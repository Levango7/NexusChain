package org.nexus.gateway.reconciliation.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StatusMappingService 单元测试。
 */
class StatusMappingServiceTest {

    private StatusMappingService service;

    @BeforeEach
    void setUp() {
        service = new StatusMappingService(new ObjectMapper());
    }

    @Test
    @DisplayName("parseStatusMapping — 正确解析 JSON 映射表")
    void parseStatusMappingValidJson() {
        String json = "{\"SUCCESS\":\"SUCCESS\",\"REFUND\":\"REFUNDED\"}";
        Map<String, String> result = service.parseStatusMapping(json);

        assertEquals(2, result.size());
        assertEquals("SUCCESS", result.get("SUCCESS"));
        assertEquals("REFUNDED", result.get("REFUND"));
    }

    @Test
    @DisplayName("parseStatusMapping — 空或 null JSON 返回空 Map")
    void parseStatusMappingEmptyOrNull() {
        assertTrue(service.parseStatusMapping(null).isEmpty());
        assertTrue(service.parseStatusMapping("").isEmpty());
        assertTrue(service.parseStatusMapping("  ").isEmpty());
    }

    @Test
    @DisplayName("parseStatusMapping — 非法 JSON 返回空 Map")
    void parseStatusMappingInvalidJson() {
        assertTrue(service.parseStatusMapping("{invalid}").isEmpty());
    }

    @Test
    @DisplayName("mapToInternalStatus — 映射表中存在时返回映射值")
    void mapToInternalStatusFound() {
        String json = "{\"TRADE_SUCCESS\":\"SUCCESS\",\"WAIT_BUYER_PAY\":\"PENDING\"}";
        assertEquals("SUCCESS", service.mapToInternalStatus(json, "TRADE_SUCCESS"));
        assertEquals("PENDING", service.mapToInternalStatus(json, "WAIT_BUYER_PAY"));
    }

    @Test
    @DisplayName("mapToInternalStatus — 映射表中不存在时返回原始值")
    void mapToInternalStatusNotFound() {
        String json = "{\"SUCCESS\":\"SUCCESS\"}";
        assertEquals("UNKNOWN_STATUS", service.mapToInternalStatus(json, "UNKNOWN_STATUS"));
    }

    @Test
    @DisplayName("mapToInternalStatus — null 渠道状态返回 null")
    void mapToInternalStatusNull() {
        assertNull(service.mapToInternalStatus("{}", null));
    }

    @Test
    @DisplayName("serializeStatusMapping — 正确序列化 Map 为 JSON")
    void serializeStatusMappingValid() {
        Map<String, String> mapping = Map.of("SUCCESS", "SUCCESS", "REFUND", "REFUNDED");
        String json = service.serializeStatusMapping(mapping);

        assertNotNull(json);
        Map<String, String> parsed = service.parseStatusMapping(json);
        assertEquals(2, parsed.size());
        assertEquals("SUCCESS", parsed.get("SUCCESS"));
        assertEquals("REFUNDED", parsed.get("REFUND"));
    }

    @Test
    @DisplayName("serializeStatusMapping — 空 Map 返回 null")
    void serializeStatusMappingEmpty() {
        assertNull(service.serializeStatusMapping(null));
        assertNull(service.serializeStatusMapping(Map.of()));
    }
}