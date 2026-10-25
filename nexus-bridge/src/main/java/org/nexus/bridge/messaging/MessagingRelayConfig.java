package org.nexus.bridge.messaging;

import org.nexus.bridge.adapter.ChainAdapter;
import org.nexus.bridge.adapter.SimulatedChainAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桥 messaging 中继/执行**装配开关**（PLAN-037 切片 1）。
 *
 * <p>背景（roadmap #33 欠账）：{@link MessageRelayer} / {@link MessageExecutor}
 * 自 1.9.2 起就存在且有完整单测，但**从未装配进生产流**——只有测试代码
 * `new` 它们。本配置类提供默认关闭的装配插座：</p>
 *
 * <ul>
 *   <li>{@code nexus.bridge.messaging.relay-enabled=true} 时才创建
 *       formatter / relayer / executor / worker / status Bean；</li>
 *   <li>默认（缺省 / false）时**一个 Bean 都不创建**——既有部署行为零变化；</li>
 *   <li>store 沿用 {@link MessageStoreConfig} 的选择（memory / jpa），不重复造；</li>
 *   <li>消息配置复用既有 {@link MessageConfig} Bean（其自带
 *       {@code @ConfigurationProperties("nexus.bridge.messaging")} 绑定，bean 名
 *       {@code messageConfig}）。<b>2026-10-26 修复</b>：本类曾自建
 *       MessageConfig Bean，与它类型重复 ⇒ 完整上下文在
 *       {@code relay-enabled=true} 时启动即 {@code NoUniqueBeanDefinitionException}
 *       （此前无全上下文用例打开该开关，一路漏检）；由新增全上下文集成测试
 *       {@code BridgeMessagingEndpointIntegrationTest} 发现并修正。</li>
 * </ul>
 *
 * <p><b>切片 1 边界（不夸大）</b>：只做"通电"—— Bean 生命周期与就绪观测；
 * 不接任何真实外部链适配器（{@code targetAdapters} 为空是<b>预期</b>），
 * 不启动消息循环。真实链路（ChainAdapter 接入 + 定时中继/执行）属切片 2。</p>
 *
 * @since 2.76.0
 */
@Configuration
public class MessagingRelayConfig {

    /** 装配开关（缺省 false = 不装配）。 */
    public static final String RELAY_ENABLED_PROPERTY = "nexus.bridge.messaging.relay-enabled";

    @Bean
    @ConditionalOnProperty(name = RELAY_ENABLED_PROPERTY, havingValue = "true")
    public MessageFormatter bridgeMessageFormatter() {
        return new MessageFormatter();
    }

    /**
     * 消息中继器。
     *
     * <p>配置复用既有 {@code @ConfigurationProperties} Bean（见类注释的修复记录）。
     * {@code required-signatures<=0} 属于非法安全配置：<b>fail-fast</b>（启动即
     * 失败，fail-closed 风格，与 JWTUtil/验证者白名单的启动校验同口径）——
     * 切片 1 的"静默回落默认 2 签"已废除（2026-10-26 复用绑定 Bean 时调整；
     * 静默回落可能掩盖误配）。</p>
     */
    @Bean
    @ConditionalOnProperty(name = RELAY_ENABLED_PROPERTY, havingValue = "true")
    public MessageRelayer bridgeMessageRelayer(MessageFormatter formatter,
                                               MessageStore store,
                                               MessageConfig config) {
        if (config.getRequiredSignatures() <= 0) {
            throw new IllegalStateException(
                    "nexus.bridge.messaging.required-signatures must be positive when "
                            + RELAY_ENABLED_PROPERTY + "=true (got "
                            + config.getRequiredSignatures() + "); fix the configuration");
        }
        return new MessageRelayer(formatter, store, config);
    }

    /**
     * 目标链适配器注册表（PLAN-037 切片 3）：容器适配器 + 模拟链合并。
     *
     * <p><b>键语义</b>：{@link MessageExecutor} 以 {@code message.targetChain} 查
     * 适配器，故注册表键必须是 {@link ChainAdapter#getChainId()}——而不是 Spring
     * 收集 {@code Map<String, ChainAdapter>} 时的 bean 名（切片 1/2 遗留的装配
     * 缺口，本切片修正：否则真实消息永远匹配不到适配器）。</p>
     *
     * <p>合并规则：容器中的适配器（如 {@code EthereumAdapter} 等 {@code @Component}）
     * 按 chainId 归键；{@code nexus.bridge.messaging.simulated-target-chains}
     * （逗号分隔，默认空）列出的模拟链用 {@link SimulatedChainAdapter} 补齐，
     * <b>真实优先、模拟不覆盖同名链</b>。</p>
     */
    @Bean
    @ConditionalOnProperty(name = RELAY_ENABLED_PROPERTY, havingValue = "true")
    public MessageExecutor bridgeMessageExecutor(
            MessageFormatter formatter,
            MessageStore store,
            MessageConfig config,
            @Value("${nexus.bridge.messaging.simulated-target-chains:}") String simulatedTargetChains,
            @Autowired(required = false) Map<String, ChainAdapter> targetAdapters) {
        Map<String, ChainAdapter> registry = new LinkedHashMap<>();
        if (targetAdapters != null) {
            for (ChainAdapter adapter : targetAdapters.values()) {
                if (adapter != null && adapter.getChainId() != null) {
                    registry.put(adapter.getChainId(), adapter);
                }
            }
        }
        if (simulatedTargetChains != null) {
            for (String raw : simulatedTargetChains.split(",")) {
                String chainId = raw.trim();
                if (!chainId.isEmpty() && !registry.containsKey(chainId)) {
                    registry.put(chainId, new SimulatedChainAdapter(chainId));
                }
            }
        }
        return new MessageExecutor(formatter, store, config, registry);
    }

    @Bean
    @ConditionalOnProperty(name = RELAY_ENABLED_PROPERTY, havingValue = "true")
    public BridgeMessagingStatus bridgeMessagingStatus(MessageConfig config,
                                                       MessageStore store,
                                                       MessageExecutor executor) {
        return new BridgeMessagingStatus(
                true,
                config.getRequiredSignatures(),
                config.getMessageTimeout(),
                store == null ? "none" : store.getClass().getSimpleName(),
                executor == null ? 0 : executor.getTargetAdapters().size());
    }

    /**
     * 中继循环（PLAN-037 切片 2）：findPending → 阈值验签 → 可选执行 → 记账。
     *
     * <p>{@code execute-enabled} 默认 false：无真实链适配器时只做验签观察，
     * 不产真实交易——见 {@link MessageRelayWorker} 的边界说明。</p>
     */
    @Bean
    @ConditionalOnProperty(name = RELAY_ENABLED_PROPERTY, havingValue = "true")
    public MessageRelayWorker bridgeMessageRelayWorker(
            MessageStore store,
            MessageRelayer relayer,
            MessageExecutor executor,
            MessageConfig config,
            @Value("${nexus.bridge.messaging.batch-size:50}") int batchSize,
            @Value("${nexus.bridge.messaging.execute-enabled:false}") boolean executeEnabled) {
        return new MessageRelayWorker(store, relayer, executor, config, batchSize, executeEnabled);
    }
}
