package org.nexus.gateway.reconciliation.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ReconciliationRuleConfigService 单元测试。
 */
class ReconciliationRuleConfigServiceTest {

    private ReconciliationRuleConfigRepository repository;
    private ReconciliationRuleConfigService service;

    private static final Long MERCHANT_ID = 500L;
    private static final String CHANNEL_TYPE = "WECHAT";

    @BeforeEach
    void setUp() {
        repository = mock(ReconciliationRuleConfigRepository.class);
        service = new ReconciliationRuleConfigService(repository);
    }

    // ==================== 配置解析 ====================

    @Test
    @DisplayName("resolveConfig — 商户+渠道级配置优先")
    void resolveConfigMerchantChannelLevel() {
        ReconciliationRuleConfig mcConfig = createConfig(MERCHANT_ID, CHANNEL_TYPE,
                new BigDecimal("0.05"), 10);
        when(repository.findByMerchantIdAndChannelType(MERCHANT_ID, CHANNEL_TYPE))
                .thenReturn(Optional.of(mcConfig));

        ReconciliationRuleConfig result = service.resolveConfig(MERCHANT_ID, CHANNEL_TYPE);

        assertEquals(new BigDecimal("0.05"), result.getAmountTolerance());
        assertEquals(10, result.getTimeWindowMinutes());
        verify(repository).findByMerchantIdAndChannelType(MERCHANT_ID, CHANNEL_TYPE);
        verify(repository, never()).findByMerchantIdAndChannelTypeIsNull(any());
    }

    @Test
    @DisplayName("resolveConfig — 商户+渠道不存在时回退到商户级默认")
    void resolveConfigFallbackToMerchantLevel() {
        ReconciliationRuleConfig merchantConfig = createConfig(MERCHANT_ID, null,
                new BigDecimal("0.02"), 8);
        when(repository.findByMerchantIdAndChannelType(MERCHANT_ID, CHANNEL_TYPE))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdAndChannelTypeIsNull(MERCHANT_ID))
                .thenReturn(Optional.of(merchantConfig));

        ReconciliationRuleConfig result = service.resolveConfig(MERCHANT_ID, CHANNEL_TYPE);

        assertEquals(new BigDecimal("0.02"), result.getAmountTolerance());
        assertEquals(8, result.getTimeWindowMinutes());
    }

    @Test
    @DisplayName("resolveConfig — 商户级不存在时回退到渠道级默认")
    void resolveConfigFallbackToChannelLevel() {
        ReconciliationRuleConfig channelConfig = createConfig(null, CHANNEL_TYPE,
                new BigDecimal("0.03"), 15);
        when(repository.findByMerchantIdAndChannelType(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdAndChannelTypeIsNull(any()))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdIsNullAndChannelType(CHANNEL_TYPE))
                .thenReturn(Optional.of(channelConfig));

        ReconciliationRuleConfig result = service.resolveConfig(MERCHANT_ID, CHANNEL_TYPE);

        assertEquals(new BigDecimal("0.03"), result.getAmountTolerance());
        assertEquals(15, result.getTimeWindowMinutes());
    }

    @Test
    @DisplayName("resolveConfig — 所有层级都不存在时回退到全局默认")
    void resolveConfigFallbackToGlobalLevel() {
        ReconciliationRuleConfig globalConfig = createConfig(null, null,
                new BigDecimal("0.01"), 5);
        when(repository.findByMerchantIdAndChannelType(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdAndChannelTypeIsNull(any()))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdIsNullAndChannelType(any()))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdIsNullAndChannelTypeIsNull())
                .thenReturn(Optional.of(globalConfig));

        ReconciliationRuleConfig result = service.resolveConfig(MERCHANT_ID, CHANNEL_TYPE);

        assertEquals(new BigDecimal("0.01"), result.getAmountTolerance());
        assertEquals(5, result.getTimeWindowMinutes());
    }

    @Test
    @DisplayName("resolveConfig — 所有层级都不存在时使用内置默认值")
    void resolveConfigBuiltInDefault() {
        when(repository.findByMerchantIdAndChannelType(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdAndChannelTypeIsNull(any()))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdIsNullAndChannelType(any()))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdIsNullAndChannelTypeIsNull())
                .thenReturn(Optional.empty());

        ReconciliationRuleConfig result = service.resolveConfig(MERCHANT_ID, CHANNEL_TYPE);

        assertEquals(new BigDecimal("0.01"), result.getAmountTolerance());
        assertEquals(5, result.getTimeWindowMinutes());
    }

    @Test
    @DisplayName("resolveConfig — null merchantId 和 channelType 直接查全局")
    void resolveConfigNullParams() {
        ReconciliationRuleConfig globalConfig = createConfig(null, null,
                new BigDecimal("0.01"), 5);
        when(repository.findByMerchantIdIsNullAndChannelTypeIsNull())
                .thenReturn(Optional.of(globalConfig));

        ReconciliationRuleConfig result = service.resolveConfig(null, null);

        assertNotNull(result);
        verify(repository, never()).findByMerchantIdAndChannelType(any(), any());
    }

    // ==================== CRUD ====================

    @Test
    @DisplayName("createConfig — 创建配置并设置操作人")
    void createConfig() {
        ReconciliationRuleConfig config = createConfig(MERCHANT_ID, CHANNEL_TYPE,
                new BigDecimal("0.01"), 5);
        when(repository.save(any())).thenAnswer(inv -> {
            ReconciliationRuleConfig saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        ReconciliationRuleConfig result = service.createConfig(config, "admin");

        assertNotNull(result.getId());
        assertEquals("admin", result.getCreatedBy());
        assertEquals("admin", result.getUpdatedBy());
        verify(repository).save(config);
    }

    @Test
    @DisplayName("deleteConfig — 删除配置")
    void deleteConfig() {
        service.deleteConfig(1L);
        verify(repository).deleteById(1L);
    }

    // ==================== 辅助方法 ====================

    private ReconciliationRuleConfig createConfig(Long merchantId, String channelType,
                                                    BigDecimal tolerance, int timeWindow) {
        ReconciliationRuleConfig config = new ReconciliationRuleConfig();
        config.setMerchantId(merchantId);
        config.setChannelType(channelType);
        config.setAmountTolerance(tolerance);
        config.setTimeWindowMinutes(timeWindow);
        return config;
    }
}