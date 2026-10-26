package org.nexus.walletsvc.custody;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.walletsvc.config.CustodyPolicyConfig;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 托管策略装配测试（2026-10-27）。
 *
 * <p>三条边界：默认不装配（行为不变）、显式启用后正确绑定、
 * 启用但配置不完整时启动失败（不允许静默空转）。</p>
 */
class CustodyPolicyConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // 显式带上 ConfigurationProperties 绑定后处理器：
            // 裸 ApplicationContextRunner 只加载用户配置，不会绑定 nexus.custody.*
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(CustodyPolicyConfig.class);

    @Test
    @DisplayName("默认不装配：未显式启用时没有 CustodyPolicy bean")
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(CustodyPolicy.class));
    }

    @Test
    @DisplayName("显式启用时按 nexus.custody.* 绑定")
    void bindsWhenEnabled() {
        runner.withPropertyValues(
                        "nexus.custody.enabled=true",
                        "nexus.custody.hot-wallet-cap=1000",
                        "nexus.custody.auto-sweep-threshold=800",
                        "nexus.custody.hot-wallet-floor=200",
                        "nexus.custody.sweep-target=COLD",
                        "nexus.custody.cold-wallet-address=nexus1coldwalletaddress")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    CustodyPolicy policy = ctx.getBean(CustodyPolicy.class);
                    assertThat(policy.getHotWalletCap()).isEqualByComparingTo("1000");
                    assertThat(policy.getAutoSweepThreshold()).isEqualByComparingTo("800");
                    assertThat(policy.getHotWalletFloor()).isEqualByComparingTo("200");
                    assertThat(policy.getSweepTarget().name()).isEqualTo("COLD");
                    assertThat(policy.getColdWalletAddress()).isEqualTo("nexus1coldwalletaddress");
                });
    }

    @Test
    @DisplayName("启用但缺阈值 → 启动失败（否则 rebalance 静默空转）")
    void failsFastWithoutThresholds() {
        runner.withPropertyValues(
                        "nexus.custody.enabled=true",
                        "nexus.custody.cold-wallet-address=nexus1coldwalletaddress")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("启用但缺冷钱包真实地址 → 启动失败")
    void failsFastWithoutColdAddress() {
        runner.withPropertyValues(
                        "nexus.custody.enabled=true",
                        "nexus.custody.auto-sweep-threshold=800")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
