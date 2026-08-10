package org.nexus.governance.emergency;

import org.nexus.governance.ConfigSnapshot;
import org.nexus.governance.GovernableParameterRegistry;
import org.nexus.governance.guardian.GuardianService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 紧急回滚服务。
 *
 * <p>当发生严重安全事件（如私钥泄露、合约漏洞、参数被恶意篡改等）时，
 * 允许守护人集合绕过正常提案流程（投票期 + timelock 延迟），
 * 通过 m-of-n 紧急批准直接将参数注册表恢复到指定历史快照版本。</p>
 *
 * <h3>与正常回滚的区别</h3>
 * <table>
 *   <caption>表：正常回滚与紧急回滚对照表</caption>
 *   <tr><th>维度</th><th>正常回滚（{@code createRollbackProposal}）</th><th>紧急回滚（本服务）</th></tr>
 *   <tr><td>触发方</td><td>任意提案人</td><td>守护人集合</td></tr>
 *   <tr><td>审批流程</td><td>投票期 + quorum + timelock</td><td>m-of-n 守护人批准即生效</td></tr>
 *   <tr><td>生效时延</td><td>数天（投票 + timelock）</td><td>分钟级（守护人签名聚合）</td></tr>
 *   <tr><td>适用场景</td><td>常规参数调优</td><td>严重安全事件应急</td></tr>
 *   <tr><td>审计</td><td>提案记录</td><td>独立审计日志（{@link EmergencyRollbackRecord}）</td></tr>
 * </table>
 *
 * <h3>使用流程</h3>
 * <ol>
 *   <li>发生安全事件，首位守护人调用 {@link #initiateEmergencyRollback} 发起回滚请求</li>
 *   <li>其余守护人调用 {@link #approveEmergencyRollback} 追加批准</li>
 *   <li>累计批准数达 {@code emergencyThreshold}（m-of-n）后调用 {@link #executeEmergencyRollback} 立即生效</li>
 *   <li>审计日志写入 {@link #listAuditLog}，可供事后追溯</li>
 * </ol>
 *
 * <h3>风险控制</h3>
 * <ul>
 *   <li>紧急回滚跳过 timelock，需严格限制 {@code emergencyThreshold}（建议 ≥ n/2+1）</li>
 *   <li>所有紧急回滚均记录审计日志，含完整守护人批准集合与原因</li>
 *   <li>回滚目标版本必须存在于 {@link GovernableParameterRegistry} 快照历史中</li>
 * </ul>
 *
 * @since 1.5
 */
@Component
public class EmergencyRollbackService {

    private static final Logger logger = LoggerFactory.getLogger(EmergencyRollbackService.class);

    @Autowired
    private GovernableParameterRegistry parameterRegistry;

    @Autowired
    private GuardianService guardianService;

    /** 紧急回滚放行门槛 m（m-of-n 守护人批准） */
    private volatile int emergencyThreshold = 1;

    /** 待执行紧急回滚请求：requestId -> 待批准集合 */
    private final java.util.concurrent.ConcurrentHashMap<String, EmergencyRollbackRequest> pendingRequests =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 审计日志（按时间顺序） */
    private final ConcurrentLinkedDeque<EmergencyRollbackRecord> auditLog = new ConcurrentLinkedDeque<>();

    /**
     * 发起紧急回滚请求。
     *
     * <p>首位守护人创建回滚请求并自动计入批准集合。请求挂起等待其余守护人追加批准，
     * 累计达 {@code emergencyThreshold} 后可执行。</p>
     *
     * @param targetSnapshotVersion 目标快照版本号
     * @param initiator             发起守护人地址
     * @param reason                回滚原因（应描述安全事件）
     * @return 回滚请求 ID；发起人非守护人、快照不存在或参数非法返回 null
     */
    public String initiateEmergencyRollback(int targetSnapshotVersion, String initiator, String reason) {
        if (initiator == null || reason == null || reason.isEmpty()) {
            logger.warn("Initiate emergency rollback rejected: invalid parameters initiator={} reason={}",
                    initiator, reason);
            return null;
        }
        if (!guardianService.isGuardian(initiator)) {
            logger.warn("Initiate emergency rollback rejected: {} is not a guardian", initiator);
            return null;
        }
        ConfigSnapshot snapshot = parameterRegistry.getSnapshot(targetSnapshotVersion);
        if (snapshot == null) {
            logger.warn("Initiate emergency rollback rejected: snapshot version {} not found",
                    targetSnapshotVersion);
            return null;
        }
        String requestId = UUID.randomUUID().toString();
        EmergencyRollbackRequest request = new EmergencyRollbackRequest(
                requestId, targetSnapshotVersion, initiator, reason, Instant.now());
        pendingRequests.put(requestId, request);
        logger.warn("Emergency rollback initiated: requestId={} targetVersion={} initiator={} reason='{}'",
                requestId, targetSnapshotVersion, initiator, reason);
        return requestId;
    }

    /**
     * 守护人追加批准紧急回滚请求。
     *
     * @param requestId 回滚请求 ID
     * @param guardian  守护人地址
     * @return 批准成功返回 true；非守护人、请求不存在、重复批准返回 false
     */
    public boolean approveEmergencyRollback(String requestId, String guardian) {
        if (requestId == null || guardian == null) {
            return false;
        }
        if (!guardianService.isGuardian(guardian)) {
            logger.warn("Approve emergency rollback rejected: {} is not a guardian", guardian);
            return false;
        }
        EmergencyRollbackRequest request = pendingRequests.get(requestId);
        if (request == null) {
            logger.warn("Approve emergency rollback rejected: request {} not found", requestId);
            return false;
        }
        if (!request.approvals.add(guardian)) {
            logger.warn("Approve emergency rollback rejected: guardian {} already approved {}", guardian, requestId);
            return false;
        }
        logger.warn("Emergency rollback {} approved by {} (approvals={}/{})",
                requestId, guardian, request.approvals.size(), emergencyThreshold);
        return true;
    }

    /**
     * 执行紧急回滚：检查批准数达门槛后直接恢复参数注册表到目标快照版本。
     *
     * <p>跳过 timelock 与正常治理流程，立即生效。审计日志同步写入。</p>
     *
     * @param requestId 回滚请求 ID
     * @return 回滚成功返回 true；请求不存在、批准数不足或快照恢复失败返回 false
     */
    public boolean executeEmergencyRollback(String requestId) {
        EmergencyRollbackRequest request = pendingRequests.get(requestId);
        if (request == null) {
            logger.warn("Execute emergency rollback rejected: request {} not found", requestId);
            return false;
        }
        if (request.approvals.size() < emergencyThreshold) {
            logger.warn("Execute emergency rollback rejected: request {} approvals {} < threshold {}",
                    requestId, request.approvals.size(), emergencyThreshold);
            return false;
        }
        boolean restored = parameterRegistry.restoreVersionedSnapshot(request.targetSnapshotVersion);
        Instant executedAt = Instant.now();
        EmergencyRollbackRecord record = new EmergencyRollbackRecord(
                request.requestId,
                request.initiator,
                new ArrayList<>(request.approvals),
                request.targetSnapshotVersion,
                request.reason,
                executedAt,
                restored);
        auditLog.addLast(record);
        pendingRequests.remove(requestId);
        if (restored) {
            logger.warn("Emergency rollback EXECUTED: requestId={} targetVersion={} approvals={} reason='{}'",
                    requestId, request.targetSnapshotVersion, request.approvals.size(), request.reason);
        } else {
            logger.error("Emergency rollback FAILED to restore snapshot: requestId={} targetVersion={}",
                    requestId, request.targetSnapshotVersion);
        }
        return restored;
    }

    /**
     * 便捷方法：一次性提交完整批准集合执行紧急回滚。
     *
     * <p>适用于链下已聚合守护人签名的场景：直接传入完整批准集合，
     * 校验 m-of-n 通过后立即执行回滚并写入审计日志。</p>
     *
     * @param targetSnapshotVersion 目标快照版本号
     * @param guardianApprovals     守护人批准地址集合
     * @param reason                回滚原因
     * @return 回滚成功返回 true；批准数不足、含非守护人或快照不存在返回 false
     */
    public boolean emergencyRollback(int targetSnapshotVersion, Set<String> guardianApprovals, String reason) {
        if (guardianApprovals == null || guardianApprovals.isEmpty() || reason == null || reason.isEmpty()) {
            logger.warn("Emergency rollback rejected: invalid parameters");
            return false;
        }
        // 校验所有批准方均为守护人
        Set<String> validGuardians = new LinkedHashSet<>();
        for (String g : guardianApprovals) {
            if (!guardianService.isGuardian(g)) {
                logger.warn("Emergency rollback rejected: {} is not a guardian", g);
                return false;
            }
            validGuardians.add(g);
        }
        if (validGuardians.size() < emergencyThreshold) {
            logger.warn("Emergency rollback rejected: approvals {} < threshold {}",
                    validGuardians.size(), emergencyThreshold);
            return false;
        }
        ConfigSnapshot snapshot = parameterRegistry.getSnapshot(targetSnapshotVersion);
        if (snapshot == null) {
            logger.warn("Emergency rollback rejected: snapshot version {} not found", targetSnapshotVersion);
            return false;
        }
        String requestId = UUID.randomUUID().toString();
        boolean restored = parameterRegistry.restoreVersionedSnapshot(targetSnapshotVersion);
        EmergencyRollbackRecord record = new EmergencyRollbackRecord(
                requestId,
                validGuardians.iterator().next(),
                new ArrayList<>(validGuardians),
                targetSnapshotVersion,
                reason,
                Instant.now(),
                restored);
        auditLog.addLast(record);
        if (restored) {
            logger.warn("Emergency rollback EXECUTED (one-shot): requestId={} targetVersion={} approvals={} reason='{}'",
                    requestId, targetSnapshotVersion, validGuardians.size(), reason);
        } else {
            logger.error("Emergency rollback FAILED (one-shot): requestId={} targetVersion={}",
                    requestId, targetSnapshotVersion);
        }
        return restored;
    }

    /**
     * 设置紧急回滚放行门槛 m。
     *
     * @param threshold 批准门槛（≥1）
     */
    public void setEmergencyThreshold(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("emergency threshold must be >= 1");
        }
        this.emergencyThreshold = threshold;
        logger.info("Emergency rollback threshold set to {}", threshold);
    }

    public int getEmergencyThreshold() {
        return emergencyThreshold;
    }

    /**
     * 查询待执行紧急回滚请求。
     *
     * @param requestId 请求 ID
     * @return 请求信息；不存在返回 null
     */
    public EmergencyRollbackRequest getPendingRequest(String requestId) {
        return pendingRequests.get(requestId);
    }

    /**
     * 返回紧急回滚审计日志（按时间顺序，只读副本）。
     *
     * @return 审计日志列表
     */
    public List<EmergencyRollbackRecord> listAuditLog() {
        return new ArrayList<>(auditLog);
    }

    /**
     * 取消未执行的紧急回滚请求（仅允许发起人或守护人取消）。
     *
     * @param requestId 请求 ID
     * @param canceller 取消人地址
     * @return 取消成功返回 true
     */
    public boolean cancelEmergencyRollback(String requestId, String canceller) {
        if (!guardianService.isGuardian(canceller)) {
            logger.warn("Cancel emergency rollback rejected: {} is not a guardian", canceller);
            return false;
        }
        EmergencyRollbackRequest removed = pendingRequests.remove(requestId);
        if (removed == null) {
            return false;
        }
        logger.warn("Emergency rollback {} cancelled by {}", requestId, canceller);
        return true;
    }

    /**
     * 紧急回滚请求内部实体。
     */
    public static final class EmergencyRollbackRequest {
        /** 请求 ID */
        private final String requestId;
        /** 目标快照版本 */
        private final int targetSnapshotVersion;
        /** 发起人 */
        private final String initiator;
        /** 回滚原因 */
        private final String reason;
        /** 发起时间 */
        private final Instant initiatedAt;
        /** 已批准守护人集合 */
        private final Set<String> approvals = new LinkedHashSet<>();

        EmergencyRollbackRequest(String requestId, int targetSnapshotVersion,
                                 String initiator, String reason, Instant initiatedAt) {
            this.requestId = requestId;
            this.targetSnapshotVersion = targetSnapshotVersion;
            this.initiator = initiator;
            this.reason = reason;
            this.initiatedAt = initiatedAt;
            this.approvals.add(initiator);
        }

        public String getRequestId() {
            return requestId;
        }

        public int getTargetSnapshotVersion() {
            return targetSnapshotVersion;
        }

        public String getInitiator() {
            return initiator;
        }

        public String getReason() {
            return reason;
        }

        public Instant getInitiatedAt() {
            return initiatedAt;
        }

        /**
         * 返回已批准守护人集合（只读）。
         *
         * @return 已批准守护人集合
         */
        public Set<String> getApprovals() {
            return Collections.unmodifiableSet(approvals);
        }
    }
}