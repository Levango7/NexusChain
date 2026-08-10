package org.nexus.governance.guardian;

import org.nexus.governance.GovernanceProposal;
import org.nexus.governance.GovernanceProposalRepository;
import org.nexus.governance.ProposalStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 守护人审核服务（多签 veto）。
 *
 * <p>提案通过社区投票后，可进入守护人审核阶段（{@link ProposalStatus#GUARDIAN_REVIEW}），
 * 由守护人集合进行二次把关，防止恶意提案或紧急风险提案直接生效：</p>
 * <ul>
 *   <li><b>veto（否决）</b>：任一守护人可单方面否决提案，提案置 {@link ProposalStatus#GUARDIAN_VETOED} 终止</li>
 *   <li><b>approve（批准）</b>：需 m-of-n 守护人批准才放行，提案置 {@link ProposalStatus#PASSED} 进入执行</li>
 * </ul>
 *
 * <h3>放行门槛</h3>
 * <p>{@code vetoThreshold}（m）为放行所需批准数，默认 1（任一守护人批准即放行），
 * 可通过 {@link #setVetoThreshold(int)} 调整为多数（如 n/2+1）或全体（n）。</p>
 *
 * <h3>流程接入</h3>
 * <p>典型流程：{@code tallyVotes} 通过后调用 {@link #enterReview} 进入审核，
 * 守护人调用 {@link #guardianApprove}/{@link #guardianVeto}，批准达门槛后置 PASSED 可执行。</p>
 *
 * @since 1.4
 */
@Component
public class GuardianService {

    private static final Logger logger = LoggerFactory.getLogger(GuardianService.class);

    @Autowired
    private GovernanceProposalRepository proposalRepository;

    /** 守护人集合 */
    private final Set<String> guardians = ConcurrentHashMap.newKeySet();

    /** 放行所需批准数 m（m-of-n） */
    private volatile int vetoThreshold = 1;

    /** proposalId -> 已批准守护人集合 */
    private final ConcurrentHashMap<String, Set<String>> approvals = new ConcurrentHashMap<>();

    /**
     * 添加守护人。
     *
     * @param guardian 守护人地址
     * @return 添加成功返回 true；已存在或参数非法返回 false
     */
    public boolean addGuardian(String guardian) {
        if (guardian == null) {
            return false;
        }
        boolean added = guardians.add(guardian);
        if (added) {
            logger.info("Guardian added: {} (total={})", guardian, guardians.size());
        }
        return added;
    }

    /**
     * 移除守护人。
     *
     * @param guardian 守护人地址
     * @return 移除成功返回 true
     */
    public boolean removeGuardian(String guardian) {
        if (guardian == null) {
            return false;
        }
        boolean removed = guardians.remove(guardian);
        if (removed) {
            logger.info("Guardian removed: {} (total={})", guardian, guardians.size());
        }
        return removed;
    }

    /**
     * 判断是否为守护人。
     *
     * @param guardian 地址
     * @return 是守护人返回 true
     */
    public boolean isGuardian(String guardian) {
        return guardian != null && guardians.contains(guardian);
    }

    /**
     * 返回守护人集合（只读）。
     *
     * @return 守护人集合
     */
    public Set<String> getGuardians() {
        return Collections.unmodifiableSet(guardians);
    }

    /**
     * 设置放行所需批准数 m。
     *
     * @param threshold 批准门槛（≥1）
     */
    public void setVetoThreshold(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("veto threshold must be >= 1");
        }
        this.vetoThreshold = threshold;
        logger.info("Guardian veto threshold set to {}", threshold);
    }

    public int getVetoThreshold() {
        return vetoThreshold;
    }

    /**
     * 将已通过投票的提案置为守护人审核阶段。
     *
     * @param proposalId 提案 ID
     * @return 进入审核返回 true；提案不存在或状态非 PASSED 返回 false
     */
    public boolean enterReview(String proposalId) {
        Optional<GovernanceProposal> opt = proposalRepository.findById(proposalId);
        if (!opt.isPresent()) {
            logger.warn("Enter review rejected: proposal not found {}", proposalId);
            return false;
        }
        GovernanceProposal proposal = opt.get();
        if (proposal.getStatus() != ProposalStatus.PASSED) {
            logger.warn("Enter review rejected: proposal {} status {} (require PASSED)",
                    proposalId, proposal.getStatus());
            return false;
        }
        proposal.setStatus(ProposalStatus.GUARDIAN_REVIEW);
        proposalRepository.save(proposal);
        approvals.put(proposalId, ConcurrentHashMap.newKeySet());
        logger.info("Proposal {} entered guardian review", proposalId);
        return true;
    }

    /**
     * 守护人批准提案。累计批准数达 {@code vetoThreshold} 则放行置 PASSED。
     *
     * @param proposalId 提案 ID
     * @param guardian   守护人地址
     * @return 批准成功返回 true；非守护人、提案非审核中、重复批准返回 false
     */
    public boolean guardianApprove(String proposalId, String guardian) {
        if (!isGuardian(guardian)) {
            logger.warn("Approve rejected: {} is not a guardian", guardian);
            return false;
        }
        Optional<GovernanceProposal> opt = proposalRepository.findById(proposalId);
        if (!opt.isPresent()) {
            return false;
        }
        GovernanceProposal proposal = opt.get();
        if (proposal.getStatus() != ProposalStatus.GUARDIAN_REVIEW) {
            logger.warn("Approve rejected: proposal {} status {} (require GUARDIAN_REVIEW)",
                    proposalId, proposal.getStatus());
            return false;
        }
        Set<String> approved = approvals.computeIfAbsent(proposalId, k -> ConcurrentHashMap.newKeySet());
        if (!approved.add(guardian)) {
            logger.warn("Approve rejected: guardian {} already approved {}", guardian, proposalId);
            return false;
        }
        logger.info("Guardian {} approved {} (approvals={}/{})", guardian, proposalId, approved.size(), vetoThreshold);
        if (approved.size() >= vetoThreshold) {
            proposal.setStatus(ProposalStatus.PASSED);
            proposalRepository.save(proposal);
            approvals.remove(proposalId);
            logger.info("Proposal {} passed guardian review (m-of-n reached)", proposalId);
        }
        return true;
    }

    /**
     * 守护人否决提案。任一守护人 veto 即终止提案。
     *
     * @param proposalId 提案 ID
     * @param guardian   守护人地址
     * @return 否决成功返回 true；非守护人或提案非审核中返回 false
     */
    public boolean guardianVeto(String proposalId, String guardian) {
        if (!isGuardian(guardian)) {
            logger.warn("Veto rejected: {} is not a guardian", guardian);
            return false;
        }
        Optional<GovernanceProposal> opt = proposalRepository.findById(proposalId);
        if (!opt.isPresent()) {
            return false;
        }
        GovernanceProposal proposal = opt.get();
        if (proposal.getStatus() != ProposalStatus.GUARDIAN_REVIEW) {
            logger.warn("Veto rejected: proposal {} status {} (require GUARDIAN_REVIEW)",
                    proposalId, proposal.getStatus());
            return false;
        }
        proposal.setStatus(ProposalStatus.GUARDIAN_VETOED);
        proposalRepository.save(proposal);
        approvals.remove(proposalId);
        logger.info("Proposal {} vetoed by guardian {}", proposalId, guardian);
        return true;
    }

    /**
     * 返回指定提案已批准的守护人集合（只读）。
     *
     * @param proposalId 提案 ID
     * @return 已批准守护人集合
     */
    public Set<String> getApprovals(String proposalId) {
        Set<String> approved = approvals.get(proposalId);
        return approved == null ? Collections.emptySet() : Collections.unmodifiableSet(approved);
    }

    /**
     * 判断提案是否处于守护人审核中。
     *
     * @param proposalId 提案 ID
     * @return 审核中返回 true
     */
    public boolean isUnderReview(String proposalId) {
        Optional<GovernanceProposal> opt = proposalRepository.findById(proposalId);
        return opt.isPresent() && opt.get().getStatus() == ProposalStatus.GUARDIAN_REVIEW;
    }
}