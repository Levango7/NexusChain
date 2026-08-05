package org.nexus.gateway.repository;

import org.nexus.gateway.model.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByOrderNo(String orderNo);

    Optional<PaymentOrder> findByCheckoutToken(String checkoutToken);

    List<PaymentOrder> findByMerchantIdAndStatus(Long merchantId, PaymentOrder.OrderStatus status);

    List<PaymentOrder> findByMerchantId(Long merchantId);

    List<PaymentOrder> findByStatusAndExpiresAtBefore(PaymentOrder.OrderStatus status, LocalDateTime cutoff);

    /**
     * Sum order amounts for a merchant paid within the given window.
     * Used by the risk service to enforce daily/monthly merchant limits.
     * Includes PAID and PAYING orders so in-flight payments count against the limit.
     */
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM PaymentOrder o "
            + "WHERE o.merchantId = :merchantId "
            + "AND o.status IN (org.nexus.gateway.model.PaymentOrder$OrderStatus.PAID, "
            + "org.nexus.gateway.model.PaymentOrder$OrderStatus.PAYING) "
            + "AND o.createdAt >= :since")
    BigDecimal sumMerchantAmountSince(@Param("merchantId") Long merchantId,
                                      @Param("since") LocalDateTime since);

    /**
     * Find all PAID orders for a merchant within a time window.
     * Used by the settlement service to build settlement batches.
     */
    List<PaymentOrder> findByMerchantIdAndStatusAndPaidAtBetween(
            Long merchantId, PaymentOrder.OrderStatus status,
            LocalDateTime windowStart, LocalDateTime windowEnd);
}
