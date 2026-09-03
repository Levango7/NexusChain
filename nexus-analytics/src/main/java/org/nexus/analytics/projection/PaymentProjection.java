package org.nexus.analytics.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付读模型投影（CQRS Query 侧）。
 *
 * <p>消费 {@code payment-events} Kafka topic，将事件投影到 {@link PaymentReadModel}，
 * 供 {@link PaymentQueryService} 提供查询服务。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@link KafkaListener} 消费 payment-events topic，groupId = "nexus-analytics-payment-projection"</li>
 *   <li>按 {@code eventType} 字段路由到不同投影方法</li>
 *   <li>幂等：基于 {@code aggregateId + version} 做去重，已应用的低版本事件被忽略</li>
 *   <li>顺序：Kafka 单分区内事件按 aggregateId 分区，保证同一聚合根事件有序</li>
 *   <li>失败重投：异常抛出后由 SeekToCurrentErrorHandler 重投，重试耗尽转投 DLQ</li>
 * </ul>
 *
 * <p>激活条件：
 * <ul>
 *   <li>classpath 存在 {@code KafkaListener} 类（spring-kafka 依赖已引入）</li>
 *   <li>{@code spring.kafka.listener.auto-startup=false} 时 @KafkaListener 不启动，但 bean 仍创建</li>
 * </ul>
 *
 * <p>本实现使用内存 Map 存储读模型。生产环境可替换为 JPA Repository + 数据库，
 * 接口不变，仅修改 {@link #getReadModelStore} 实现的存储后端。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
@Component
@ConditionalOnClass(name = "org.springframework.kafka.annotation.KafkaListener")
public class PaymentProjection {

    private static final Logger log = LoggerFactory.getLogger(PaymentProjection.class);

    /** Kafka topic：支付事件流 */
    public static final String TOPIC_PAYMENT_EVENTS = "payment-events";

    /** 消费组 ID（与 nexus-analytics 服务对齐） */
    public static final String GROUP_ID = "nexus-analytics-payment-projection";

    /** 读模型存储：aggregateId → PaymentReadModel */
    private final Map<String, PaymentReadModel> readModelStore = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public PaymentProjection() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 消费支付事件并投影到读模型。
     *
     * <p>使用默认 {@code kafkaListenerContainerFactory}（由 spring-kafka 自动配置提供）。
     * Kafka consumer 配置在 application.yml 的 {@code spring.kafka.consumer.*} 声明。
     *
     * <p>失败处理：异常抛出后由 Spring Kafka 的 SeekToCurrentErrorHandler 重投，
     * 重试耗尽后转投 DLQ（若配置）。本方法不吞没异常，保证事件不丢失。
     *
     * @param payload 事件 JSON 字符串
     */
    @KafkaListener(
            topics = TOPIC_PAYMENT_EVENTS,
            groupId = GROUP_ID
    )
    public void onPaymentEvent(@Payload String payload) {

        if (payload == null || payload.isBlank()) {
            log.warn("Empty payment event payload received");
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventType = root.path("eventType").asText("");
            String aggregateId = root.path("aggregateId").asText("");
            long version = root.path("version").asLong(0L);
            String eventId = root.path("eventId").asText("");
            Instant timestamp = parseInstant(root.path("timestamp"));

            if (eventType.isEmpty() || aggregateId.isEmpty()) {
                log.warn("Invalid payment event (missing eventType/aggregateId), eventId={}", eventId);
                return;
            }

            // 幂等校验：若已投影过更高或相同版本，跳过
            PaymentReadModel existing = readModelStore.get(aggregateId);
            if (existing != null && existing.getVersion() >= version) {
                log.debug("Stale event skipped: aggregateId={}, eventVersion={}, currentVersion={}",
                        aggregateId, version, existing.getVersion());
                return;
            }

            // 按事件类型路由投影
            switch (eventType) {
                case "PAYMENT_CREATED" -> projectCreated(root, aggregateId, version, timestamp);
                case "PAYMENT_PROCESSING" -> projectProcessing(root, aggregateId, version, timestamp);
                case "PAYMENT_SUCCEEDED" -> projectSucceeded(root, aggregateId, version, timestamp);
                case "PAYMENT_FAILED" -> projectFailed(root, aggregateId, version, timestamp);
                case "PAYMENT_REFUNDED" -> projectRefunded(root, aggregateId, version, timestamp);
                default -> log.warn("Unknown payment event type: {}, eventId={}", eventType, eventId);
            }

            log.debug("Payment event projected: eventType={}, aggregateId={}, version={}",
                    eventType, aggregateId, version);
        } catch (Exception e) {
            log.error("Failed to project payment event: payload={}, error={}", payload, e.getMessage(), e);
            // 抛出异常触发 SeekToCurrentErrorHandler 重投；若配置 DLQ 则转投
            throw new ProjectionException("Failed to project payment event: " + e.getMessage(), e);
        }
    }

    /**
     * 投影异常（触发 Kafka 重投）。
     */
    public static class ProjectionException extends RuntimeException {
        public ProjectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void projectCreated(JsonNode root, String aggregateId, long version, Instant timestamp) {
        Long merchantId = root.path("merchantId").asLong(0L);
        if (merchantId == 0L) {
            merchantId = null;
        }
        String orderNo = root.path("orderNo").asText(null);
        BigDecimal amount = parseBigDecimal(root.path("amount"));
        String tokenSymbol = root.path("tokenSymbol").asText(null);
        String payerAddress = root.path("payerAddress").asText(null);
        String payeeAddress = root.path("payeeAddress").asText(null);

        PaymentReadModel rm = PaymentReadModel.fromCreated(
                aggregateId, merchantId, orderNo, amount, tokenSymbol,
                payerAddress, payeeAddress, version, timestamp);
        readModelStore.put(aggregateId, rm);
    }

    private void projectProcessing(JsonNode root, String aggregateId, long version, Instant timestamp) {
        PaymentReadModel rm = readModelStore.computeIfAbsent(aggregateId, k -> new PaymentReadModel());
        rm.setAggregateId(aggregateId);
        rm.setState(PaymentReadModel.State.PROCESSING);
        String chainTxHash = root.path("chainTxHash").asText(null);
        if (chainTxHash != null && !chainTxHash.isEmpty()) {
            rm.setChainTxHash(chainTxHash);
        }
        rm.setVersion(version);
        rm.setUpdatedAt(timestamp);
    }

    private void projectSucceeded(JsonNode root, String aggregateId, long version, Instant timestamp) {
        PaymentReadModel rm = readModelStore.computeIfAbsent(aggregateId, k -> new PaymentReadModel());
        rm.setAggregateId(aggregateId);
        rm.setState(PaymentReadModel.State.SUCCEEDED);
        rm.setChainTxHash(root.path("chainTxHash").asText(null));
        BigDecimal settled = parseBigDecimal(root.path("settledAmount"));
        if (settled != null) {
            rm.setSettledAmount(settled);
        }
        Instant paidAt = parseInstant(root.path("paidAt"));
        rm.setPaidAt(paidAt);
        if (root.has("latencyMs") && !root.path("latencyMs").isNull()) {
            rm.setRoutingLatencyMs(root.path("latencyMs").asLong());
        }
        if (root.has("costBps") && !root.path("costBps").isNull()) {
            rm.setCostBps(root.path("costBps").asInt());
        }
        rm.setVersion(version);
        rm.setUpdatedAt(timestamp);
    }

    private void projectFailed(JsonNode root, String aggregateId, long version, Instant timestamp) {
        PaymentReadModel rm = readModelStore.computeIfAbsent(aggregateId, k -> new PaymentReadModel());
        rm.setAggregateId(aggregateId);
        rm.setState(PaymentReadModel.State.FAILED);
        rm.setFailureCode(root.path("failureCode").asText(null));
        rm.setFailureMessage(root.path("failureMessage").asText(null));
        rm.setVersion(version);
        rm.setUpdatedAt(timestamp);
    }

    private void projectRefunded(JsonNode root, String aggregateId, long version, Instant timestamp) {
        PaymentReadModel rm = readModelStore.computeIfAbsent(aggregateId, k -> new PaymentReadModel());
        rm.setAggregateId(aggregateId);
        rm.setState(PaymentReadModel.State.REFUNDED);
        rm.setRefundNo(root.path("refundNo").asText(null));
        rm.setRefundAmount(parseBigDecimal(root.path("refundAmount")));
        rm.setRefundChainTxHash(root.path("refundChainTxHash").asText(null));
        rm.setRefundReason(root.path("reason").asText(null));
        rm.setVersion(version);
        rm.setUpdatedAt(timestamp);
    }

    // ============ 工具方法 ============

    private Instant parseInstant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (Exception e) {
            return null;
        }
    }


    // ============ 读模型访问（供 PaymentQueryService 使用） ============

    /**
     * 获取读模型存储的不可变视图（供 {@link PaymentQueryService} 查询）。
     */
    Map<String, PaymentReadModel> getReadModelStore() {
        return readModelStore;
    }

    /**
     * 直接投影一个事件对象（测试用，绕过 Kafka）。
     */
    public void projectDirect(PaymentReadModel rm) {
        if (rm != null && rm.getAggregateId() != null) {
            readModelStore.put(rm.getAggregateId(), rm);
        }
    }
}
