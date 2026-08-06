package org.nexus.governance.recall;

import org.nexus.governance.GovernanceProposal;
import org.nexus.governance.GovernanceService;
import org.nexus.governance.ProposalStatus;
import org.nexus.governance.ProposalType;
import org.nexus.governance.guardian.GuardianService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 守护人罢免服务。
 *
 * <p>当守护人作恶（恶意否决正当提案、串谋、私钥泄露等）时，可通过治理提案
 * 罢免该守护人。罢免提案走正常治理投票流程（投票期 + quorum + timelock），
 * 通过后由本服务从 {@link GuardianService} 移除该守护人，并记录罢免处置结果。</p>
 *
 * <h3>罢免流程</h3>
 * <ol>
 *   <li>任意社区成员调用 {@link #submitRecallProposal} 提交罢免提案，附证据</li>
 *   <li>本服务创建关联的治理提案（{@link ProposalType#CUSTOM}），进入正常投票流程</li>
 *   <li>治理提案通过后（{@link ProposalStatus#PASSED} 或经守护人审核置 PASSED），
 *       调用 {@link #executeRecallIfPassed} 执行罢免</li>
 *   <li>罢免执行：从 {@link GuardianService} 移除目标守护人，置提案 EXECUTED，记录处置结果</li>
 * </ol>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>不绕过治理</b>：罢免本身需经社区投票通过，避免少数人恶意罢免守护人</li>
 *   <li><b>证据留痕</b>：罢免证据随提案持久化，供事后审计</li>
 *   <li><b>幂等执行</b>：同一罢免提案重复执行返回已处置状态，不会重复移除</li>
 *   <li><b>不可罢免非守护人</b>：目标必须是当前守护人集合成员</li>
 * </ul>
 *
 * @since 1.5
 */
@Component
public class GuardianRecallService {

    private static final Logger logger = LoggerFactory.getLogger(GuardianRecallService.class);

    @Autowired
    private GuardianService guardianService;

    @Autowired
    private GovernanceService governanceService;

    /** recallProposalId -> RecallProposal */
    private final ConcurrentHashMap<String, RecallProposal> recallProposals = new ConcurrentHashMap<>();

    /** governanceProposalId -> recallProposalId（用于执行时反查） */
    private final ConcurrentHashMap<String, String> governanceToRecall = new ConcurrentHashMap<>();

    /**
     * 提交守护人罢免提案。
     *
     * <p>创建一个 {@link ProposalType#CUSTOM} 治理提案（描述为 "Guardian Recall: {target}"），
     * 进入正常投票流程。同时持久化罢免证据，供投票人评估与事后审计。</p>
     *
     * @param targetGuardian 目标守护人地址
     * @param evidences      罢免证据列表
     * @param proposer       提案发起人地址
     * @return 罢免提案 ID；目标非守护人、无证据或参数非法返回 null
     */
    public String submitRecallProposal(String targetGuardian, List<RecallEvidence> evidences, String proposer) {
        if (targetGuardian == null || proposer == null || evidences == null || evidences.isEmpty()) {
            logger.warn("Recall proposal rejected: invalid parameters targetGuardian={} evidences={} proposer={}",
                    targetGuardian, evidences == null ? 0 : evidences.size(), proposer);
            return null;
        }
        if (!guardianService.isGuardian(targetGuardian)) {
            logger.warn("Recall proposal rejected: target {} is not a guardian", targetGuardian);
            return null;
        }
        // 创建关联治理提案，走正常投票流程
        GovernanceProposal governanceProposal = new GovernanceProposal();
        governanceProposal.setType(ProposalType.CUSTOM);
        governanceProposal.setProposer(proposer);
        governanceProposal.setParameterChanges(new ArrayList<>());
        String governanceProposalId = governanceService.submitProposal(governanceProposal);

        String recallProposalId = UUID.randomUUID().toString();
        RecallProposal recall = new RecallProposal(
                recallProposalId, targetGuardian, new ArrayList<>(evidences),
                governanceProposalId, proposer, Instant.now());
        recallProposals.put(recallProposalId, recall);
        governanceToRecall.put(governanceProposalId, recallProposalId);
        logger.warn("Guardian recall proposal submitted: recallId={} target={} governanceId={} evidences={}",
                recallProposalId, targetGuardian, governanceProposalId, evidences.size());
        return recallProposalId;
    }

    /**
     * 查询指定罢免提案。
     *
     * @param recallProposalId 罢免提案 ID
     * @return 罢免提案；不存在返回 null
     */
    public RecallProposal getRecallProposal(String recallProposalId) {
        return recallProposals.get(recallProposalId);
    }

    /**
     * 根据关联的治理提案 ID 反查罢免提案。
     *
     * @param governanceProposalId 治理提案 ID
     * @return 罢免提案；不存在返回 null
     */
    public RecallProposal getRecallByGovernanceProposal(String governanceProposalId) {
        String recallId = governanceToRecall.get(governanceProposalId);
        return recallId == null ? null : recallProposals.get(recallId);
    }

    /**
     * 列出所有罢免提案。
     *
     * @return 罢免提案列表
     */
    public List<RecallProposal> listRecallProposals() {
        return new ArrayList<>(recallProposals.values());
    }

    /**
     * 列出指定状态的罢免提案。
     *
     * @param status 状态
     * @return 罢免提案列表
     */
    public List<RecallProposal> listRecallProposalsByStatus(RecallProposal.Status status) {
        List<RecallProposal> result = new ArrayList<>();
        for (RecallProposal p : recallProposals.values()) {
            if (p.getStatus() == status) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * 当关联治理提案通过后执行罢免：从守护人集合移除目标守护人。
     *
     * <p>幂等：若提案已执行返回 true；若治理提案未通过返回 false。</p>
     *
     * @param recallProposalId 罢免提案 ID
     * @return 罢免执行成功返回 true；提案不存在、治理提案未通过或目标已非守护人返回 false
     */
    public boolean executeRecallIfPassed(String recallProposalId) {
        RecallProposal recall = recallProposals.get(recallProposalId);
        if (recall == null) {
            logger.warn("Execute recall rejected: recall proposal {} not found", recallProposalId);
            return false;
        }
        if (recall.getStatus() == RecallProposal.Status.EXECUTED) {
            logger.info("Recall proposal {} already executed", recallProposalId);
            return true;
        }
        GovernanceProposal governance = governanceService.getProposal(recall.getGovernanceProposalId());
        if (governance == null) {
            logger.warn("Execute recall rejected: governance proposal {} not found",
                    recall.getGovernanceProposalId());
            return false;
        }
        // 仅当治理提案处于 PASSED 或 EXECUTED 时才执行罢免
        if (governance.getStatus() != ProposalStatus.PASSED
                && governance.getStatus() != ProposalStatus.EXECUTED) {
            logger.warn("Execute recall rejected: governance proposal {} status {} (require PASSED/EXECUTED)",
                    recall.getGovernanceProposalId(), governance.getStatus());
            // 同步罢免提案状态
            if (governance.getStatus() == ProposalStatus.REJECTED
                    || governance.getStatus() == ProposalStatus.GUARDIAN_VETOED) {
                recall.setStatus(RecallProposal.Status.REJECTED);
                recall.setResolution("Governance proposal rejected/vetoed");
            } else if (governance.getStatus() == ProposalStatus.EXPIRED) {
                recall.setStatus(RecallProposal.Status.EXPIRED);
                recall.setResolution("Governance proposal expired");
            }
            return false;
        }
        boolean removed = guardianService.removeGuardian(recall.getTargetGuardian());
        if (removed) {
            recall.setStatus(RecallProposal.Status.EXECUTED);
            recall.setResolution("Guardian removed at " + Instant.now());
            logger.warn("Guardian recall EXECUTED: recallId={} target={} governanceId={}",
                    recallProposalId, recall.getTargetGuardian(), recall.getGovernanceProposalId());
        } else {
            recall.setStatus(RecallProposal.Status.EXECUTED);
            recall.setResolution("Target guardian already absent; no-op");
            logger.info("Guardian recall no-op: target {} already absent", recall.getTargetGuardian());
        }
        return true;
    }

    /**
     * 返回所有罢免证据记录（跨所有罢免提案，用于全局审计）。
     *
     * @return 证据列表
     */
    public List<RecallEvidence> listAllEvidences() {
        List<RecallEvidence> all = new ArrayList<>();
        for (RecallProposal p : recallProposals.values()) {
            all.addAll(p.getEvidences());
        }
        return all;
    }

    /**
     * 返回针对指定守护人的所有罢免提案。
     *
     * @param guardian 守护人地址
     * @return 罢免提案列表
     */
    public List<RecallProposal> listRecallsAgainst(String guardian) {
        List<RecallProposal> result = new ArrayList<>();
        for (RecallProposal p : recallProposals.values()) {
            if (p.getTargetGuardian().equals(guardian)) {
                result.add(p);
            }
        }
        return Collections.unmodifiableList(result);
    }
}