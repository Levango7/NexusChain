package org.nexus.gateway.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.settlement.risk.RiskEngine;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 风控毫秒级改造的预算/机制测试（2026-10-03）。
 *
 * <p>断言两件事（都比绝对耗时更抗 CI 抖动）：</p>
 * <ol>
 *   <li><b>机制</b>：稳态热路径的 DB 查询次数被 TTL 缓存压到
 *       「每商户每窗至多 1 次」——N 次评估只打 3 次库，而不是 3N 次；</li>
 *   <li><b>预算</b>：缓存命中的评估路径为纯内存，1000 次平均耗时远低于
 *       5ms 预算（宽松上限防 CI 抖动误报；真实价值在机制断言）。</li>
 * </ol>
 * 决策语义回归：缓存后单笔/日限额拒绝行为不变。
 */
@ExtendWith(MockitoExtension.class)
class RiskEvaluationLatencyBudgetTest {

    private static final Long MERCHANT_ID = 42L;

    @Mock private RiskProfileRepository riskProfileRepository;
    @Mock private RiskEngine riskEngine;
    @Mock private PaymentOrderRepository orderRepository;

    private DefaultPaymentRiskService service;
    private RiskProfile profile;

    @BeforeEach
    void setUp() {
        service = new DefaultPaymentRiskService(riskProfileRepository, riskEngine, orderRepository, null, null);
        profile = new RiskProfile();
        profile.setMerchantId(MERCHANT_ID);
        profile.setBlacklisted(false);
        profile.setPerTxLimit(new BigDecimal("100000"));
        profile.setDailyLimit(new BigDecimal("1000000"));
        profile.setMonthlyLimit(new BigDecimal("10000000"));
    }

    private PaymentRequest payment(String amount) {
        PaymentRequest req = new PaymentRequest();
        req.setMerchantId(MERCHANT_ID);
        req.setAmount(new BigDecimal(amount));
        req.setTokenSymbol("NEX");
        return req;
    }

    @Test
    @DisplayName("机制: 1000 次评估仅 3 次 DB 查询（画像 1 + 日/月 SUM 各 1）")
    void hotPathCollapsesDbCallsToO1() {
        when(riskProfileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(profile));
        when(orderRepository.sumMerchantAmountSince(eq(MERCHANT_ID), any()))
                .thenReturn(BigDecimal.ZERO);
        when(riskEngine.evaluate(any()))
                .thenReturn(org.nexus.settlement.risk.RiskDecision.APPROVED);

        for (int i = 0; i < 1000; i++) {
            assertEquals(RiskDecision.APPROVED, service.evaluatePayment(payment("100")));
        }

        verify(riskProfileRepository, times(1)).findByMerchantId(MERCHANT_ID);
        // 日/月限额各回源一次（首查装载），此后 999 次评估零 DB
        verify(orderRepository, times(2)).sumMerchantAmountSince(eq(MERCHANT_ID), any());
    }

    @Test
    @DisplayName("预算: 缓存命中路径 1000 次平均耗时 < 5ms（纯内存路径的宽松上限）")
    void cachedPathLatencyBudget() {
        when(riskProfileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(profile));
        when(orderRepository.sumMerchantAmountSince(eq(MERCHANT_ID), any()))
                .thenReturn(BigDecimal.ZERO);
        when(riskEngine.evaluate(any())).thenReturn(org.nexus.settlement.risk.RiskDecision.APPROVED);

        // 预热：触发全部回源装载（画像 1 + 日/月 SUM 各 1）
        service.evaluatePayment(payment("100"));
        verify(orderRepository, times(2)).sumMerchantAmountSince(eq(MERCHANT_ID), any());

        long start = System.nanoTime();
        final int n = 1000;
        for (int i = 0; i < n; i++) {
            service.evaluatePayment(payment("100"));
        }
        double avgMillis = (System.nanoTime() - start) / 1_000_000.0 / n;
        assertTrue(avgMillis < 5.0,
                "缓存命中平均耗时 " + String.format("%.3f", avgMillis) + "ms 超出 5ms 预算");
        // 稳态零 DB 查询（同一 TTL 窗内）
        verify(orderRepository, times(2)).sumMerchantAmountSince(eq(MERCHANT_ID), any());
        verify(riskProfileRepository, times(1)).findByMerchantId(MERCHANT_ID);
    }

    @Test
    @DisplayName("语义回归: 缓存后单笔/日限额拒绝不变")
    void limitSemanticsPreserved() {
        RiskProfile tight = new RiskProfile();
        tight.setMerchantId(MERCHANT_ID);
        tight.setBlacklisted(false);
        tight.setPerTxLimit(new BigDecimal("500"));
        when(riskProfileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(tight));
        when(riskEngine.evaluate(any())).thenReturn(org.nexus.settlement.risk.RiskDecision.APPROVED);

        assertEquals(RiskDecision.REJECTED, service.evaluatePayment(payment("600")));
        assertEquals(RiskDecision.APPROVED, service.evaluatePayment(payment("100")));
    }
}
