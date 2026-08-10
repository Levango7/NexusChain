package org.nexus.gateway.orchestration.routing.ai;

import org.nexus.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 路由自动装配（P4-T4）。
 *
 * <p>当 {@code nexus.routing.ai.enabled=true} 时装配以下 bean：</p>
 * <ul>
 *   <li>{@link MetricsCollector} — 滑动窗口指标收集器（windowSize 来自配置）</li>
 *   <li>{@link AiRoutingStrategy} — AI 路由策略（minSamples 来自配置）</li>
 *   <li>{@link AbTestRouter} — A/B 测试路由器（aiTrafficPercentage 来自配置）</li>
 * </ul>
 *
 * <p>{@link HeuristicRoutingModel} 通过 {@code @Component} 自注册为默认
 * {@link RoutingModel}。当 AI 路由禁用时，本配置类不装配任何 bean，
 * {@code RoutingEngine} 的 {@code AbTestRouter} 注入为 null，回退到纯规则路由。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "nexus.routing.ai", name = "enabled", havingValue = "true")
public class AiRoutingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiRoutingConfiguration.class);

    @Bean
    public MetricsCollector metricsCollector(GatewayConfig gatewayConfig) {
        GatewayConfig.AiMetricsConfig metricsConfig = aiMetricsConfig(gatewayConfig);
        int windowSize = metricsConfig.getWindowSize();
        log.info("AI routing MetricsCollector: windowSize={}", windowSize);
        return new MetricsCollector(windowSize);
    }

    @Bean
    public AiRoutingStrategy aiRoutingStrategy(MetricsCollector metricsCollector,
                                               RoutingModel routingModel,
                                               GatewayConfig gatewayConfig) {
        GatewayConfig.AiMetricsConfig metricsConfig = aiMetricsConfig(gatewayConfig);
        int minSamples = metricsConfig.getMinSamples();
        log.info("AI routing strategy: model={}, minSamples={}", routingModel.modelType(), minSamples);
        return new AiRoutingStrategy(metricsCollector, routingModel, minSamples);
    }

    @Bean
    public AbTestRouter abTestRouter(AiRoutingStrategy aiRoutingStrategy,
                                     GatewayConfig gatewayConfig) {
        GatewayConfig.AiRoutingConfig aiConfig = aiConfig(gatewayConfig);
        GatewayConfig.AbTestConfig abConfig = aiConfig.getAbTest();
        boolean abEnabled = abConfig.isEnabled();
        int percentage = abConfig.getAiTrafficPercentage();
        log.info("AI routing A/B test: enabled={}, aiTrafficPercentage={}%", abEnabled, percentage);
        return new AbTestRouter(aiRoutingStrategy, percentage, abEnabled);
    }

    private static GatewayConfig.AiRoutingConfig aiConfig(GatewayConfig gatewayConfig) {
        GatewayConfig.RoutingConfig routing = gatewayConfig.getRouting();
        if (routing == null || routing.getAi() == null) {
            return new GatewayConfig.AiRoutingConfig();
        }
        return routing.getAi();
    }

    private static GatewayConfig.AiMetricsConfig aiMetricsConfig(GatewayConfig gatewayConfig) {
        GatewayConfig.AiRoutingConfig ai = aiConfig(gatewayConfig);
        if (ai.getMetrics() == null) {
            return new GatewayConfig.AiMetricsConfig();
        }
        return ai.getMetrics();
    }
}