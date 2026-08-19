package org.nexus.settlement.funds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多签审批服务（FundSweep 增强）。
 *
 * <p>资金归集执行前需经过多签审批：发起审批 → 多个审批人确认 → 达到阈值 → 可执行归集。
 *
 * <p>审批流程：
 * <ul>
 *   <li>{@link #requestApproval}：发起审批请求，指定审批人和阈值</li>
 *   <li>{@link #approve}：审批人确认</li>
 *   <li>{@link #reject}：审批人拒绝</li>
 *   <li>{@link #isApproved}：检查是否达到审批阈值</li>
 *   <li>{@link #getStatus}：获取审批状态</li>
 * </ul>
 *
 * @since 2.12.0
 */
@Service
public class MultiSigApprovalService {

    private static final Logger log = LoggerFactory.getLogger(MultiSigApprovalService.class);

    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();

    /** 发起审批请求 */
    public ApprovalRequest requestApproval(String orderId, String requester,
                                           List<String> approvers, int threshold) {
        String approvalId = "approval-" + UUID.randomUUID().toString().substring(0, 8);
        ApprovalRequest req = new ApprovalRequest(approvalId, orderId, requester,
                new ArrayList<>(approvers), threshold);
        requests.put(approvalId, req);
        log.info("Approval requested: {} for order {} by {} (threshold={}/{})",
                approvalId, orderId, requester, threshold, approvers.size());
        return req;
    }

    /** 审批人确认 */
    public boolean approve(String approvalId, String approver) {
        ApprovalRequest req = requests.get(approvalId);
        if (req == null) {
            log.warn("Approval not found: {}", approvalId);
            return false;
        }
        if (req.status == ApprovalStatus.REJECTED || req.status == ApprovalStatus.EXPIRED) {
            log.warn("Approval already rejected/expired: {}", approvalId);
            return false;
        }
        if (!req.approvers.contains(approver)) {
            log.warn("Approver not authorized: {} for {}", approver, approvalId);
            return false;
        }
        req.approvedBy.add(approver);
        if (req.approvedBy.size() >= req.threshold) {
            req.status = ApprovalStatus.APPROVED;
        }
        log.info("Approval {} by {} ({}/{})", approvalId, approver, req.approvedBy.size(), req.threshold);
        return true;
    }

    /** 审批人拒绝 */
    public boolean reject(String approvalId, String approver) {
        ApprovalRequest req = requests.get(approvalId);
        if (req == null || !req.approvers.contains(approver)) {
            return false;
        }
        req.status = ApprovalStatus.REJECTED;
        req.rejectedBy = approver;
        log.info("Approval {} rejected by {}", approvalId, approver);
        return true;
    }

    /** 检查是否达到审批阈值 */
    public boolean isApproved(String approvalId) {
        ApprovalRequest req = requests.get(approvalId);
        return req != null && req.status == ApprovalStatus.APPROVED;
    }

    /** 获取审批状态 */
    public ApprovalRequest getStatus(String approvalId) {
        return requests.get(approvalId);
    }

    // ==================== 审批请求实体 ====================

    public enum ApprovalStatus {
        PENDING, APPROVED, REJECTED, EXPIRED
    }

    public static class ApprovalRequest {
        private final String approvalId;
        private final String orderId;
        private final String requester;
        private final List<String> approvers;
        private final int threshold;
        private final Set<String> approvedBy = ConcurrentHashMap.newKeySet();
        private volatile String rejectedBy;
        private volatile ApprovalStatus status;
        private final Instant createdAt;

        public ApprovalRequest(String approvalId, String orderId, String requester,
                               List<String> approvers, int threshold) {
            this.approvalId = approvalId;
            this.orderId = orderId;
            this.requester = requester;
            this.approvers = approvers;
            this.threshold = threshold;
            this.status = ApprovalStatus.PENDING;
            this.createdAt = Instant.now();
        }

        public String getApprovalId() { return approvalId; }
        public String getOrderId() { return orderId; }
        public String getRequester() { return requester; }
        public List<String> getApprovers() { return approvers; }
        public int getThreshold() { return threshold; }
        public Set<String> getApprovedBy() { return approvedBy; }
        public String getRejectedBy() { return rejectedBy; }
        public ApprovalStatus getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
    }
}