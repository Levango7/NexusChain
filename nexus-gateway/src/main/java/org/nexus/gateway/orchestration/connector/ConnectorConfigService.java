package org.nexus.gateway.orchestration.connector;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Connector 配置服务 — 提供动态注册 Connector 的持久化 CRUD 和启动恢复能力。
 *
 * <p>核心职责：</p>
 * <ol>
 *   <li><b>启动恢复</b>：{@link #restoreOnStartup()} 在 {@code @PostConstruct} 时从数据库
 *       加载所有 {@code active=true} 的配置，使用 {@link ConnectorFactory} 创建 Connector
 *       并注册到 {@link ConnectorRegistry}，实现重启后自动恢复。</li>
 *   <li><b>注册新 Connector</b>：{@link #registerConfig(ConnectorConfig)} 保存配置到数据库 +
 *       创建 Connector + 注册到 Registry。对 {@code http_psp} 类型使用 {@link PspTargetPolicy}
 *       校验 baseUrl 和 apiKeyEnv；对 {@code wechat/alipay} 类型跳过校验（受信任的内置类型）。</li>
 *   <li><b>注销 Connector</b>：{@link #unregisterConfig(String)} 从数据库删除 + 从 Registry 注销。</li>
 *   <li><b>查询</b>：{@link #findAll()} 和 {@link #findActive()} 提供配置列表查询。</li>
 * </ol>
 */
@Service
public class ConnectorConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorConfigService.class);

    private final ConnectorConfigRepository repository;
    private final ConnectorRegistry registry;
    private final ConnectorFactory factory;
    private final PspTargetPolicy pspTargetPolicy;

    public ConnectorConfigService(ConnectorConfigRepository repository,
                                   ConnectorRegistry registry,
                                   ConnectorFactory factory,
                                   PspTargetPolicy pspTargetPolicy) {
        this.repository = repository;
        this.registry = registry;
        this.factory = factory;
        this.pspTargetPolicy = pspTargetPolicy;
    }

    /**
     * 启动恢复：从数据库加载所有 active=true 的配置，创建 Connector 并注册到 Registry。
     *
     * <p>此方法在 Spring Bean 初始化后自动调用（{@code @PostConstruct}），
     * 确保动态注册的 Connector 在应用重启后自动恢复。</p>
     */
    @PostConstruct
    public void restoreOnStartup() {
        List<ConnectorConfig> activeConfigs = repository.findByActiveTrue();
        if (activeConfigs.isEmpty()) {
            log.info("No active connector configs found in database, skipping restore");
            return;
        }
        log.info("Restoring {} active connector configs from database", activeConfigs.size());
        int restored = 0;
        for (ConnectorConfig config : activeConfigs) {
            try {
                PaymentConnector connector = factory.create(config);
                registry.register(connector);
                restored++;
                log.info("Restored connector: {} (type={})", config.getId(), config.getType());
            } catch (Exception e) {
                log.error("Failed to restore connector {}: {}", config.getId(), e.getMessage(), e);
            }
        }
        log.info("Connector restore complete: {}/{} successfully restored", restored, activeConfigs.size());
    }

    /**
     * 注册新的 Connector 配置。
     *
     * <p>流程：校验 → 保存到数据库 → 创建 Connector → 注册到 Registry。</p>
     *
     * <p>校验规则：</p>
     * <ul>
     *   <li>{@code http_psp} 类型：使用 {@link PspTargetPolicy} 校验 baseUrl 和 apiKeyEnv</li>
     *   <li>{@code wechat/alipay} 类型：跳过 PspTargetPolicy 校验（受信任的内置类型）</li>
     * </ul>
     *
     * @param config 连接器配置
     * @return 保存后的配置（含 createdAt/updatedAt）
     * @throws IllegalArgumentException 校验失败或不支持的类型
     */
    @Transactional
    public ConnectorConfig registerConfig(ConnectorConfig config) {
        // 校验类型
        String type = config.getType();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Connector type must not be null or blank");
        }

        // 对 http_psp 类型执行 PspTargetPolicy 校验
        if ("http_psp".equals(type)) {
            if (!pspTargetPolicy.isAllowedBaseUrl(config.getBaseUrl())) {
                throw new IllegalArgumentException("base_url not allowed by PspTargetPolicy: " + config.getBaseUrl());
            }
            if (config.getApiKeyEnv() != null && !config.getApiKeyEnv().isBlank()
                    && !pspTargetPolicy.isAllowedApiKeyEnv(config.getApiKeyEnv())) {
                throw new IllegalArgumentException("api_key_env not allowed by PspTargetPolicy: " + config.getApiKeyEnv());
            }
        }

        // 保存到数据库
        ConnectorConfig saved = repository.save(config);

        // 创建 Connector 并注册到 Registry
        PaymentConnector connector = factory.create(saved);
        registry.register(connector);

        log.info("Registered new connector config: {} (type={})", saved.getId(), saved.getType());
        return saved;
    }

    /**
     * 注销 Connector 配置：从数据库删除 + 从 Registry 注销。
     *
     * @param id connector ID
     * @return true 表示成功注销，false 表示配置不存在
     */
    @Transactional
    public boolean unregisterConfig(String id) {
        if (!repository.existsById(id)) {
            log.warn("Cannot unregister connector {}: config not found in database", id);
            return false;
        }
        repository.deleteById(id);
        registry.unregister(id);
        log.info("Unregistered connector config: {}", id);
        return true;
    }

    /**
     * 查询所有 Connector 配置。
     *
     * @return 所有配置列表
     */
    public List<ConnectorConfig> findAll() {
        return repository.findAll();
    }

    /**
     * 查询所有激活的 Connector 配置。
     *
     * @return 激活配置列表（active=true）
     */
    public List<ConnectorConfig> findActive() {
        return repository.findByActiveTrue();
    }

    /**
     * 按 ID 查找 Connector 配置。
     *
     * @param id connector ID
     * @return 配置 Optional
     */
    public Optional<ConnectorConfig> findById(String id) {
        return repository.findById(id);
    }
}