package org.nexus.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BridgeConfig} 单元测试：覆盖配置项读写、金额校验、限额检查。
 */
class BridgeConfigTest {

    private BridgeConfig config;

    @BeforeEach
    void setUp() {
        config = new BridgeConfig();
        config.setSourceChainId("ethereum");
        config.setTargetChainId("bsc");
        config.setSignatureThreshold(3);
        config.setTimelockPeriodSeconds(3600);
        config.setMaxAmountPerTx(10_000_000_000L);
        config.setDailyLimit(100_000_000_000L);
        config.setLargeAmountThreshold(8_000_000_000L);
        config.setValidatorPublicKeys(Arrays.asList("v1", "v2", "v3"));
    }

    @Test
    @DisplayName("默认构造应产生空配置")
    void defaultConstructor_producesEmptyConfig() {
        BridgeConfig empty = new BridgeConfig();
        assertNull(empty.getValidatorPublicKeys());
        assertEquals(0, empty.getSignatureThreshold());
        assertEquals(0, empty.getTimelockPeriodSeconds());
        assertEquals(0, empty.getMaxAmountPerTx());
        assertEquals(0, empty.getDailyLimit());
        assertEquals(0, empty.getLargeAmountThreshold());
    }

    @Test
    @DisplayName("getter/setter 应正确读写所有字段")
    void gettersSetters_roundTrip() {
        assertEquals("ethereum", config.getSourceChainId());
        assertEquals("bsc", config.getTargetChainId());
        assertEquals(3, config.getSignatureThreshold());
        assertEquals(3600, config.getTimelockPeriodSeconds());
        assertEquals(10_000_000_000L, config.getMaxAmountPerTx());
        assertEquals(100_000_000_000L, config.getDailyLimit());
        assertEquals(8_000_000_000L, config.getLargeAmountThreshold());
        assertEquals(3, config.getValidatorPublicKeys().size());
    }

    @Test
    @DisplayName("isLargeAmount: 低于阈值返回 false，达到/超过阈值返回 true")
    void isLargeAmount_thresholdCheck() {
        assertFalse(config.isLargeAmount(7_999_999_999L));
        assertTrue(config.isLargeAmount(8_000_000_000L));
        assertTrue(config.isLargeAmount(20_000_000_000L));
    }

    @Test
    @DisplayName("exceedsMaxAmount: 等于上限不超，超过上限返回 true")
    void exceedsMaxAmount_boundaryCheck() {
        assertFalse(config.exceedsMaxAmount(0));
        assertFalse(config.exceedsMaxAmount(10_000_000_000L));
        assertTrue(config.exceedsMaxAmount(10_000_000_001L));
    }

    @Test
    @DisplayName("exceedsDailyLimit: 累计超额返回 true")
    void exceedsDailyLimit_accumulatedCheck() {
        assertFalse(config.exceedsDailyLimit(0, 0));
        assertFalse(config.exceedsDailyLimit(50_000_000_000L, 40_000_000_000L));
        assertTrue(config.exceedsDailyLimit(60_000_000_000L, 50_000_000_000L));
    }

    @Test
    @DisplayName("可设置空验证者列表")
    void setValidatorPublicKeys_emptyList() {
        config.setValidatorPublicKeys(Collections.emptyList());
        assertNotNull(config.getValidatorPublicKeys());
        assertTrue(config.getValidatorPublicKeys().isEmpty());
    }
}