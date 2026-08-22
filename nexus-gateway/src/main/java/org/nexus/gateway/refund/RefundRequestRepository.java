package org.nexus.gateway.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
            + "org.nexus.gateway.refund.RefundRequest$RefundStatus.EXECUTED)")
    BigDecimal sumPendingRefundsByOrderId(@Param("orderId") Long orderId);
}
