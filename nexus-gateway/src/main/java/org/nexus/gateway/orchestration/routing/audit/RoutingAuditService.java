package org.nexus.gateway.orchestration.routing.audit;

import jakarta.annotation.PreDestroy;
import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.routing.RoutingJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 路由决策审计服务（Wave 16 模块五）。
 *
 * <p>在 {@code RoutingEngine} 决策后落审计记录。{@code nexus.routing.audit.async-write=true}
 * （默认）时经由单线程 daemon executor 异步写入——主支付路径不因审计阻塞或失败而中断；
 * 同步模式用于测试与严格顺序场景。写入失败仅记 warn，不影响路由结果。</p>
 *
 * <p>保留期清理（每小时）删除超过 {@code nexus.routing.audit.retention-days}（默认 90）
 * 的记录。</p>
 */
@Service
public class RoutingAuditService {

    private static final Logger log = LoggerFactory.getLogger(RoutingAuditService.class);

    private final RoutingDecisionRecordRepository repository;
    private final RoutingWave16Properties properties;
    private final ExecutorService asyncWriter;

    public RoutingAuditService(RoutingDecisionRecordRepository repository,
                               RoutingWave16Properties properties) {
        this.repository = repository;
        this.properties = properties;
        this.asyncWriter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "routing-audit-writer");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdownWriter() {
        asyncWriter.shutdown();
        try {
            if (!asyncWriter.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncWriter.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asyncWriter.shutdownNow();
        }
    }

    // ==================== 写入 ====================

    /**
     * 记录一次路由决策。
     *
     * @param paymentId   支付 ID（审计记录必填；引擎内部非支付链路调用可传占位符）
     * @param merchantId  商户 ID（可空）
     * @param amount      金额（分）
     * @param currency    币种
     * @param ruleMatched 命中规则 id（可空）
     * @param strategy    策略名（PRIORITY/WEIGHT/COST/EXPLICIT/MULTI_OBJECTIVE/AI）
     * @param candidates  候选 connector id 列表
     * @param scores      各候选评分（可空；多目标策略时提供）
     * @param decision    最终决策 connector id 列表
     * @param experimentId A/B 实验 id（可空）
     * @param abTestGroup A/B 实验组（可空）
     * @return decisionId（{@code rd_{UUID}}；异步模式下记录可能尚未落库）
     */
    public String recordDecision(String paymentId, Long merchantId, BigDecimal amount, String currency,
                                 String ruleMatched, String strategy, List<String> candidates,
                                 Map<String, Double> scores, List<String> decision,
                                 String experimentId, String abTestGroup) {
        RoutingDecisionRecord record = new RoutingDecisionRecord();
        String decisionId = "rd_" + UUID.randomUUID();
        record.setDecisionId(decisionId);
        record.setPaymentId(paymentId == null ? "unknown" : paymentId);
        record.setMerchantId(merchantId);
        Map<String, String> input = new LinkedHashMap<>();
        if (amount != null) input.put("amount", amount.toPlainString());
        if (currency != null) input.put("currency", currency);
        record.setInputJson(RoutingJsonCodec.toJson(input));
        record.setRuleMatched(ruleMatched);
        record.setStrategy(strategy);
        record.setCandidatesJson(RoutingJsonCodec.toJson(candidates));
        record.setScoresJson(scores == null ? null
                : RoutingJsonCodec.writeValueAsString(new LinkedHashMap<>(scores)));
        record.setDecisionJson(RoutingJsonCodec.toJson(decision));
        record.setExperimentId(experimentId);
        record.setAbTestGroup(abTestGroup);

        if (properties.getAudit().isEnabled()) {
            if (properties.getAudit().isAsyncWrite()) {
                asyncWriter.execute(() -> safeSave(record, decisionId));
            } else {
                safeSave(record, decisionId);
            }
        }
        return decisionId;
    }

    /**
     * 回填决策结果（支付完成后调用；同步执行——单条按主键更新，不构成路径风险）。
     *
     * @param success 支付是否在选中的 connector 上成功
     */
    @Transactional
    public void recordOutcome(String decisionId, boolean success) {
        if (decisionId == null || !properties.getAudit().isEnabled()) return;
        try {
            repository.findById(decisionId).ifPresent(record -> {
                record.setOutcome(success ? "SUCCESS" : "FAILURE");
                repository.save(record);
            });
        } catch (RuntimeException e) {
            log.warn("Audit outcome update failed for {}: {}", decisionId, e.getMessage());
        }
    }

    private void safeSave(RoutingDecisionRecord record, String decisionId) {
        try {
            repository.save(record);
        } catch (RuntimeException e) {
            log.warn("Audit record save failed for {}: {}", decisionId, e.getMessage());
        }
    }

    // ==================== 查询 ====================

    public Optional<RoutingDecisionRecord> get(String decisionId) {
        return repository.findById(decisionId);
    }

    public List<RoutingDecisionRecord> listByPayment(String paymentId) {
        return repository.findByPaymentIdOrderByCreatedAtDesc(paymentId);
    }

    public List<RoutingDecisionRecord> listByMerchant(Long merchantId) {
        return repository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public List<RoutingDecisionRecord> listByExperiment(String experimentId) {
        return repository.findByExperimentId(experimentId);
    }

    // ==================== 清理 ====================

    /** 保留期清理（每小时）。 */
    @Scheduled(fixedDelayString = "PT1H", initialDelay = 900000)
    @Transactional
    public void purgeExpired() {
        if (!properties.getAudit().isEnabled()) return;
        int days = Math.max(1, properties.getAudit().getRetentionDays());
        List<RoutingDecisionRecord> expired =
                repository.findByCreatedAtBefore(LocalDateTime.now().minusDays(days));
        if (!expired.isEmpty()) {
            repository.deleteAll(expired);
            log.info("Purged {} routing decision records older than {} days", expired.size(), days);
        }
    }
}
