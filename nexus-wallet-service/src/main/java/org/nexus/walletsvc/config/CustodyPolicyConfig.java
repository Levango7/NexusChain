package org.nexus.walletsvc.config;

import org.nexus.walletsvc.custody.CustodyPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 托管策略装配（2026-10-27）。
 *
 * <p><b>修的是什么</b>：{@link CustodyPolicy} 原先是普通 POJO，全仓没有
 * {@code @Component} / {@code @Bean} / {@code @ConfigurationProperties}，
 * 容器里永远没有策略 bean，{@code DefaultCustodyService.rebalance()} 只能走
 * "Rebalance skipped: no custody policy configured" —— 自动再平衡实际不可达。</p>
 *
 * <p><b>为什么用显式开关</b>：与 dry-run 的收口方式一致——默认关闭，必须
 * {@code nexus.custody.enabled=true} 才装配。默认不装配时保持现有行为
 * （rebalance 安全跳过并告警），不会让未配置托管策略的环境突然开始动钱。</p>
 *
 * <p>启用后由 {@link CustodyPolicy#afterPropertiesSet()} 做完整性校验：
 * 缺阈值或冷钱包真实地址都会启动失败。</p>
 */
@Configuration
public class CustodyPolicyConfig {

    @Bean
    @ConditionalOnProperty(prefix = "nexus.custody", name = "enabled", havingValue = "true")
    @ConfigurationProperties(prefix = "nexus.custody")
    public CustodyPolicy custodyPolicy() {
        return new CustodyPolicy();
    }
}
