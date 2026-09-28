package org.nexus.gateway.orchestration.routing.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 跨渠道补偿路由服务（Wave 16 模块三）。
 *
 * <p>当补偿（退款/调账）需要在非原支付 connector 上执行时，按
 * {@link FallbackRouteService} 的降级链选择执行渠道，并把决策与结果持久化到
 * {@code compensation_routing_records}。</p>
 *
 * <p>状态机：{@link CompensationRoutingRecord.RouteResult#PENDING}（route 落库）
 * → SUCCESS / FAILED / TIMEOUT（complete 回填）。{@code compensation_id}
 * 唯一约束 + {@code route()} 的已有记录短路保证幂等。</p>
 *
 * <p>与 Wave 15 {@code AutoCompensationService} 的关系：本服务只负责「选渠道 + 记录」，
 * 不执行退款/调账本身；Wave 15 补偿执行方拿到 {@code compensationConnector} 后
 * 自行调用渠道退款接口并回填结果。compensationId 建议传 Wave 15 补偿记录标识以便关联。</p>
 */
@Service
public class CompensationRoutingService {

    private static final Logger log = LoggerFactory.getLogger(CompensationRoutingService.class);

    private final CompensationRoutingRecordRepository recordRepository;
    private final FallbackRouteService fallbackRouteService;

    public CompensationRoutingService(CompensationRoutingRecordRepository recordRepository,
                                      FallbackRouteService fallbackRouteService) {
        this.recordRepository = recordRepository;
        this.fallbackRouteService = fallbackRouteService;
    }

    /**
     * 为补偿决策执行渠道。
     *
     * <p>幂等：同 compensationId 重复调用直接返回已有记录（不覆盖）。
     * 无可用跨渠道备选时落库 FAILED（errorMessage 说明原因），调用方可回退原渠道执行。</p>
     *
     * @param merchantId        商户 ID
     * @param paymentId         原支付 ID（orchestrated_payments.id）
     * @param compensationId    补偿标识（可空，空则生成 {@code comp_{UUID}}）
     * @param originalConnector 原支付 connector
     * @param amount            金额（分）
     * @param currency          币种
     * @return 已持久化的路由记录（result=PENDING / FAILED）
     */
    @Transactional
    public CompensationRoutingRecord route(Long merchantId, String paymentId, String compensationId,
                                           String originalConnector, BigDecimal amount, String currency) {
        String effectiveCompensationId = compensationId == null || compensationId.isBlank()
                ? "comp_" + UUID.randomUUID()
                : compensationId;

        Optional<CompensationRoutingRecord> existing =
                recordRepository.findByCompensationId(effectiveCompensationId);
        if (existing.isPresent()) {
            log.debug("Compensation routing idempotent hit: compensationId={}", effectiveCompensationId);
            return existing.get();
        }

        CompensationRoutingRecord record = new CompensationRoutingRecord();
        record.setCompensationId(effectiveCompensationId);
        record.setPaymentId(paymentId);
        record.setMerchantId(merchantId);
        record.setOriginalConnector(originalConnector);
        record.setAttemptNo((int) Math.min(Integer.MAX_VALUE,
                recordRepository.findByPaymentIdOrderByAttemptNoAsc(paymentId).size() + 1L));

        List<String> chain = fallbackRouteService.resolveFallbackChain(
                merchantId, originalConnector, amount, currency);
        String chosen = chain.isEmpty() ? null : chain.get(0);
        if (chosen == null) {
            record.setCompensationConnector(null);
            record.setResult(CompensationRoutingRecord.RouteResult.FAILED.name());
            record.setErrorMessage("no cross-channel fallback route for primary=" + originalConnector);
        } else {
            record.setCompensationConnector(chosen);
            record.setResult(CompensationRoutingRecord.RouteResult.PENDING.name());
        }
        CompensationRoutingRecord saved = recordRepository.save(record);
        log.info("Compensation routed: compensationId={}, paymentId={}, {} -> {}, result={}",
                effectiveCompensationId, paymentId, originalConnector, chosen, saved.getResult());
        return saved;
    }

    /**
     * 回填执行结果（仅 PENDING 记录可回填；重复回填抛 IllegalArgumentException）。
     */
    @Transactional
    public CompensationRoutingRecord complete(String compensationId,
                                              CompensationRoutingRecord.RouteResult result,
                                              Long latencyMs, String errorMessage) {
        CompensationRoutingRecord record = recordRepository.findByCompensationId(compensationId)
                .orElseThrow(() -> new IllegalArgumentException("补偿路由记录不存在: " + compensationId));
        if (record.getResult() == null || CompensationRoutingRecord.RouteResult.PENDING.name().equals(record.getResult())) {
            record.setResult(result.name());
            record.setLatencyMs(latencyMs);
            record.setErrorMessage(errorMessage);
        } else {
            throw new IllegalArgumentException("补偿路由记录已完成，不可重复回填: " + compensationId
                    + " (result=" + record.getResult() + ")");
        }
        CompensationRoutingRecord saved = recordRepository.save(record);
        log.info("Compensation routing completed: compensationId={}, result={}, latencyMs={}",
                compensationId, saved.getResult(), latencyMs);
        return saved;
    }

    /** 按补偿标识查询。 */
    public Optional<CompensationRoutingRecord> getByCompensationId(String compensationId) {
        return recordRepository.findByCompensationId(compensationId);
    }

    /** 按支付查询（attempt_no 升序）。 */
    public List<CompensationRoutingRecord> listByPayment(String paymentId) {
        return recordRepository.findByPaymentIdOrderByAttemptNoAsc(paymentId);
    }

    /** 按商户查询（创建时间降序）。 */
    public List<CompensationRoutingRecord> listByMerchant(Long merchantId) {
        return recordRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }
}
