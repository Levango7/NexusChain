package org.nexus.gateway.repository;

import org.nexus.gateway.model.Refund;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByOrderId(Long orderId);

    /**
     * S3 修复（2026-08-31 交付前审计）：订单已发起（PENDING/EXECUTING/COMPLETED）
     * 退款总额——直通退款路径的累计额度校验依据。
     *
     * <p>审批路径（DefaultRefundApprovalService:96-98）早有同款校验；直通路径
     * （PaymentServiceImpl.refund）此前只校验单次金额 ≤ 订单金额——两笔 90%
     * 的部分退款即可退超 100%。与审批口径一致：FAILED 的退款不占额度。 </p>
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.orderId = :orderId "
            + "AND r.status IN ("
            + "org.nexus.gateway.model.Refund$RefundStatus.PENDING, "
            + "org.nexus.gateway.model.Refund$RefundStatus.PROCESSING, "
            + "org.nexus.gateway.model.Refund$RefundStatus.COMPLETED, "
            + "org.nexus.gateway.model.Refund$RefundStatus.RECONCILIATION_NEEDED)")
    BigDecimal sumActiveRefundsByOrderId(@Param("orderId") Long orderId);

    /**
     * P2-F3：查询指定状态且创建时间早于 cutoff 的退款记录。
     *
     * <p>供 {@link org.nexus.gateway.execution.CompensationService} 扫描 PENDING 超时记录，
     * 命中索引 {@code idx_status_created (status, created_at)}。</p>
     *
     * @param status 退款状态
     * @param cutoff 时间阈值（创建时间早于此值的记录）
     * @return 符合条件的退款列表
     */
    List<Refund> findByStatusAndCreatedAtBefore(Refund.RefundStatus status, LocalDateTime cutoff);

    /**
     * 低6 改进：查询指定状态且创建时间早于 cutoff 的退款记录（带分页限制）。
     *
     * <p>重载方法，增加 {@link Pageable} 参数限制单次查询返回的记录数，
     * 避免 PENDING 积压过多时一次处理大批量记录占用数据库连接池、
     * 阻塞正常交易流程。{@link org.nexus.gateway.execution.CompensationService#handlePendingRefunds}
     * 通过 {@code nexus.compensation.batch-size}（默认 100）控制单次批量大小。</p>
     *
     * @param status  退款状态
     * @param cutoff  时间阈值（创建时间早于此值的记录）
     * @param pageable 分页参数（仅取 pageSize，page number 固定为 0）
     * @return 符合条件的退款列表（最多 pageSize 条）
     */
    List<Refund> findByStatusAndCreatedAtBefore(Refund.RefundStatus status,
                                                LocalDateTime cutoff,
                                                Pageable pageable);

    /**
     * P2-F3：查询指定状态的退款记录（供对账使用）。
     *
     * @param status 退款状态
     * @return 符合条件的退款列表
     */
    List<Refund> findByStatus(Refund.RefundStatus status);
}
