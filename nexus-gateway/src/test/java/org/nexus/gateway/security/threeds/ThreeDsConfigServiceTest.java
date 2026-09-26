package org.nexus.gateway.security.threeds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ThreeDsConfigService 单元测试 — 验证 3DS 配置管理的查询和更新逻辑。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>getConfig 商户级配置优先</li>
 *   <li>getConfig 无商户级时回退租户级配置</li>
 *   <li>getConfig 无任何配置时返回默认值</li>
 *   <li>updateConfig 创建/更新商户级配置</li>
 *   <li>updateConfig 创建/更新租户级配置</li>
 * </ul>
 * </p>
 */
@DisplayName("ThreeDsConfigService 配置管理测试")
class ThreeDsConfigServiceTest {

    private ThreeDsConfigRepository configRepository;
    private ThreeDsConfigService configService;

    @BeforeEach
    void setUp() {
        configRepository = mock(ThreeDsConfigRepository.class);
        configService = new ThreeDsConfigService(configRepository);
    }

    // --- 辅助方法 ---

    /**
     * 创建商户级配置。
     */
    private ThreeDsConfig merchantConfig() {
        ThreeDsConfig config = new ThreeDsConfig();
        config.setId(1L);
        config.setTenantId("tenant-001");
        config.setMerchantId(200L);
        config.setEnabled(true);
        config.setFrictionlessThresholdScore(50);
        config.setChallengeTimeoutSeconds(180);
        config.setAcsUrl("https://merchant-acs.example.com");
        return config;
    }

    /**
     * 创建租户级配置（merchantId 为 null）。
     */
    private ThreeDsConfig tenantConfig() {
        ThreeDsConfig config = new ThreeDsConfig();
        config.setId(2L);
        config.setTenantId("tenant-001");
        config.setMerchantId(null);
        config.setEnabled(true);
        config.setFrictionlessThresholdScore(70);
        config.setChallengeTimeoutSeconds(600);
        config.setAcsUrl("https://tenant-acs.example.com");
        return config;
    }

    // --- getConfig 测试 ---

    @Nested
    @DisplayName("getConfig - 查询 3DS 配置")
    class GetConfig {

        @Test
        @DisplayName("存在商户级配置 → 返回商户级配置（优先）")
        void merchantConfigExists_returnsMerchantConfig() {
            ThreeDsConfig merchant = merchantConfig();

            when(configRepository.findByTenantIdAndMerchantId("tenant-001", 200L))
                    .thenReturn(Optional.of(merchant));

            ThreeDsConfig result = configService.getConfig("tenant-001", 200L);

            assertNotNull(result);
            assertEquals(200L, result.getMerchantId(),
                    "应返回商户级配置");
            assertEquals(50, result.getFrictionlessThresholdScore(),
                    "应返回商户级配置的阈值分数");
            assertTrue(result.isEnabled(),
                    "应返回商户级配置的启用状态");
            assertEquals("https://merchant-acs.example.com", result.getAcsUrl(),
                    "应返回商户级配置的 ACS URL");

            // 不应查询租户级配置
            verify(configRepository, never()).findByTenantIdAndMerchantIdIsNull(any());
        }

        @Test
        @DisplayName("无商户级配置 → 回退租户级配置")
        void noMerchantConfig_fallsBackToTenantConfig() {
            ThreeDsConfig tenant = tenantConfig();

            when(configRepository.findByTenantIdAndMerchantId("tenant-001", 200L))
                    .thenReturn(Optional.empty());
            when(configRepository.findByTenantIdAndMerchantIdIsNull("tenant-001"))
                    .thenReturn(Optional.of(tenant));

            ThreeDsConfig result = configService.getConfig("tenant-001", 200L);

            assertNotNull(result);
            assertNull(result.getMerchantId(),
                    "应返回租户级配置（merchantId 为 null）");
            assertEquals(70, result.getFrictionlessThresholdScore(),
                    "应返回租户级配置的阈值分数");
            assertTrue(result.isEnabled());

            // 应先查商户级，再查租户级
            verify(configRepository, times(1)).findByTenantIdAndMerchantId("tenant-001", 200L);
            verify(configRepository, times(1)).findByTenantIdAndMerchantIdIsNull("tenant-001");
        }

        @Test
        @DisplayName("无商户级也无租户级配置 → 返回默认配置")
        void noConfigAtAll_returnsDefaultConfig() {
            when(configRepository.findByTenantIdAndMerchantId("tenant-001", 200L))
                    .thenReturn(Optional.empty());
            when(configRepository.findByTenantIdAndMerchantIdIsNull("tenant-001"))
                    .thenReturn(Optional.empty());

            ThreeDsConfig result = configService.getConfig("tenant-001", 200L);

            assertNotNull(result, "默认配置不应为 null");
            assertFalse(result.isEnabled(),
                    "默认配置 enabled 应为 false");
            assertEquals(ThreeDsConfigService.DEFAULT_FRICTIONLESS_THRESHOLD_SCORE,
                    result.getFrictionlessThresholdScore(),
                    "默认 Frictionless 阈值应为 " + ThreeDsConfigService.DEFAULT_FRICTIONLESS_THRESHOLD_SCORE);
            assertEquals(ThreeDsConfigService.DEFAULT_CHALLENGE_TIMEOUT_SECONDS,
                    result.getChallengeTimeoutSeconds(),
                    "默认 Challenge 超时应为 " + ThreeDsConfigService.DEFAULT_CHALLENGE_TIMEOUT_SECONDS);
            assertEquals("tenant-001", result.getTenantId(),
                    "默认配置应设置 tenantId");
            assertEquals(200L, result.getMerchantId(),
                    "默认配置应设置 merchantId");
        }

        @Test
        @DisplayName("merchantId 为 null → 直接查询租户级配置")
        void nullMerchantId_queriesTenantConfig() {
            ThreeDsConfig tenant = tenantConfig();

            when(configRepository.findByTenantIdAndMerchantIdIsNull("tenant-001"))
                    .thenReturn(Optional.of(tenant));

            ThreeDsConfig result = configService.getConfig("tenant-001", null);

            assertNotNull(result);
            assertNull(result.getMerchantId(),
                    "merchantId 为 null 时应返回租户级配置");

            // 不应查询商户级配置
            verify(configRepository, never()).findByTenantIdAndMerchantId(any(), any());
        }

        @Test
        @DisplayName("merchantId 为 null 且无租户级配置 → 返回默认配置")
        void nullMerchantIdNoTenantConfig_returnsDefault() {
            when(configRepository.findByTenantIdAndMerchantIdIsNull("tenant-001"))
                    .thenReturn(Optional.empty());

            ThreeDsConfig result = configService.getConfig("tenant-001", null);

            assertNotNull(result);
            assertFalse(result.isEnabled(),
                    "默认配置 enabled 应为 false");
            assertEquals(ThreeDsConfigService.DEFAULT_FRICTIONLESS_THRESHOLD_SCORE,
                    result.getFrictionlessThresholdScore());
        }

        @Test
        @DisplayName("默认配置的 frictionlessThresholdScore = 60")
        void defaultConfig_thresholdScoreIs60() {
            when(configRepository.findByTenantIdAndMerchantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(configRepository.findByTenantIdAndMerchantIdIsNull(any()))
                    .thenReturn(Optional.empty());

            ThreeDsConfig result = configService.getConfig("tenant-001", 200L);

            assertEquals(60, result.getFrictionlessThresholdScore(),
                    "默认 Frictionless 阈值分数应为 60");
        }

        @Test
        @DisplayName("默认配置的 challengeTimeoutSeconds = 300")
        void defaultConfig_challengeTimeoutIs300() {
            when(configRepository.findByTenantIdAndMerchantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(configRepository.findByTenantIdAndMerchantIdIsNull(any()))
                    .thenReturn(Optional.empty());

            ThreeDsConfig result = configService.getConfig("tenant-001", 200L);

            assertEquals(300, result.getChallengeTimeoutSeconds(),
                    "默认 Challenge 超时秒数应为 300");
        }
    }

    // --- updateConfig 测试 ---

    @Nested
    @DisplayName("updateConfig - 更新 3DS 配置")
    class UpdateConfig {

        @Test
        @DisplayName("更新已存在的商户级配置")
        void updateExistingMerchantConfig() {
            ThreeDsConfig existing = merchantConfig();

            when(configRepository.findByTenantIdAndMerchantId("tenant-001", 200L))
                    .thenReturn(Optional.of(existing));
            when(configRepository.save(any(ThreeDsConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ThreeDsConfig result = configService.updateConfig(
                    "tenant-001", 200L, true, 40, 120, "https://new-acs.example.com");

            assertNotNull(result);
            assertTrue(result.isEnabled());
            assertEquals(40, result.getFrictionlessThresholdScore(),
                    "阈值分数应更新为 40");
            assertEquals(120, result.getChallengeTimeoutSeconds(),
                    "超时秒数应更新为 120");
            assertEquals("https://new-acs.example.com", result.getAcsUrl(),
                    "ACS URL 应更新");

            verify(configRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("更新不存在的商户级配置 → 创建新配置")
        void updateNonExistingMerchantConfig_createsNew() {
            when(configRepository.findByTenantIdAndMerchantId("tenant-001", 300L))
                    .thenReturn(Optional.empty());
            when(configRepository.save(any(ThreeDsConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ThreeDsConfig result = configService.updateConfig(
                    "tenant-001", 300L, true, 55, 240, "https://new-acs.example.com");

            assertNotNull(result);
            assertEquals("tenant-001", result.getTenantId());
            assertEquals(300L, result.getMerchantId());
            assertTrue(result.isEnabled());
            assertEquals(55, result.getFrictionlessThresholdScore());
            assertEquals(240, result.getChallengeTimeoutSeconds());

            verify(configRepository, times(1)).save(any(ThreeDsConfig.class));
        }

        @Test
        @DisplayName("更新租户级配置（merchantId 为 null）")
        void updateTenantConfig() {
            ThreeDsConfig existing = tenantConfig();

            when(configRepository.findByTenantIdAndMerchantIdIsNull("tenant-001"))
                    .thenReturn(Optional.of(existing));
            when(configRepository.save(any(ThreeDsConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ThreeDsConfig result = configService.updateConfig(
                    "tenant-001", null, false, 80, 360, null);

            assertNotNull(result);
            assertFalse(result.isEnabled());
            assertEquals(80, result.getFrictionlessThresholdScore());
            assertEquals(360, result.getChallengeTimeoutSeconds());
            assertNull(result.getMerchantId(),
                    "租户级配置 merchantId 应为 null");

            verify(configRepository, times(1)).save(existing);
        }

        @Test
        @DisplayName("更新不存在的租户级配置 → 创建新配置")
        void updateNonExistingTenantConfig_createsNew() {
            when(configRepository.findByTenantIdAndMerchantIdIsNull("tenant-001"))
                    .thenReturn(Optional.empty());
            when(configRepository.save(any(ThreeDsConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ThreeDsConfig result = configService.updateConfig(
                    "tenant-001", null, true, 65, 300, "https://tenant-acs.example.com");

            assertNotNull(result);
            assertEquals("tenant-001", result.getTenantId());
            assertNull(result.getMerchantId());
            assertTrue(result.isEnabled());
            assertEquals(65, result.getFrictionlessThresholdScore());

            verify(configRepository, times(1)).save(any(ThreeDsConfig.class));
        }
    }
}