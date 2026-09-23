package org.nexus.gateway.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BusinessLogContext} 单元测试（任务 #28）。
 *
 * <p>测试 MDC 上下文设置、获取和清理功能。</p>
 */
class BusinessLogContextTest {

    private BusinessLogContext businessLogContext;

    @BeforeEach
    void setUp() {
        businessLogContext = new BusinessLogContext();
        MDC.clear(); // 确保测试前 MDC 为空
    }

    @AfterEach
    void tearDown() {
        MDC.clear(); // 清理 MDC，避免影响其他测试
    }

    // === setContext 测试 ===

    @Test
    @DisplayName("setContext 设置所有业务字段到 MDC")
    void testSetContextAllFields() {
        businessLogContext.setContext("M001", "ORD123", "PAY456", "stripe");

        assertEquals("M001", MDC.get(BusinessLogContext.MERCHANT_ID));
        assertEquals("ORD123", MDC.get(BusinessLogContext.ORDER_ID));
        assertEquals("PAY456", MDC.get(BusinessLogContext.PAYMENT_ID));
        assertEquals("stripe", MDC.get(BusinessLogContext.CONNECTOR_TYPE));
    }

    @Test
    @DisplayName("setContext null 值不设置到 MDC")
    void testSetContextNullValues() {
        businessLogContext.setContext("M001", null, null, "stripe");

        assertEquals("M001", MDC.get(BusinessLogContext.MERCHANT_ID));
        assertNull(MDC.get(BusinessLogContext.ORDER_ID), "null 值不应设置到 MDC");
        assertNull(MDC.get(BusinessLogContext.PAYMENT_ID), "null 值不应设置到 MDC");
        assertEquals("stripe", MDC.get(BusinessLogContext.CONNECTOR_TYPE));
    }

    @Test
    @DisplayName("setContext 全部 null 不设置任何字段")
    void testSetContextAllNull() {
        businessLogContext.setContext(null, null, null, null);

        assertNull(MDC.get(BusinessLogContext.MERCHANT_ID));
        assertNull(MDC.get(BusinessLogContext.ORDER_ID));
        assertNull(MDC.get(BusinessLogContext.PAYMENT_ID));
        assertNull(MDC.get(BusinessLogContext.CONNECTOR_TYPE));
    }

    // === setField / getField 测试 ===

    @Test
    @DisplayName("setField 设置单个字段")
    void testSetField() {
        businessLogContext.setField(BusinessLogContext.MERCHANT_ID, "M002");
        assertEquals("M002", MDC.get(BusinessLogContext.MERCHANT_ID));
    }

    @Test
    @DisplayName("setField null 值移除字段")
    void testSetFieldNullRemoves() {
        MDC.put(BusinessLogContext.MERCHANT_ID, "M001");
        businessLogContext.setField(BusinessLogContext.MERCHANT_ID, null);
        assertNull(MDC.get(BusinessLogContext.MERCHANT_ID), "null 值应移除字段");
    }

    @Test
    @DisplayName("getField 获取已设置的字段")
    void testGetField() {
        MDC.put(BusinessLogContext.ORDER_ID, "ORD789");
        assertEquals("ORD789", businessLogContext.getField(BusinessLogContext.ORDER_ID));
    }

    @Test
    @DisplayName("getField 获取未设置的字段返回 null")
    void testGetFieldNotSet() {
        assertNull(businessLogContext.getField(BusinessLogContext.PAYMENT_ID));
    }

    // === getContext 测试 ===

    @Test
    @DisplayName("getContext 返回所有已设置字段的快照")
    void testGetContext() {
        businessLogContext.setContext("M001", "ORD123", "PAY456", "adyen");

        Map<String, String> context = businessLogContext.getContext();

        assertEquals("M001", context.get(BusinessLogContext.MERCHANT_ID));
        assertEquals("ORD123", context.get(BusinessLogContext.ORDER_ID));
        assertEquals("PAY456", context.get(BusinessLogContext.PAYMENT_ID));
        assertEquals("adyen", context.get(BusinessLogContext.CONNECTOR_TYPE));
    }

    @Test
    @DisplayName("getContext 未设置的字段不包含在快照中")
    void testGetContextPartial() {
        businessLogContext.setContext("M001", null, null, "stripe");

        Map<String, String> context = businessLogContext.getContext();

        assertEquals("M001", context.get(BusinessLogContext.MERCHANT_ID));
        assertEquals("stripe", context.get(BusinessLogContext.CONNECTOR_TYPE));
        assertFalse(context.containsKey(BusinessLogContext.ORDER_ID), "未设置的字段不应包含");
        assertFalse(context.containsKey(BusinessLogContext.PAYMENT_ID), "未设置的字段不应包含");
    }

    @Test
    @DisplayName("getContext 空上下文返回空 Map")
    void testGetContextEmpty() {
        Map<String, String> context = businessLogContext.getContext();
        assertTrue(context.isEmpty(), "空上下文应返回空 Map");
    }

    // === clearContext 测试 ===

    @Test
    @DisplayName("clearContext 清理所有业务字段")
    void testClearContext() {
        businessLogContext.setContext("M001", "ORD123", "PAY456", "stripe");
        businessLogContext.clearContext();

        assertNull(MDC.get(BusinessLogContext.MERCHANT_ID));
        assertNull(MDC.get(BusinessLogContext.ORDER_ID));
        assertNull(MDC.get(BusinessLogContext.PAYMENT_ID));
        assertNull(MDC.get(BusinessLogContext.CONNECTOR_TYPE));
    }

    @Test
    @DisplayName("clearContext 不影响 traceId（由 Micrometer Tracing 管理）")
    void testClearContextPreservesTraceId() {
        MDC.put(BusinessLogContext.TRACE_ID, "trace123");
        businessLogContext.setContext("M001", "ORD123", "PAY456", "stripe");
        businessLogContext.clearContext();

        assertEquals("trace123", MDC.get(BusinessLogContext.TRACE_ID), "traceId 不应被清理");
        assertNull(MDC.get(BusinessLogContext.MERCHANT_ID), "业务字段应被清理");
    }

    @Test
    @DisplayName("clearContext 在空上下文时安全执行")
    void testClearContextEmpty() {
        assertDoesNotThrow(() -> businessLogContext.clearContext(), "空上下文时清理不应抛异常");
    }

    // === 重复设置测试 ===

    @Test
    @DisplayName("重复 setContext 覆盖之前的值")
    void testSetContextOverwrite() {
        businessLogContext.setContext("M001", "ORD123", "PAY456", "stripe");
        businessLogContext.setContext("M002", "ORD456", "PAY789", "adyen");

        assertEquals("M002", MDC.get(BusinessLogContext.MERCHANT_ID));
        assertEquals("ORD456", MDC.get(BusinessLogContext.ORDER_ID));
        assertEquals("PAY789", MDC.get(BusinessLogContext.PAYMENT_ID));
        assertEquals("adyen", MDC.get(BusinessLogContext.CONNECTOR_TYPE));
    }
}