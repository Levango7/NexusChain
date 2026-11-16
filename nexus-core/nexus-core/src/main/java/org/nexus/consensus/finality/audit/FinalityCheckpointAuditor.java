package org.nexus.consensus.finality.audit;

import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.persistence.FinalityStateStore;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 最终性检查点的**语义一致性校验**（roadmap #27 / PLAN-067 挂账项 A-7：
 * 「epoch 窗口 / 规范检查点语义校验未做（需链接口）」）。
 *
 * <p><b>为什么需要它</b>：{@code finality_checkpoints} 只存
 * {@code (epoch, checkpoint_hash)}，<b>不存高度</b>；epoch 又是 1-based、与高度的
 * 换算依赖每个节点自己的 {@code epoch-length}。于是「库里 epoch N 记下的哈希，
 * 是否就是链上规范高度 {@code N × epochLength} 那个块」此前<b>没有任何校验入口</b>：
 * 用不同 {@code epoch-length} 启动的节点、或分支切换后残留的检查点，都能让最终性
 * 水位指向链上并不存在的"规范检查点"而无人发现。</p>
 *
 * <p><b>判据</b>（枚举库内既有最终化记录后逐条核对）：</p>
 * <ol>
 *   <li>{@link Verdict#HEIGHT_OUT_OF_RANGE}：{@code h = epoch × epochLength} 超出
 *       当前链头（或该高度上没有规范块）——典型成因就是配置错位/跨分支残留；</li>
 *   <li>{@link Verdict#HASH_MISMATCH}：规范高度 {@code h} 上有块，但其哈希与库内
 *       检查点哈希<b>逐字节不等</b>；</li>
 *   <li>{@link Verdict#EPOCH_GAP}：{@code 1..maxFinalizedEpoch} 之间存在没有最终化
 *       记录的 epoch（最终性应单调前进，缺号 = 票/检查点丢失）。</li>
 * </ol>
 *
 * <p><b>只读</b>：不写库、不改内存态、不触发投票；内部异常收敛为
 * {@link Verdict#UNKNOWN} 并在报告中点名（fail-visible，绝不静默放过）。</p>
 *
 * <p><b>边界</b>：只比对「本地库 × 本地规范链」，不做跨节点比对（anti-entropy 职责）、
 * 不重算最终性权重（{@code FinalityGadget} 入口处已验签与计权）、不校验
 * {@code finality_votes} 与检查点的票数关系。</p>
 */
public class FinalityCheckpointAuditor {

    private static final Logger log = LoggerFactory.getLogger(FinalityCheckpointAuditor.class);

    /** 单次审计最多核对的检查点条数（历史上限，防止端点在长链上被拖死；超限如实标记）。 */
    static final int MAX_CHECKS_PER_AUDIT = 5000;

    public enum Verdict {
        OK,
        /** 规范高度超出链头或无块（配置错位 / 跨分支残留的典型形态）。 */
        HEIGHT_OUT_OF_RANGE,
        /** 规范块哈希与库内检查点哈希不一致。 */
        HASH_MISMATCH,
        /** 最终化 epoch 序列缺号。 */
        EPOCH_GAP,
        /** 校验过程异常（fail-visible）。 */
        UNKNOWN
    }

    /** 单个检查点的核对结果。 */
    public record Check(long epoch, long height, String storedHash, String canonicalHash,
                        Verdict verdict, String detail) { }

    private final FinalityGadget gadget;
    private final NexusChainBlockChain chain;
    private final long epochLength;

    public FinalityCheckpointAuditor(FinalityGadget gadget, NexusChainBlockChain chain, long epochLength) {
        this.gadget = gadget;
        this.chain = chain;
        this.epochLength = epochLength <= 0 ? 32L : epochLength;
    }

    /**
     * 执行一次审计。
     *
     * @param headHeight 当前链头高度（调用方传入，避免本类自行漂移）
     * @return 可直接序列化为 JSON 的报告
     */
    public Map<String, Object> audit(long headHeight) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("epochLength", epochLength);
        report.put("headHeight", headHeight);

        Map<Verdict, Integer> tally = new LinkedHashMap<>();
        for (Verdict v : Verdict.values()) {
            tally.put(v, 0);
        }
        List<Check> checks = new ArrayList<>();

        FinalityStateStore store = gadget == null ? null : gadget.getStateStore();
        if (store == null) {
            report.put("verdict", Verdict.UNKNOWN.name());
            report.put("detail", "no FinalityStateStore wired (in-memory / non-pos topology)");
            report.put("tally", tally);
            report.put("checks", checks);
            return report;
        }

        // 枚举库内既有最终化记录（key = "epoch|hex"）——这是唯一能发现"存了错哈希"的入口：
        // 若改为「按 epoch 反算高度再问 isFinalized」，错哈希的记录根本不会被枚举到。
        //
        // 逐**行**核对（不按 epoch 合并）：同一 epoch 可能同时存在"正确行"与"跨分支/篡改残留行"
        // ——启动时的补最终化扫描会把正确行重新推导出来，而残留的错行不会被删除。
        // 若按 epoch 合并后"任一匹配即 OK"，这类垃圾行会被静默放过（2026-11-16 演习实证）。
        List<Map.Entry<Long, String>> rows = new ArrayList<>();
        try {
            for (String key : store.loadAllFinalized().keySet()) {
                int sep = key == null ? -1 : key.indexOf('|');
                if (sep <= 0) {
                    continue;
                }
                try {
                    long epoch = Long.parseLong(key.substring(0, sep));
                    rows.add(new java.util.AbstractMap.SimpleImmutableEntry<>(epoch, key.substring(sep + 1)));
                } catch (NumberFormatException ignored) {
                    // 键格式异常：计入 UNKNOWN（fail-visible），不静默跳过
                    checks.add(new Check(-1L, -1L, key, null, Verdict.UNKNOWN, "unparseable checkpoint key"));
                    tally.merge(Verdict.UNKNOWN, 1, Integer::sum);
                }
            }
        } catch (RuntimeException e) {
            checks.add(new Check(-1L, -1L, null, null, Verdict.UNKNOWN, "enumerate failed: " + e.getMessage()));
            tally.merge(Verdict.UNKNOWN, 1, Integer::sum);
            rows.clear();
        }

        boolean truncated = rows.size() > MAX_CHECKS_PER_AUDIT;
        int processed = 0;
        java.util.Set<Long> recordedEpochs = new java.util.TreeSet<>();
        long maxRecordedInChain = 0L;
        long maxAnyRecorded = 0L;
        for (Map.Entry<Long, String> e : rows) {
            if (processed >= MAX_CHECKS_PER_AUDIT) {
                break;
            }
            processed++;
            long epoch = e.getKey();
            maxAnyRecorded = Math.max(maxAnyRecorded, epoch);
            if (epoch > 0 && epoch * epochLength <= headHeight) {
                maxRecordedInChain = Math.max(maxRecordedInChain, epoch);
            }
            recordedEpochs.add(epoch);
            Check c = checkOne(epoch, e.getValue(), headHeight);
            checks.add(c);
            tally.merge(c.verdict(), 1, Integer::sum);
        }

        // 缺号判定（**只算内部空洞**）：最终性天然落后链头若干 epoch（轮转 + 视图切换，
        // PLAN-047 实测基线 lag≈2），尾随未最终化属正常，不能算缺号。
        // 因此：只在 [1, maxRecordedInChain) 区间找没有记录行的 epoch；
        // 尾随差（headEpoch − maxRecordedInChain）单独作为 finalityLagEpochs 报告（信息项）。
        long headEpoch = headHeight <= 0 ? 0L : (headHeight - 1) / epochLength + 1;
        long gapCount = 0L;
        for (long epoch = 1; epoch < maxRecordedInChain && epoch <= MAX_CHECKS_PER_AUDIT; epoch++) {
            if (recordedEpochs.contains(epoch)) {
                continue;
            }
            gapCount++;
            checks.add(new Check(epoch, epoch * epochLength, null, null, Verdict.EPOCH_GAP,
                    "interior gap: no finalized checkpoint recorded below the highest recorded epoch "
                            + maxRecordedInChain));
            tally.merge(Verdict.EPOCH_GAP, 1, Integer::sum);
        }
        long finalityLag = Math.max(0L, headEpoch - maxRecordedInChain);

        int failures = tally.getOrDefault(Verdict.HASH_MISMATCH, 0)
                + tally.getOrDefault(Verdict.HEIGHT_OUT_OF_RANGE, 0)
                + tally.getOrDefault(Verdict.EPOCH_GAP, 0)
                + tally.getOrDefault(Verdict.UNKNOWN, 0);

        report.put("finalizedCheckpoints", gadget.finalizedCheckpointCount());
        report.put("recordedRows", rows.size());
        report.put("recordedEpochs", recordedEpochs.size());
        report.put("maxRecordedEpoch", maxAnyRecorded);
        report.put("maxRecordedInChain", maxRecordedInChain);
        report.put("finalityLagEpochs", finalityLag);
        report.put("gapEpochs", gapCount);
        report.put("truncated", truncated);
        report.put("tally", tally);
        report.put("verdict", failures == 0 ? Verdict.OK.name() : "FAIL");
        report.put("checks", checks);
        log.info("Finality checkpoint audit: head={} rows={} verdict={} tally={}",
                headHeight, rows.size(), report.get("verdict"), tally);
        return report;
    }

    /** 核对单条库内检查点记录（逐行，不做任何 epoch 级合并）。 */
    private Check checkOne(long epoch, String storedHash, long headHeight) {
        long height = epoch * epochLength;
        try {
            if (height <= 0 || height > headHeight) {
                return new Check(epoch, height, storedHash, null, Verdict.HEIGHT_OUT_OF_RANGE,
                        "epoch " + epoch + " × epochLength " + epochLength + " = height " + height
                                + " is outside the chain (head=" + headHeight + ")");
            }
            if (chain == null) {
                return new Check(epoch, height, storedHash, null, Verdict.UNKNOWN,
                        "no NexusChainBlockChain wired; cannot resolve canonical block");
            }
            Block b = chain.getCanonicalBlock(height);
            if (b == null || b.getHash() == null) {
                return new Check(epoch, height, storedHash, null, Verdict.HEIGHT_OUT_OF_RANGE,
                        "no canonical block at height " + height);
            }
            String canonical = org.apache.commons.codec.binary.Hex.encodeHexString(b.getHash());
            if (storedHash != null && storedHash.equalsIgnoreCase(canonical)) {
                return new Check(epoch, height, storedHash, canonical, Verdict.OK,
                        "stored hash matches canonical block at height " + height);
            }
            return new Check(epoch, height, storedHash, canonical, Verdict.HASH_MISMATCH,
                    "stored=" + abbreviate(storedHash) + " canonical=" + abbreviate(canonical)
                            + " at height " + height
                            + (storedHash != null && storedHash.contains(",")
                            ? " (multi-hash key: some variants do not match)" : ""));
        } catch (RuntimeException e) {
            return new Check(epoch, height, storedHash, null, Verdict.UNKNOWN, "audit error: " + e.getMessage());
        }
    }

    private static String abbreviate(String hex) {
        if (hex == null) {
            return "null";
        }
        return hex.length() <= 16 ? hex : hex.substring(0, 16) + "…";
    }
}
