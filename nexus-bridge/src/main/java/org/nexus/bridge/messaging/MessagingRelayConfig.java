package org.nexus.bridge.messaging;

import org.nexus.bridge.adapter.ChainAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 *       formatter / relayer / executor / status 四类 Bean；</li>
 *   <li>默认（缺省 / false）时**一个 Bean 都不创建**——既有部署行为零变化；</li>
 *   <li>store 沿用 {@link MessageStoreConfig} 的选择（memory / jpa），不重复造。</li>
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
     * 消息配置（多签阈值/超时来自外部属性；默认 1 签——切片 1 的装配语义是
     * "本节点自己签自己验"，真实多签治理阈值应由部署方显式配置）。
     */
    @Bean
    @ConditionalOnProperty(name = RELAY_ENABLED_PROPERTY, havingValue = "true")
    public MessageConfig bridgeMessageConfig(
            @Value("${nexus.bridge.messaging.required-signatures:1}") int requiredSignatures,
            @Value("${nexus.bridge.messaging.message-timeout:3600}") long messageTimeout) {
        MessageConfig config = new MessageConfig();
        if (requiredSignatures > 0) {
            config.setRequiredSignatures(requiredSignatures);
        }
        if (messageTimeout > 0) {
            config.setMessageTimeout(messageTimeout);
        }
        config.setEnabled(true);
        return config;
    }

    @Bean
    @ConditionalOnProperty(name = RELAY_ENABLED_PROPERTY, havingValue = "true")
    public MessageRelayer bridgeMessageRelayer(MessageFormatter formatter,
                                               MessageStore store,
                                               MessageConfig config) {
        return new MessageRelayer(formatter, store, config);
    }

    /**
     * 目标链适配器（可缺失：切片 1 未接真实链；构造器容忍空 Map）。
     */
    @Bean
    @ConditionalOnProperty(name = RELAY_ENABLED_PROPERTY, havingValue = "true")
    public MessageExecutor bridgeMessageExecutor(
            MessageFormatter formatter,
            MessageStore store,
            MessageConfig config,
            @Autowired(required = false) Map<String, ChainAdapter> targetAdapters) {
        return new MessageExecutor(formatter, store, config, targetAdapters);
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
