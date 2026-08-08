package org.nexus.gateway.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.settlement.risk.RiskEngine;
import org.nexus.settlement.risk.RiskTransaction;


import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultPaymentRiskService} 单元测试：覆盖黑名单、单笔/日/月限额、
 * 风控引擎委托与 null/缺失分支。
 */
@ExtendWith(MockitoExtension.class)
class DefaultPaymentRiskServiceTest {

    @Mock private RiskProfileRepository riskProfileRepository;
    @Mock private RiskEngine riskEngine;
    @Mock private PaymentOrderRepository orderRepository;

    private DefaultPaymentRiskService service;

    @BeforeEach
    void setUp() {
        service = new DefaultPaymentRiskService(riskProfileRepository, riskEngine, orderRepository);
    }

    // === evaluatePayment ===

    @Test
    @DisplayName("evaluatePayment: null request 返回 APPROVED")
    void evaluatePayment_nullRequest() {
        assertEquals(RiskDecision.APPROVED, service.evaluatePayment(null));
    }

    @Test
    @DisplayName("evaluatePayment: null merchantId 返回 APPROVED")
    void evaluatePayment_nullMerchantId() {
        PaymentRequest req = new PaymentRequest();
        assertEquals(RiskDecision.APPROVED, service.evaluatePayment(req));
    }

    @Test
    @DisplayName("evaluatePayment: 黑名单 -> FROZEN")
    void evaluatePayment_blacklisted() {
        RiskProfile profile = new RiskProfile();
        profile.setBlacklisted(true);
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.of(profile));

        PaymentRequest req = new PaymentRequest(100L, "0xPayer", BigDecimal.TEN, "NEX");
        assertEquals(RiskDecision.FROZEN, service.evaluatePayment(req));
    }

    @Test
    @DisplayName("evaluatePayment: 超过单笔限额 -> REJECTED")
    void evaluatePayment_perTxLimit() {
        RiskProfile profile = new RiskProfile();
        profile.setPerTxLimit(new BigDecimal("100"));
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.of(profile));

        PaymentRequest req = new PaymentRequest(100L, "0xPayer", new BigDecimal("200"), "NEX");
        assertEquals(RiskDecision.REJECTED, service.evaluatePayment(req));
    }

    @Test
    @DisplayName("evaluatePayment: 超过日限额 -> REJECTED")
    void evaluatePayment_dailyLimit() {
        RiskProfile profile = new RiskProfile();
        profile.setDailyLimit(new BigDecimal("1000"));
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.of(profile));
        when(orderRepository.sumMerchantAmountSince(eq(100L), any()))
                .thenReturn(new BigDecimal("900"));

        PaymentRequest req = new PaymentRequest(100L, "0xPayer", new BigDecimal("200"), "NEX");
        assertEquals(RiskDecision.REJECTED, service.evaluatePayment(req));
    }

    @Test
    @DisplayName("evaluatePayment: 超过月限额 -> REJECTED")
    void evaluatePayment_monthlyLimit() {
        RiskProfile profile = new RiskProfile();
        profile.setMonthlyLimit(new BigDecimal("5000"));
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.of(profile));
        when(orderRepository.sumMerchantAmountSince(eq(100L), any()))
                .thenReturn(new BigDecimal("4900"));

        PaymentRequest req = new PaymentRequest(100L, "0xPayer", new BigDecimal("200"), "NEX");
        assertEquals(RiskDecision.REJECTED, service.evaluatePayment(req));
    }

    @Test
    @DisplayName("evaluatePayment: profile 存在但未超限 -> 委托 RiskEngine")
    void evaluatePayment_withinLimits_delegate() {
        RiskProfile profile = new RiskProfile();
        profile.setPerTxLimit(new BigDecimal("1000"));
        profile.setDailyLimit(new BigDecimal("10000"));
        profile.setMonthlyLimit(new BigDecimal("50000"));
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.of(profile));
        when(orderRepository.sumMerchantAmountSince(eq(100L), any()))
                .thenReturn(new BigDecimal("100"));
        when(riskEngine.evaluate(any(RiskTransaction.class)))
                .thenReturn(org.nexus.settlement.risk.RiskDecision.APPROVED);

        PaymentRequest req = new PaymentRequest(100L, "0xPayer", new BigDecimal("200"), "NEX");
        assertEquals(RiskDecision.APPROVED, service.evaluatePayment(req));
    }

    @Test
    @DisplayName("evaluatePayment: profile 不存在 -> 直接委托 RiskEngine")
    void evaluatePayment_noProfile_delegate() {
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.empty());
        when(riskEngine.evaluate(any(RiskTransaction.class)))
                .thenReturn(org.nexus.settlement.risk.RiskDecision.REJECTED);

        PaymentRequest req = new PaymentRequest(100L, "0xPayer", BigDecimal.TEN, "NEX");
        assertEquals(RiskDecision.REJECTED, service.evaluatePayment(req));
    }

    @Test
    @DisplayName("evaluatePayment: RiskEngine 返回 null -> APPROVED")
    void evaluatePayment_engineNull() {
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.empty());
        when(riskEngine.evaluate(any())).thenReturn(null);

        PaymentRequest req = new PaymentRequest(100L, "0xPayer", BigDecimal.TEN, "NEX");
        assertEquals(RiskDecision.APPROVED, service.evaluatePayment(req));
    }

    @Test
    @DisplayName("evaluatePayment: amount 为 null 跳过限额检查，委托引擎")
    void evaluatePayment_nullAmount() {
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.of(new RiskProfile()));
        when(riskEngine.evaluate(any())).thenReturn(org.nexus.settlement.risk.RiskDecision.APPROVED);

        PaymentRequest req = new PaymentRequest(100L, "0xPayer", null, "NEX");
        assertEquals(RiskDecision.APPROVED, service.evaluatePayment(req));
    }

    // === evaluateRefund ===

    @Test
    @DisplayName("evaluateRefund: null request 返回 APPROVED")
    void evaluateRefund_null() {
        assertEquals(RiskDecision.APPROVED, service.evaluateRefund(null));
    }

    @Test
    @DisplayName("evaluateRefund: 黑名单 -> FROZEN")
    void evaluateRefund_blacklisted() {
        RiskProfile profile = new RiskProfile();
        profile.setBlacklisted(true);
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.of(profile));

        RefundRequest req = new RefundRequest();
        req.setMerchantId(100L);
        assertEquals(RiskDecision.FROZEN, service.evaluateRefund(req));
    }

    @Test
    @DisplayName("evaluateRefund: 正常 -> 委托 RiskEngine")
    void evaluateRefund_delegate() {
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.empty());
        when(riskEngine.evaluate(any())).thenReturn(org.nexus.settlement.risk.RiskDecision.PENDING_REVIEW);

        RefundRequest req = new RefundRequest();
        req.setMerchantId(100L);
        assertEquals(RiskDecision.PENDING_REVIEW, service.evaluateRefund(req));
    }

    // === getRiskProfile ===

    @Test
    @DisplayName("getRiskProfile: null 返回 null")
    void getRiskProfile_null() {
        assertNull(service.getRiskProfile(null));
    }

    @Test
    @DisplayName("getRiskProfile: 存在返回 profile，不存在返回 null")
    void getRiskProfile_present() {
        RiskProfile profile = new RiskProfile();
        when(riskProfileRepository.findByMerchantId(100L)).thenReturn(Optional.of(profile));
        assertSame(profile, service.getRiskProfile(100L));

        when(riskProfileRepository.findByMerchantId(200L)).thenReturn(Optional.empty());
        assertNull(service.getRiskProfile(200L));
    }
}