package org.nexus.gateway.risk;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.nexus.settlement.risk.RiskEngine;
import org.nexus.settlement.risk.RiskTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Payment risk service that gates payments and refunds.
 *
 * <p>Two-layer evaluation:</p>
 * <ol>
 *   <li><b>Local merchant profile</b> — blacklist short-circuit (FROZEN) and
 *       per-transaction / daily / monthly limit enforcement from the gateway's
 *       {@link RiskProfile} entity (gateway owns merchant-level limits).</li>
 *   <li><b>Delegated rule engine</b> — amount threshold, velocity and blacklist
 *       address rules evaluated by the {@code nexus-settlement}
 *       {@link RiskEngine} (shared risk infrastructure).</li>
 * </ol>
 *
 * <p><b>毫秒级改造（2026-10-03）</b>：评估热路径原为每支付 3 次 DB 往返
 * （profile + 日/月限额 SUM 聚合）。两步演进：①画像 30s / 限额 5s 短 TTL
 * 缓存（{@link RiskHotPathCache}）把 DB 次数从 3N 压到每商户每窗 3 次；
 * ②日/月限额进一步改用 {@link RiskLimitAccrualService} 自然日/月计数器
 * （O(1) 索引读、零查询滞后；口径变更见下段）。配 Micrometer 计时
 * （{@code nexus_risk_evaluation_duration{operation}}），延迟从「不可见」变「可运营」。
 * 未注入计数器时（旧装配/部分测试）自动退化滚动 SUM（向后兼容）。</p>
 *
 * <p><b>严格计数器（2026-10-03 A3，口径拍板后实施）</b>：日/月限额从
 * 「滚动 24h/30 天 SUM 聚合」（O(订单量)、5s TTL 缓存滞后窗）升级为
 * 「自然日/自然月计数器」（{@link RiskLimitAccrualService}，
 * O(1) 索引读、零查询滞后）——自然日为行业惯例口径，语义变更已在
 * CHANGELOG 明示。TTL 缓存保留（把 O(1) 读也摊薄到每窗一次）；
 * 成员集（PAID+PAYING）与原 SUM 严格一致。画像缓存不变。</p>
 */
@Service
public class DefaultPaymentRiskService implements PaymentRiskService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPaymentRiskService.class);

    /** 商户画像缓存 TTL：配置级数据，变化极少。 */
    static final long PROFILE_TTL_MILLIS = 30_000L;
    /** 限额计数缓存 TTL：摊薄 O(1) 读；计数器本身零滞后，缓存窗只影响读频。 */
    static final long LIMIT_SUM_TTL_MILLIS = 5_000L;

    private final RiskProfileRepository riskProfileRepository;
    private final RiskEngine riskEngine;
    private final org.nexus.gateway.repository.PaymentOrderRepository orderRepository;
    /** 限额计数器（自然日/月，O(1)）；nullable=未注入时退化滚动 SUM（兼容旧装配/测试）。 */
    private final RiskLimitAccrualService accrualService;

    private final RiskHotPathCache<RiskProfile> profileCache = new RiskHotPathCache<>(PROFILE_TTL_MILLIS);
    private final RiskHotPathCache<BigDecimal> dailySumCache = new RiskHotPathCache<>(LIMIT_SUM_TTL_MILLIS);
    private final RiskHotPathCache<BigDecimal> monthlySumCache = new RiskHotPathCache<>(LIMIT_SUM_TTL_MILLIS);

    /** Micrometer 注册表（可空——测试环境降级为无计时）。 */
    private final MeterRegistry meterRegistry;

    @Autowired
    public DefaultPaymentRiskService(RiskProfileRepository riskProfileRepository,
                                     RiskEngine riskEngine,
                                     org.nexus.gateway.repository.PaymentOrderRepository orderRepository,
                                     @Autowired(required = false) RiskLimitAccrualService accrualService,
                                     @Autowired(required = false) MeterRegistry meterRegistry) {
        this.riskProfileRepository = riskProfileRepository;
        this.riskEngine = riskEngine;
        this.orderRepository = orderRepository;
        this.accrualService = accrualService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public RiskDecision evaluatePayment(PaymentRequest request) {
        long startNanos = System.nanoTime();
        try {
            RiskDecision decision = doEvaluatePayment(request);
            recordLatency("payment", startNanos);
            return decision;
        } catch (RuntimeException e) {
            recordLatency("payment_error", startNanos);
            throw e;
        }
    }

    private RiskDecision doEvaluatePayment(PaymentRequest request) {
        if (request == null || request.getMerchantId() == null) {
            log.warn("Risk evaluation skipped: missing request or merchantId");
            return RiskDecision.APPROVED;
        }

        Long merchantId = request.getMerchantId();
        RiskProfile profile = profileCache.get("profile:" + merchantId,
                () -> riskProfileRepository.findByMerchantId(merchantId).orElse(null));

        // Layer 1: merchant profile guards
        if (profile != null) {
            if (Boolean.TRUE.equals(profile.getBlacklisted())) {
                log.warn("Merchant {} is blacklisted, freezing payment", merchantId);
                return RiskDecision.FROZEN;
            }

            BigDecimal amount = request.getAmount();
            if (amount != null) {
                if (profile.getPerTxLimit() != null && amount.compareTo(profile.getPerTxLimit()) > 0) {
                    log.warn("Merchant {} payment {} exceeds per-tx limit {}",
                            merchantId, amount, profile.getPerTxLimit());
                    return RiskDecision.REJECTED;
                }

                if (profile.getDailyLimit() != null) {
                    BigDecimal daySum = dailySumCache.get("daily:" + merchantId, () ->
                            accrualService != null
                                    ? accrualService.dailyAccrued(merchantId)
                                    : orderRepository.sumMerchantAmountSince(
                                            merchantId, LocalDateTime.now().minusDays(1)));
                    if (daySum.add(amount).compareTo(profile.getDailyLimit()) > 0) {
                        log.warn("Merchant {} daily limit {} exceeded (current={}, requested={})",
                                merchantId, profile.getDailyLimit(), daySum, amount);
                        return RiskDecision.REJECTED;
                    }
                }

                if (profile.getMonthlyLimit() != null) {
                    BigDecimal monthSum = monthlySumCache.get("monthly:" + merchantId, () ->
                            accrualService != null
                                    ? accrualService.monthlyAccrued(merchantId)
                                    : orderRepository.sumMerchantAmountSince(
                                            merchantId, LocalDateTime.now().minusDays(30)));
                    if (monthSum.add(amount).compareTo(profile.getMonthlyLimit()) > 0) {
                        log.warn("Merchant {} monthly limit {} exceeded (current={}, requested={})",
                                merchantId, profile.getMonthlyLimit(), monthSum, amount);
                        return RiskDecision.REJECTED;
                    }
                }
            }
        }

        // Layer 2: delegate to nexus-settlement RiskEngine rule chain
        RiskTransaction riskTx = new RiskTransaction();
        riskTx.setType("PAYMENT");
        riskTx.setMerchantId(merchantId);
        riskTx.setPayerAddress(request.getPayerAddress());
        riskTx.setAmount(request.getAmount());
        riskTx.setCurrency(request.getTokenSymbol());
        riskTx.setIdempotencyKey(request.getIdempotencyKey());

        org.nexus.settlement.risk.RiskDecision engineDecision = riskEngine.evaluate(riskTx);
        return mapDecision(engineDecision);
    }

    @Override
    public RiskDecision evaluateRefund(RefundRequest request) {
        long startNanos = System.nanoTime();
        try {
            RiskDecision decision = doEvaluateRefund(request);
            recordLatency("refund", startNanos);
            return decision;
        } catch (RuntimeException e) {
            recordLatency("refund_error", startNanos);
            throw e;
        }
    }

    private RiskDecision doEvaluateRefund(RefundRequest request) {
        if (request == null || request.getMerchantId() == null) {
            return RiskDecision.APPROVED;
        }

        Long merchantId = request.getMerchantId();
        RiskProfile profile = profileCache.get("profile:" + merchantId,
                () -> riskProfileRepository.findByMerchantId(merchantId).orElse(null));
        if (profile != null && Boolean.TRUE.equals(profile.getBlacklisted())) {
            log.warn("Merchant {} is blacklisted, freezing refund for order {}",
                    merchantId, request.getOrderId());
            return RiskDecision.FROZEN;
        }

        RiskTransaction riskTx = new RiskTransaction();
        riskTx.setType("REFUND");
        riskTx.setMerchantId(merchantId);
        riskTx.setPayeeAddress(request.getReceiverAddress());
        riskTx.setAmount(request.getAmount());

        org.nexus.settlement.risk.RiskDecision engineDecision = riskEngine.evaluate(riskTx);
        return mapDecision(engineDecision);
    }

    private void recordLatency(String operation, long startNanos) {
        if (meterRegistry == null) return;
        try {
            Timer.builder("nexus_risk_evaluation_duration")
                    .description("风控评估耗时（含画像/限额查询与规则链）")
                    .tag("operation", operation)
                    .register(meterRegistry)
                    .record(Duration.ofNanos(System.nanoTime() - startNanos));
        } catch (RuntimeException e) {
            log.debug("Risk latency recording failed: {}", e.getMessage());
        }
    }

    @Override
    public RiskProfile getRiskProfile(Long merchantId) {
        if (Objects.isNull(merchantId)) {
            return null;
        }
        return riskProfileRepository.findByMerchantId(merchantId).orElse(null);
    }

    private RiskDecision mapDecision(org.nexus.settlement.risk.RiskDecision decision) {
        if (decision == null) {
            return RiskDecision.APPROVED;
        }
        return switch (decision) {
            case APPROVED -> RiskDecision.APPROVED;
            case REJECTED -> RiskDecision.REJECTED;
            case PENDING_REVIEW -> RiskDecision.PENDING_REVIEW;
            case FROZEN -> RiskDecision.FROZEN;
        };
    }
}
