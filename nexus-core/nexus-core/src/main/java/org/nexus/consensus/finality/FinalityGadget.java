package org.nexus.consensus.finality;

import org.nexus.consensus.pos.SlashingService;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.consensus.finality.SignatureAggregator.AggregatedSignature;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NexFinality 最终性层核心（ADR-030 M2：BFT 投票子协议，权重判定；M3 聚合签名前置）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>收集验证者对 epoch 检查点的 {@link Vote}</li>
 *   <li>按 {@link StakingService#getStake} 累积投票权重</li>
 *   <li>当 votedWeight ≥ 2/3 × totalWeight 时标记 finalized</li>
 *   <li>检测双签产生 {@link EquivocationEvidence}</li>
 * </ul>
 *
 * <p>M1 阶段不验签（签名验证明智至 M3 BLS 集成时统一接入，
 * 当前仅按提交者身份累积权重，便于单节点测试）。</p>
 */
public class FinalityGadget {

    private final ValidatorRegistry validatorRegistry;
    private final StakingService stakingService;

    private final Map<String, Set<String>> epochCheckpointVoters = new ConcurrentHashMap<>();
    private final Map<String, List<Vote>> epochCheckpointVotes = new ConcurrentHashMap<>(); // M3: 投票对象存档，供聚合验签
    private final Map<String, BigDecimal> epochCheckpointWeights = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> epochTotalWeight = new ConcurrentHashMap<>();
    private final Set<String> finalizedCheckpoints = ConcurrentHashMap.newKeySet();
    private final List<EquivocationEvidence> detectedEquivocations = new ArrayList<>();
    /** 已执行罚没的作恶者（地址），防止同一证据重复 slash 造成过度惩罚。 */
    private final Set<String> slashedOffenders = ConcurrentHashMap.newKeySet();

    /** 可选：罚没联动（ADR-030 M4 连接轴）。未注入时仅记录证据，不执行罚没。 */
    private SlashingService slashingService;

    /**
     * 签名聚合器（ADR-030 M3 架构层）。
     * 默认使用 {@link CollectingAggregator}（收集式，诚实降级）；
     * blst 就位后注入 BlstSignatureAggregator 实现真实 O(1) 聚合验签。
     */
    private SignatureAggregator signatureAggregator = new SignatureAggregator.CollectingAggregator();

    public FinalityGadget(ValidatorRegistry registry, StakingService stakingService) {
        this.validatorRegistry = registry;
        this.stakingService = stakingService;
    }

    /**
     * 注入签名聚合器（M3 架构挂接点）。
     * 默认收集式；生产环境应在 blst 就位后替换为真实聚合实现。
     */
    public void setSignatureAggregator(SignatureAggregator aggregator) {
        this.signatureAggregator = Objects.requireNonNull(aggregator, "aggregator must not be null");
    }

    /**
     * 注入罚没服务（M4 连接轴）。注入后，检测到双签证据将自动执行
     * {@link SlashingService#slash(String, SlashingService.Offense)}（DOUBLE_SIGN，没收全部质押并置 SLASHED）。
     */
    public void setSlashingService(SlashingService slashingService) {
        this.slashingService = slashingService;
    }

    /**
     * 提交检查点投票。
     *
     * @param vote BFT 投票记录
     * @return 该检查点的当前最终化记录
     */
    public FinalityRecord submitVote(Vote vote) {
        long epoch = vote.getEpoch();
        String checkpointKey = epochKey(epoch, vote.getCheckpointHash());

        // 总权重按 epoch 快照（首次投票时取活跃验证者权重并锁定）
        epochTotalWeight.computeIfAbsent(String.valueOf(epoch), k -> computeTotalWeight());
        BigDecimal total = epochTotalWeight.get(String.valueOf(epoch));

        // 同验证者重复投票同检查点：幂等拒绝
        Set<String> voters = epochCheckpointVoters.computeIfAbsent(checkpointKey, k -> ConcurrentHashMap.newKeySet());
        if (!voters.add(vote.getValidatorAddress())) {
            return record(epoch, vote.getCheckpointHash(), total);
        }

        // 双签检测：同 epoch 不同检查点
        for (Map.Entry<String, Set<String>> e : epochCheckpointVoters.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            if (parts.length == 2 && parts[0].equals(String.valueOf(epoch))
                    && !parts[1].equals(Arrays.toString(vote.getCheckpointHash()))
                    && e.getValue().contains(vote.getValidatorAddress())) {
                EquivocationEvidence ev = new EquivocationEvidence(
                        vote, new Vote(epoch, parseCheckpoint(parts[1]), vote.getValidatorAddress(), new byte[0]));
                detectedEquivocations.add(ev);
                // M4 连接轴：注入罚没服务时，双签证据即时触发 slash（幂等，防止重复罚没）
                if (slashingService != null && slashedOffenders.add(vote.getValidatorAddress())) {
                    slashingService.slash(vote.getValidatorAddress(), SlashingService.Offense.DOUBLE_SIGN);
                }
            }
        }

        // 权重累积
        Validator v = validatorRegistry.getValidator(vote.getValidatorAddress());
        if (v == null || v.getStatus() != ValidatorStatus.ACTIVE) {
            return record(epoch, vote.getCheckpointHash(), total);
        }
        BigDecimal weight = stakingService.getStake(vote.getValidatorAddress());
        epochCheckpointWeights.merge(checkpointKey, weight, BigDecimal::add);
        epochCheckpointVotes.computeIfAbsent(checkpointKey, k -> new ArrayList<>()).add(vote);

        BigDecimal voted = epochCheckpointWeights.get(checkpointKey);
        boolean finalized = voted.multiply(BigDecimal.valueOf(3))
                .compareTo(total.multiply(BigDecimal.valueOf(2))) >= 0;
        if (finalized) {
            // M3 架构挂接：最终化前对投票集合做聚合验签（收集式降级或 blst 聚合）
            AggregatedSignature agg = signatureAggregator.aggregate(epochCheckpointVotes.get(checkpointKey));
            if (agg != null && !signatureAggregator.verifyAggregate(epochCheckpointVotes.get(checkpointKey), agg)) {
                // fail-closed：聚合验签失败则不最终化
                finalized = false;
            } else {
                finalizedCheckpoints.add(checkpointKey);
            }
        }
        return new FinalityRecord(epoch, vote.getCheckpointHash(), voted, total, finalized);
    }

    /**
     * 查询检查点当前最终化状态。
     */
    public FinalityRecord getFinality(long epoch, byte[] checkpointHash) {
        BigDecimal total = epochTotalWeight.getOrDefault(String.valueOf(epoch), computeTotalWeight());
        return record(epoch, checkpointHash, total);
    }

    /**
     * 检查点是否已最终化。
     */
    public boolean isFinalized(long epoch, byte[] checkpointHash) {
        return finalizedCheckpoints.contains(epochKey(epoch, checkpointHash));
    }

    /**
     * 已检测到的双签证据（供 slashing 联动消费）。
     */
    public List<EquivocationEvidence> getDetectedEquivocations() {
        return Collections.unmodifiableList(detectedEquivocations);
    }

    private FinalityRecord record(long epoch, byte[] checkpointHash, BigDecimal total) {
        String key = epochKey(epoch, checkpointHash);
        BigDecimal voted = epochCheckpointWeights.getOrDefault(key, BigDecimal.ZERO);
        return new FinalityRecord(epoch, checkpointHash, voted, total, finalizedCheckpoints.contains(key));
    }

    private BigDecimal computeTotalWeight() {
        BigDecimal total = BigDecimal.ZERO;
        for (Validator v : validatorRegistry.getActiveValidators()) {
            if (v.getStatus() == ValidatorStatus.ACTIVE) {
                total = total.add(stakingService.getStake(v.getAddress()));
            }
        }
        return total;
    }

    private String epochKey(long epoch, byte[] checkpointHash) {
        return epoch + "|" + Arrays.toString(checkpointHash);
    }

    private byte[] parseCheckpoint(String s) {
        String inner = s.substring(1, s.length() - 1).replace(" ", "");
        String[] parts = inner.split(",");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Byte.parseByte(parts[i]);
        return out;
    }
}
