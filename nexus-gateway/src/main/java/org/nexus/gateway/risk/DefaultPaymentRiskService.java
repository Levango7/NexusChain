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
 * （profile + 日/月限额 SUM 聚合）。SUM 随订单量增长退化，是延迟主源。
 * 现引入 {@link RiskHotPathCache}：画像 30s、限额聚合 5s 短 TTL 缓存——
 * <b>语义零变化</b>（同一查询、同一 PAID/PAYING 口径，仅近窗复用），
 * 稳态命中路径纯内存。配 Micrometer 计时
 * （{@code nexus_risk_evaluation_duration{operation}}），延迟从「不可见」变「可运营」。
 * 时延换实时：限额校验最多滞后 5s（有界，见缓存类头注）。</p>
 *
 * <p><b>严格化后续项（未做，语义需产品拍板）</b>：把 SUM 换成 O(1) 滚动计数表
 * （{@code risk_limit_accruals}：merchant+period PK，订单落库时原子递增），
 * 可做到逐笔精确、无滞后。前置条件是先收敛 {@code PaymentServiceImpl} 的
 * ~10 处 {@code orderRepository.save} 散点为单一落库咽喉（或改用事件监听），
 * 否则递增钩子挂不全。拍板后单独立项。</p>
 */
@Service
public class DefaultPaymentRiskService implements PaymentRiskService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPaymentRiskService.class);

    /** 商户画像缓存 TTL：配置级数据，变化极少。 */
    static final long PROFILE_TTL_MILLIS = 30_000L;
    /** 限额聚合缓存 TTL：限额闸门允许的有界滞后。 */
    static final long LIMIT_SUM_TTL_MILLIS = 5_000L;

    private final RiskProfileRepository riskProfileRepository;
    private final RiskEngine riskEngine;
    private final org.nexus.gateway.repository.PaymentOrderRepository orderRepository;

    private final RiskHotPathCache<RiskProfile> profileCache = new RiskHotPathCache<>(PROFILE_TTL_MILLIS);
    private final RiskHotPathCache<BigDecimal> dailySumCache = new RiskHotPathCache<>(LIMIT_SUM_TTL_MILLIS);
    private final RiskHotPathCache<BigDecimal> monthlySumCache = new RiskHotPathCache<>(LIMIT_SUM_TTL_MILLIS);

    /** Micrometer 注册表（可空——测试环境降级为无计时）。 */
    private final MeterRegistry meterRegistry;

    @Autowired
    public DefaultPaymentRiskService(RiskProfileRepository riskProfileRepository,
                                     RiskEngine riskEngine,
                                     org.nexus.gateway.repository.PaymentOrderRepository orderRepository,
                                     @Autowired(required = false) MeterRegistry meterRegistry) {
        this.riskProfileRepository = riskProfileRepository;
        this.riskEngine = riskEngine;
        this.orderRepository = orderRepository;
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
                    BigDecimal daySum = dailySumCache.get("daily:" + merchantId,
                            () -> orderRepository.sumMerchantAmountSince(
                                    merchantId, LocalDateTime.now().minusDays(1)));
                    if (daySum.add(amount).compareTo(profile.getDailyLimit()) > 0) {
                        log.warn("Merchant {} daily limit {} exceeded (current={}, requested={})",
                                merchantId, profile.getDailyLimit(), daySum, amount);
                        return RiskDecision.REJECTED;
                    }
                }

                if (profile.getMonthlyLimit() != null) {
                    BigDecimal monthSum = monthlySumCache.get("monthly:" + merchantId,
                            () -> orderRepository.sumMerchantAmountSince(
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
