package org.nexus.gateway.repository;

import org.nexus.gateway.model.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
