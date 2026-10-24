package org.nexus.gateway.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for refund requests flowing through the approval workflow.
 */
@Repository
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    Optional<RefundRequest> findByRefundNo(String refundNo);

    List<RefundRequest> findByOrderId(Long orderId);

    List<RefundRequest> findByMerchantIdAndStatus(Long merchantId, RefundRequest.RefundStatus status);

    /**
     * 查询同一订单的 PENDING + APPROVED + EXECUTED 退款金额总和。
     *
     * <p>用于超额退款校验（P0-1 修复）：新退款金额 + 已有退款总和 不得超过订单原额。
     * v2.27.0 安全加固：将 EXECUTED 状态纳入统计，防止已执行的退款不计入额度，
     * 导致同一订单被无限次重复放款。</p>
     *
     * @param orderId 订单 ID
     * @return PENDING、APPROVED 和 EXECUTED 状态的退款金额总和，无记录时返回 0
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RefundRequest r "
            + "WHERE r.orderId = :orderId "
            + "AND r.status IN ("
            + "org.nexus.gateway.refund.RefundRequest$RefundStatus.PENDING, "
            + "org.nexus.gateway.refund.RefundRequest$RefundStatus.APPROVED, "
            + "org.nexus.gateway.refund.RefundRequest$RefundStatus.EXECUTING, "
            + "org.nexus.gateway.refund.RefundRequest$RefundStatus.AWAITING_APPROVAL, "
            + "org.nexus.gateway.refund.RefundRequest$RefundStatus.EXECUTED)")
    BigDecimal sumPendingRefundsByOrderId(@Param("orderId") Long orderId);

    /**
     * CAS 认领退款执行权（P0 修复：防止并发重复执行导致多次不可逆链上打款）。
     *
     * <p>仅当退款单仍处于 APPROVED 状态时原子地置为 EXECUTING；返回受影响行数：
     * 0 表示已被其他线程认领或状态已流转（非 APPROVED），调用方必须放弃执行。
     * UPDATE 在数据库层串行化，两个并发 executeRefund 只有一个能认领成功。</p>
     *
     * @param id  退款单 ID
     * @param now 认领时间
     * @return 受影响行数：1=认领成功，0=认领失败
     */
    @Modifying
    @Query("UPDATE RefundRequest r "
            + "SET r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.EXECUTING, "
            + "r.updatedAt = :now "
            + "WHERE r.id = :id "
            + "AND r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.APPROVED")
    int claimForExecution(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 结果落库：EXECUTING → EXECUTED（批次⑤ P0 加固，2026-10-18）。
     *
     * <p>执行三段式的第三段在<b>独立事务</b>中调用：链上出款发生在事务之外，
     * 本 UPDATE 只负责把"链上已出款"这一既成事实落库。WHERE 锁 status=EXECUTING，
     * 防止覆盖已被其他路径流转的单据。</p>
     *
     * @param id     退款单 ID
     * @param txHash 链上交易哈希
     * @param now    落库时间
     * @return 受影响行数：1=落库成功，0=单据不在 EXECUTING（调用方需按异常处理）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefundRequest r "
            + "SET r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.EXECUTED, "
            + "r.chainTxHash = :txHash, r.executedAt = :now, r.updatedAt = :now "
            + "WHERE r.id = :id "
            + "AND r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.EXECUTING")
    int markExecuted(@Param("id") Long id, @Param("txHash") String txHash, @Param("now") LocalDateTime now);

    /**
     * 结果落库：EXECUTING → FAILED（链上出款确定未发生时调用，如通道返回失败/参数校验失败）。
     *
     * <p>同样只允许从 EXECUTING 流转，独立事务提交。</p>
     *
     * @param id     退款单 ID
     * @param reason 失败原因（写入 rejection_reason）
     * @param now    落库时间
     * @return 受影响行数：1=落库成功，0=单据不在 EXECUTING
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefundRequest r "
            + "SET r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.FAILED, "
            + "r.rejectionReason = :reason, r.updatedAt = :now "
            + "WHERE r.id = :id "
            + "AND r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.EXECUTING")
    int markFailed(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /**
     * CAS 认领"审批中"的退款执行权（AWAITING_APPROVAL → EXECUTING）。
     *
     * <p>与 {@link #claimForExecution} 同款原子 UPDATE：审批通过后由
     * {@code RefundApprovalExecutionReconciler} 重新认领并执行链上退款。
     * 两个并发推进者只有一个能认领成功。</p>
     *
     * @param id  退款单 ID
     * @param now 认领时间
     * @return 受影响行数：1=认领成功，0=认领失败（状态已变）
     */
    @Modifying
    @Query("UPDATE RefundRequest r "
            + "SET r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.EXECUTING, "
            + "r.updatedAt = :now "
            + "WHERE r.id = :id "
            + "AND r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.AWAITING_APPROVAL")
    int claimAwaitingForExecution(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 结果落库：EXECUTING → AWAITING_APPROVAL（大额签名转人工审批，尚未广播）。
     *
     * <p>只允许从 EXECUTING 流转：认领已独立提交，状态回到"等待审批"而非 FAILED，
     * 审批通过后由 reconciler 重新认领。审批等待期间该笔仍计入退款额度。</p>
     *
     * <p>{@code rejection_reason} 列在此复用为排障备注（存 approvalId），
     * 避免为执行腿 ALTER 既有业务表；该列只在 FAILED 时对用户展示为拒绝原因。</p>
     *
     * @param id     退款单 ID
     * @param reason 审批信息（如 approvalId）
     * @param now    落库时间
     * @return 受影响行数：1=落库成功，0=单据不在 EXECUTING
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefundRequest r "
            + "SET r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.AWAITING_APPROVAL, "
            + "r.rejectionReason = :reason, r.updatedAt = :now "
            + "WHERE r.id = :id "
            + "AND r.status = org.nexus.gateway.refund.RefundRequest$RefundStatus.EXECUTING")
    int markAwaitingApproval(@Param("id") Long id, @Param("reason") String reason,
                             @Param("now") LocalDateTime now);

    /**
     * 取一批等待审批的退款单（按最久未更新优先），供审批执行推进器使用。
     *
     * @param status 目标状态（AWAITING_APPROVAL）
     * @param cutoff 时间阈值（updated_at 早于该值，避免刚写入就被重复处理）
     * @return 最多 50 条
     */
    List<RefundRequest> findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            RefundRequest.RefundStatus status, LocalDateTime cutoff);

    /**
     * 扫描超时停留在指定状态的退款单（{@link RefundExecutionReconciler} 使用）。
     *
     * @param status 目标状态（当前仅使用 EXECUTING）
     * @param cutoff 时间阈值（updated_at 早于该值视为超时）
     * @return 命中的退款单列表
     */
    List<RefundRequest> findByStatusAndUpdatedAtBefore(RefundRequest.RefundStatus status, LocalDateTime cutoff);
}
