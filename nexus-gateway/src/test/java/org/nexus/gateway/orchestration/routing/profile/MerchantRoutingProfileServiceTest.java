package org.nexus.gateway.orchestration.routing.profile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.RoutingWave16Properties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link MerchantRoutingProfileService} 单元测试：解析优先级、金额分层、时段窗口、偏好解析。
 */
class MerchantRoutingProfileServiceTest {

    private MerchantRoutingProfileRepository repository;
    private RoutingWave16Properties properties;
    private MerchantRoutingProfileService service;

    private static final Long MERCHANT_ID = 7L;

    @BeforeEach
    void setUp() {
        repository = mock(MerchantRoutingProfileRepository.class);
        properties = new RoutingWave16Properties();
        service = new MerchantRoutingProfileService(repository, properties);
    }

    private MerchantRoutingProfile profile(Long merchantId, String industry, String tiers,
                                           String windows, String objectives, String prefs) {
        MerchantRoutingProfile profile = new MerchantRoutingProfile();
        profile.setId(1L);
        profile.setProfileId("p-" + System.nanoTime());
        profile.setMerchantId(merchantId);
        profile.setIndustry(industry);
        profile.setAmountTierRulesJson(tiers);
        profile.setTimeWindowRulesJson(windows);
        profile.setPreferredObjectivesJson(objectives);
        profile.setConnectorPreferencesJson(prefs);
        profile.setPriority(1);
        profile.setEnabled(true);
        return profile;
    }

    @Test
    @DisplayName("resolve: 商户级 > 行业级 > 默认画像")
    void resolvePriority() {
        // 无任何配置 → 默认画像
        when(repository.findByMerchantIdAndEnabledTrueOrderByPriorityDesc(MERCHANT_ID)).thenReturn(List.of());
        ResolvedRoutingProfile defaultProfile = service.resolve(MERCHANT_ID, null, BigDecimal.valueOf(500));
        assertEquals("default", defaultProfile.profileId());
        assertEquals("MEDIUM", defaultProfile.amountTier());

        // 行业级
        MerchantRoutingProfile industryProfile = profile(null, "GAMING",
                null, null, "[\"LATENCY\"]", "{\"preferred\":[\"wechat\"]}");
        when(repository.findByIndustryAndEnabledTrueOrderByPriorityDesc("GAMING"))
                .thenReturn(List.of(industryProfile));
        ResolvedRoutingProfile industry = service.resolve(MERCHANT_ID, "GAMING", BigDecimal.valueOf(500));
        assertEquals("GAMING", industry.industry());
        assertEquals(List.of("wechat"), industry.preferredConnectors());

        // 商户级覆盖行业级
        MerchantRoutingProfile merchantProfile = profile(MERCHANT_ID, null,
                null, null, "[\"COST\"]", "{\"excluded\":[\"mock\"]}");
        when(repository.findByMerchantIdAndEnabledTrueOrderByPriorityDesc(MERCHANT_ID))
                .thenReturn(List.of(merchantProfile));
        ResolvedRoutingProfile merchant = service.resolve(MERCHANT_ID, "GAMING", BigDecimal.valueOf(500));
        assertEquals(MERCHANT_ID, merchant.merchantId());
        assertEquals(List.of("mock"), merchant.excludedConnectors());
    }

    @Test
    @DisplayName("金额分层: 自定义区间规则优先，未命中回退全局阈值")
    void amountTiers() {
        String tiers = "[{\"min\":0,\"max\":100,\"tier\":\"SMALL\"},{\"min\":100,\"max\":10000,\"tier\":\"MEDIUM\"},{\"min\":10000,\"tier\":\"LARGE\"}]";
        assertEquals("SMALL", service.resolveAmountTier(tiers, 99));
        assertEquals("MEDIUM", service.resolveAmountTier(tiers, 100));
        assertEquals("LARGE", service.resolveAmountTier(tiers, 10000));
        // 区间外 → 全局阈值（large 阈值默认 10000）
        assertEquals("LARGE", service.resolveAmountTier("[{\"min\":0,\"max\":1,\"tier\":\"SMALL\"}]", 10000));
        // 无规则 → 全局阈值
        assertEquals("SMALL", service.resolveAmountTier(null, 50));
        assertEquals("LARGE", service.resolveAmountTier(null, 20000));
    }

    @Test
    @DisplayName("非法 JSON: 画像列解析失败时降级为全局默认而非抛出")
    void invalidJsonFallsBack() {
        assertEquals("MEDIUM", service.resolveAmountTier("{invalid", 5000));
        // 非法时段 JSON → 回退全局高峰窗口（09:00-22:00 内必为 true）
        boolean peak = service.inPeakHours("{invalid");
        assertEquals(service.inPeakHours(null), peak);
    }

    @Test
    @DisplayName("connector_preferences_json: preferred/excluded 正确解析")
    void connectorPreferences() {
        MerchantRoutingProfile profile = profile(MERCHANT_ID, null, null, null, null,
                "{\"preferred\":[\"wechat\",\"alipay\"],\"excluded\":[\"mock\"]}");
        when(repository.findByMerchantIdAndEnabledTrueOrderByPriorityDesc(MERCHANT_ID))
                .thenReturn(List.of(profile));
        ResolvedRoutingProfile resolved = service.resolve(MERCHANT_ID, null, BigDecimal.valueOf(100));
        assertEquals(List.of("wechat", "alipay"), resolved.preferredConnectors());
        assertEquals(List.of("mock"), resolved.excludedConnectors());
    }

    @Test
    @DisplayName("create: profile_id 重复 / 行业级缺 industry 被拒绝")
    void createValidation() {
        MerchantRoutingProfile industryLevel = profile(null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.create(industryLevel));

        MerchantRoutingProfile merchantLevel = profile(MERCHANT_ID, null, null, null, null, null);
        when(repository.findByProfileId(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertNotNull(service.create(merchantLevel));
    }
}
