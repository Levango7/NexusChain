package org.nexus.gateway.orchestration.connector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connectors.AlipayConnector;
import org.nexus.gateway.orchestration.connectors.DynamicHttpPspConnector;
import org.nexus.gateway.orchestration.connectors.WeChatPayConnector;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ConnectorConfigService} 和 {@link ConnectorFactory} 的单元测试。
 *
 * <p>测试覆盖：</p>
 * <ul>
 *   <li>ConnectorConfig 实体创建和字段访问</li>
 *   <li>ConnectorConfigRepository save 和 findById</li>
 *   <li>ConnectorConfigService registerConfig - http_psp/wechat/alipay 类型成功</li>
 *   <li>ConnectorConfigService registerConfig - 不支持的类型抛异常</li>
 *   <li>ConnectorConfigService unregisterConfig - 成功注销/不存在的 ID</li>
 *   <li>ConnectorConfigService findAll / findActive</li>
 *   <li>ConnectorFactory create - 各类型 / 不支持的类型抛异常</li>
 *   <li>启动恢复 - 从数据库加载并注册到 Registry</li>
 * </ul>
 */
class ConnectorConfigServiceTest {

    private ConnectorConfigRepository repository;
    private ConnectorRegistry registry;
    private ConnectorFactory factory;
    private PspTargetPolicy pspTargetPolicy;
    private ConnectorConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConnectorConfigRepository.class);
        registry = mock(ConnectorRegistry.class);
        factory = new ConnectorFactory(new RestTemplate());
        // PspTargetPolicy：允许 PSP_X_API_KEY 环境变量和 api.pspx.example host
        pspTargetPolicy = new PspTargetPolicy("PSP_X_API_KEY", "api.pspx.example");
        service = new ConnectorConfigService(repository, registry, factory, pspTargetPolicy);
    }

    // === 1. ConnectorConfig 实体创建和字段访问 ===

    @Test
    @DisplayName("ConnectorConfig 实体创建和字段访问")
    void connectorConfigEntityCreationAndFieldAccess() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("test-connector");
        config.setType("http_psp");
        config.setDisplayName("Test Connector");
        config.setBaseUrl("https://api.example.com");
        config.setApiKeyEnv("PSP_X_API_KEY");
        config.setCurrencies("USD,EUR");
        config.setFeeBps(150);
        config.setActive(true);

        assertEquals("test-connector", config.getId());
        assertEquals("http_psp", config.getType());
        assertEquals("Test Connector", config.getDisplayName());
        assertEquals("https://api.example.com", config.getBaseUrl());
        assertEquals("PSP_X_API_KEY", config.getApiKeyEnv());
        assertEquals("USD,EUR", config.getCurrencies());
        assertEquals(150, config.getFeeBps());
        assertTrue(config.isActive());
    }

    // === 2. ConnectorConfigRepository save 和 findById ===

    @Test
    @DisplayName("ConnectorConfigRepository save 和 findById")
    void repositorySaveAndFindById() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("repo-test");
        config.setType("http_psp");
        config.setActive(true);

        when(repository.save(config)).thenReturn(config);
        when(repository.findById("repo-test")).thenReturn(Optional.of(config));

        ConnectorConfig saved = repository.save(config);
        assertEquals("repo-test", saved.getId());

        Optional<ConnectorConfig> found = repository.findById("repo-test");
        assertTrue(found.isPresent());
        assertEquals("repo-test", found.get().getId());

        verify(repository).save(config);
        verify(repository).findById("repo-test");
    }

    // === 3. ConnectorConfigService registerConfig - http_psp 类型成功 ===

    @Test
    @DisplayName("registerConfig: http_psp 类型成功注册")
    void registerConfigHttpPspSuccess() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("psp-x");
        config.setType("http_psp");
        config.setDisplayName("PSP X");
        config.setBaseUrl("https://api.pspx.example");
        config.setApiKeyEnv("PSP_X_API_KEY");
        config.setCurrencies("USD,EUR");
        config.setFeeBps(150);
        config.setActive(true);

        when(repository.save(config)).thenReturn(config);

        ConnectorConfig result = service.registerConfig(config);

        assertEquals("psp-x", result.getId());
        verify(repository).save(config);
        verify(registry).register(any(PaymentConnector.class));
    }

    // === 4. ConnectorConfigService registerConfig - wechat 类型成功 ===

    @Test
    @DisplayName("registerConfig: wechat 类型成功注册")
    void registerConfigWechatSuccess() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("wechat_merchant_2");
        config.setType("wechat");
        config.setDisplayName("WeChat Merchant 2");
        config.setAppId("wx1234567890");
        config.setMchId("1234567890");
        config.setApiKeyEnv("WECHAT_API_KEY_MERCHANT_2");
        config.setCurrencies("CNY");
        config.setFeeBps(60);
        config.setActive(true);

        when(repository.save(config)).thenReturn(config);

        ConnectorConfig result = service.registerConfig(config);

        assertEquals("wechat_merchant_2", result.getId());
        verify(repository).save(config);
        verify(registry).register(any(PaymentConnector.class));
    }

    // === 5. ConnectorConfigService registerConfig - alipay 类型成功 ===

    @Test
    @DisplayName("registerConfig: alipay 类型成功注册")
    void registerConfigAlipaySuccess() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("alipay_merchant_2");
        config.setType("alipay");
        config.setDisplayName("Alipay Merchant 2");
        config.setAppId("2021001234567890");
        config.setMerchantPrivateKey("ALIPAY_MERCHANT_PRIVATE_KEY_ENV");
        config.setAlipayPublicKey("ALIPAY_PUBLIC_KEY_ENV");
        config.setCurrencies("CNY");
        config.setFeeBps(38);
        config.setActive(true);

        when(repository.save(config)).thenReturn(config);

        ConnectorConfig result = service.registerConfig(config);

        assertEquals("alipay_merchant_2", result.getId());
        verify(repository).save(config);
        verify(registry).register(any(PaymentConnector.class));
    }

    // === 6. ConnectorConfigService registerConfig - 不支持的类型抛异常 ===

    @Test
    @DisplayName("registerConfig: 不支持的类型抛 IllegalArgumentException")
    void registerConfigUnsupportedTypeThrowsException() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("unsupported-x");
        config.setType("stripe");  // 不支持动态注册的类型
        config.setActive(true);

        when(repository.save(config)).thenReturn(config);

        assertThrows(IllegalArgumentException.class, () -> service.registerConfig(config));
        verify(registry, never()).register(any(PaymentConnector.class));
    }

    // === 7. ConnectorConfigService unregisterConfig - 成功注销 ===

    @Test
    @DisplayName("unregisterConfig: 成功注销已存在的配置")
    void unregisterConfigSuccess() {
        String id = "psp-to-remove";
        when(repository.existsById(id)).thenReturn(true);

        boolean result = service.unregisterConfig(id);

        assertTrue(result);
        verify(repository).existsById(id);
        verify(repository).deleteById(id);
        verify(registry).unregister(id);
    }

    // === 8. ConnectorConfigService unregisterConfig - 不存在的 ID ===

    @Test
    @DisplayName("unregisterConfig: 不存在的 ID 返回 false")
    void unregisterConfigNotFoundReturnsFalse() {
        String id = "nonexistent";
        when(repository.existsById(id)).thenReturn(false);

        boolean result = service.unregisterConfig(id);

        assertFalse(result);
        verify(repository).existsById(id);
        verify(repository, never()).deleteById(any(String.class));
        verify(registry, never()).unregister(any(String.class));
    }

    // === 9. ConnectorConfigService findAll - 返回所有配置 ===

    @Test
    @DisplayName("findAll: 返回所有配置")
    void findAllReturnsAllConfigs() {
        ConnectorConfig config1 = new ConnectorConfig();
        config1.setId("c1");
        config1.setType("http_psp");
        config1.setActive(true);

        ConnectorConfig config2 = new ConnectorConfig();
        config2.setId("c2");
        config2.setType("wechat");
        config2.setActive(false);

        when(repository.findAll()).thenReturn(List.of(config1, config2));

        List<ConnectorConfig> result = service.findAll();

        assertEquals(2, result.size());
        assertEquals("c1", result.get(0).getId());
        assertEquals("c2", result.get(1).getId());
        verify(repository).findAll();
    }

    // === 10. ConnectorConfigService findActive - 只返回 active=true ===

    @Test
    @DisplayName("findActive: 只返回 active=true 的配置")
    void findActiveReturnsOnlyActiveConfigs() {
        ConnectorConfig active1 = new ConnectorConfig();
        active1.setId("active-1");
        active1.setType("http_psp");
        active1.setActive(true);

        ConnectorConfig active2 = new ConnectorConfig();
        active2.setId("active-2");
        active2.setType("wechat");
        active2.setActive(true);

        when(repository.findByActiveTrue()).thenReturn(List.of(active1, active2));

        List<ConnectorConfig> result = service.findActive();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(ConnectorConfig::isActive));
        verify(repository).findByActiveTrue();
    }

    // === 11. ConnectorFactory create - http_psp 类型 ===

    @Test
    @DisplayName("ConnectorFactory create: http_psp 类型创建 DynamicHttpPspConnector")
    void factoryCreateHttpPsp() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("factory-psp");
        config.setType("http_psp");
        config.setDisplayName("Factory PSP");
        config.setBaseUrl("https://api.example.com");
        config.setApiKeyEnv("PSP_X_API_KEY");
        config.setCurrencies("USD,EUR");
        config.setFeeBps(100);

        PaymentConnector connector = factory.create(config);

        assertInstanceOf(DynamicHttpPspConnector.class, connector);
        assertEquals("factory-psp", connector.getId());
        assertEquals("http_psp", connector.getType());
        assertEquals("Factory PSP", connector.getDisplayName());
        assertEquals(100, connector.feeBasisPoints());
    }

    // === 12. ConnectorFactory create - wechat 类型 ===

    @Test
    @DisplayName("ConnectorFactory create: wechat 类型创建 WeChatPayConnector")
    void factoryCreateWechat() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("factory-wechat");
        config.setType("wechat");
        config.setDisplayName("Factory WeChat");
        config.setAppId("wx1234567890");
        config.setMchId("1234567890");
        config.setApiKeyEnv("WECHAT_API_KEY");
        config.setCurrencies("CNY");
        config.setFeeBps(60);

        PaymentConnector connector = factory.create(config);

        assertInstanceOf(WeChatPayConnector.class, connector);
        assertEquals("wechat", connector.getId());
        assertTrue(connector.isActive());
    }

    // === 13. ConnectorFactory create - alipay 类型 ===

    @Test
    @DisplayName("ConnectorFactory create: alipay 类型创建 AlipayConnector")
    void factoryCreateAlipay() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("factory-alipay");
        config.setType("alipay");
        config.setDisplayName("Factory Alipay");
        config.setAppId("2021001234567890");
        config.setMerchantPrivateKey("ALIPAY_PRIVATE_KEY_ENV");
        config.setAlipayPublicKey("ALIPAY_PUBLIC_KEY_ENV");
        config.setCurrencies("CNY");
        config.setFeeBps(38);

        PaymentConnector connector = factory.create(config);

        assertInstanceOf(AlipayConnector.class, connector);
        assertEquals("alipay", connector.getId());
        assertTrue(connector.isActive());
    }

    // === 14. ConnectorFactory create - 不支持的类型抛异常 ===

    @Test
    @DisplayName("ConnectorFactory create: 不支持的类型抛 IllegalArgumentException")
    void factoryCreateUnsupportedTypeThrowsException() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("factory-unsupported");
        config.setType("stripe");

        assertThrows(IllegalArgumentException.class, () -> factory.create(config));
    }

    // === 15. 启动恢复 - 从数据库加载并注册到 Registry ===

    @Test
    @DisplayName("启动恢复: 从数据库加载 active 配置并注册到 Registry")
    void restoreOnStartupLoadsAndRegisters() {
        ConnectorConfig activeConfig1 = new ConnectorConfig();
        activeConfig1.setId("restore-psp");
        activeConfig1.setType("http_psp");
        activeConfig1.setDisplayName("Restore PSP");
        activeConfig1.setBaseUrl("https://api.pspx.example");
        activeConfig1.setApiKeyEnv("PSP_X_API_KEY");
        activeConfig1.setCurrencies("USD");
        activeConfig1.setFeeBps(100);
        activeConfig1.setActive(true);

        ConnectorConfig activeConfig2 = new ConnectorConfig();
        activeConfig2.setId("restore-wechat");
        activeConfig2.setType("wechat");
        activeConfig2.setDisplayName("Restore WeChat");
        activeConfig2.setAppId("wx1234567890");
        activeConfig2.setMchId("1234567890");
        activeConfig2.setActive(true);

        when(repository.findByActiveTrue()).thenReturn(List.of(activeConfig1, activeConfig2));

        service.restoreOnStartup();

        verify(repository).findByActiveTrue();
        // 注册了 2 个 connector（1 个 http_psp + 1 个 wechat）
        verify(registry, times(2)).register(any(PaymentConnector.class));
    }

    // === 额外: 启动恢复 - 无 active 配置时不注册 ===

    @Test
    @DisplayName("启动恢复: 无 active 配置时不注册任何 Connector")
    void restoreOnStartupNoActiveConfigs() {
        when(repository.findByActiveTrue()).thenReturn(List.of());

        service.restoreOnStartup();

        verify(repository).findByActiveTrue();
        verify(registry, never()).register(any(PaymentConnector.class));
    }

    // === 额外: registerConfig - http_psp baseUrl 不在白名单时抛异常 ===

    @Test
    @DisplayName("registerConfig: http_psp baseUrl 不在白名单时抛 IllegalArgumentException")
    void registerConfigHttpPspBaseUrlNotAllowed() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("psp-bad");
        config.setType("http_psp");
        config.setBaseUrl("http://169.254.169.254");  // 云元数据地址，SSRF
        config.setActive(true);

        assertThrows(IllegalArgumentException.class, () -> service.registerConfig(config));
        verify(repository, never()).save(any(ConnectorConfig.class));
        verify(registry, never()).register(any(PaymentConnector.class));
    }

    // === 额外: registerConfig - http_psp apiKeyEnv 不在白名单时抛异常 ===

    @Test
    @DisplayName("registerConfig: http_psp apiKeyEnv 不在白名单时抛 IllegalArgumentException")
    void registerConfigHttpPspApiKeyEnvNotAllowed() {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("psp-bad-env");
        config.setType("http_psp");
        config.setBaseUrl("https://api.pspx.example");
        config.setApiKeyEnv("AWS_SECRET_ACCESS_KEY");  // 不在白名单
        config.setActive(true);

        assertThrows(IllegalArgumentException.class, () -> service.registerConfig(config));
        verify(repository, never()).save(any(ConnectorConfig.class));
        verify(registry, never()).register(any(PaymentConnector.class));
    }
}