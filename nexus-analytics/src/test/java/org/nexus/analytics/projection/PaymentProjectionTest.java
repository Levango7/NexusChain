package org.nexus.analytics.projection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentProjection} 单元测试。
 *
 * <p>Path B 扩展：覆盖 {@code projectSucceeded()} 将事件中的
 * {@code latencyMs}/{@code costBps} 提取到 {@link PaymentReadModel} 的链路埋点逻辑，
 * 以及幂等版本校验、非法/空 payload 熔断。</p>
 *
 * <p>通过 {@link PaymentProjection#onPaymentEvent(String)} 直接投喂 JSON payload，
 * 绕过 Kafka 秒耦测试，同包访问包私有 {@code getReadModelStore()} 读取投影结果。</p>
 */
class PaymentProjectionTest {

    private PaymentProjection projection;

    @BeforeEach
    void setUp() {
        projection = new PaymentProjection();
    }

    private String succeededPayload(String aggregateId, long version,
                                    String latency, String cost) {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
                .append("\"eventType\":\"PAYMENT_SUCCEEDED\",")
                .append("\"aggregateId\":\"").append(aggregateId).append("\",")
                .append("\"version\":").append(version).append(",")
                .append("\"eventId\":\"e1\",")
                .append("\"timestamp\":\"2026-01-01T00:00:00Z\",")
                .append("\"chainTxHash\":\"0xabc\",")
                .append("\"settledAmount\":\"100.50\",")
                .append("\"paidAt\":\"2026-01-01T00:00:00Z\",");
        // latencyMs / costBps：null 时输出 JSON null，未提供时省略
        if ("null".equals(latency)) {
            sb.append("\"latencyMs\":null,");
        } else if (latency != null) {
            sb.append("\"latencyMs\":").append(latency).append(",");
        }
        if ("null".equals(cost)) {
            sb.append("\"costBps\":null,");
        } else if (cost != null) {
            sb.append("\"costBps\":").append(cost).append(",");
        }
        // 去掉可能遗留的尾逗号
        String body = sb.toString();
        if (body.endsWith(",")) {
            body = body.substring(0, body.length() - 1);
        }
        return body + "}";
    }

    @Test
    void onPaymentEvent_succeeded_shouldExtractLatencyAndCost() {
        projection.onPaymentEvent(succeededPayload("pay_1", 3L, "42", "5"));

        PaymentReadModel rm = projection.getReadModelStore().get("pay_1");
        assertNotNull(rm);
        assertEquals(PaymentReadModel.State.SUCCEEDED, rm.getState());
        assertEquals("0xabc", rm.getChainTxHash());
        assertEquals(0, new BigDecimal("100.50").compareTo(rm.getSettledAmount()));
        assertEquals(42L, rm.getRoutingLatencyMs());
        assertEquals(5, rm.getCostBps());
        assertEquals(3L, rm.getVersion());
        assertNotNull(rm.getPaidAt());
    }

    @Test
    void onPaymentEvent_succeeded_zeroLatencyCost_shouldPreserve() {
        // 显式提供 0 也应提取（0 是有效值，区别于缺失）
        projection.onPaymentEvent(succeededPayload("pay_0", 1L, "0", "0"));

        PaymentReadModel rm = projection.getReadModelStore().get("pay_0");
        assertEquals(0L, rm.getRoutingLatencyMs());
        assertEquals(0, rm.getCostBps());
    }

    @Test
    void onPaymentEvent_succeeded_nullLatencyCost_shouldKeepNull() {
        projection.onPaymentEvent(succeededPayload("pay_null", 1L, "null", "null"));

        PaymentReadModel rm = projection.getReadModelStore().get("pay_null");
        assertNotNull(rm);
        assertEquals(PaymentReadModel.State.SUCCEEDED, rm.getState());
        assertNull(rm.getRoutingLatencyMs());
        assertNull(rm.getCostBps());
    }

    @Test
    void onPaymentEvent_succeeded_missingLatencyCost_shouldKeepNull() {
        projection.onPaymentEvent(succeededPayload("pay_missing", 1L, null, null));

        PaymentReadModel rm = projection.getReadModelStore().get("pay_missing");
        assertNull(rm.getRoutingLatencyMs());
        assertNull(rm.getCostBps());
    }

    @Test
    void onPaymentEvent_staleVersion_shouldBeIgnored() {
        // 先投 v5（高版本），再投 v3（陈旧）→ 读模型保持 v5 字段
        projection.onPaymentEvent(succeededPayload("pay_ver", 5L, "100", "20"));
        projection.onPaymentEvent(succeededPayload("pay_ver", 3L, "1", "1"));

        PaymentReadModel rm = projection.getReadModelStore().get("pay_ver");
        assertEquals(5L, rm.getVersion());
        assertEquals(100L, rm.getRoutingLatencyMs());
        assertEquals(20, rm.getCostBps());
        assertEquals("0xabc", rm.getChainTxHash());
    }

    @Test
    void onPaymentEvent_succeededNewVersion_shouldUpdateFields() {
        // 低版本先投，高版本后投 → 用高版本覆盖
        projection.onPaymentEvent(succeededPayload("pay_update", 1L, "10", "1"));
        projection.onPaymentEvent(succeededPayload("pay_update", 2L, "42", "5"));

        PaymentReadModel rm = projection.getReadModelStore().get("pay_update");
        assertEquals(2L, rm.getVersion());
        assertEquals(42L, rm.getRoutingLatencyMs());
        assertEquals(5, rm.getCostBps());
    }

    @Test
    void onPaymentEvent_blankPayload_shouldBeNoOp() {
        projection.onPaymentEvent("");
        projection.onPaymentEvent("   ");
        assertTrue(projection.getReadModelStore().isEmpty());
    }

    @Test
    void onPaymentEvent_missingEventTypeOrAggregateId_shouldBeNoOp() {
        projection.onPaymentEvent("{\"eventType\":\"PAYMENT_SUCCEEDED\",\"aggregateId\":\"\"}");
        projection.onPaymentEvent("{\"eventType\":\"\",\"aggregateId\":\"pay_x\"}");
        assertTrue(projection.getReadModelStore().isEmpty());
    }

    @Test
    void onPaymentEvent_malformedJson_shouldThrowProjectionException() {
        assertThrows(PaymentProjection.ProjectionException.class,
                () -> projection.onPaymentEvent("{ not valid json"));
    }

    @Test
    void onPaymentEvent_succeededThenFailed_shouldMoveToFailed() {
        projection.onPaymentEvent(succeededPayload("pay_fail", 2L, "42", "5"));
        projection.onPaymentEvent("{"
                + "\"eventType\":\"PAYMENT_FAILED\","
                + "\"aggregateId\":\"pay_fail\","
                + "\"version\":3,"
                + "\"timestamp\":\"2026-01-01T00:00:00Z\","
                + "\"failureCode\":\"CHAIN_TIMEOUT\","
                + "\"failureMessage\":\"timeout\""
                + "}");

        PaymentReadModel rm = projection.getReadModelStore().get("pay_fail");
        assertEquals(PaymentReadModel.State.FAILED, rm.getState());
        assertEquals("CHAIN_TIMEOUT", rm.getFailureCode());
        assertEquals(3L, rm.getVersion());
        // 埋点字段保留（FAILED 事件不覆盖已提取值）
        assertEquals(42L, rm.getRoutingLatencyMs());
        assertEquals(5, rm.getCostBps());
    }
}