package org.nexus.gateway.resilience;

import io.github.resilience4j.ratelimiter.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConnectorRateLimiter} 语义守卫（2026-11-17）。
 *
 * <p>核心断三件事：</p>
 * <ol>
 *   <li><b>默认关闭 = 零行为变更</b>：enabled=false（默认）时 tryAcquire 恒 true，
 *       且不创建 limiter 实例（零开销路径）；</li>
 *   <li><b>开启后按配额节流</b>：limit=N/N s 时第 N+1 次（同周期内）被拒；</li>
 *   <li><b>分桶隔离</b>：A 被拒不影响 B 的配额。</li>
 * </ol>
 */
class ConnectorRateLimiterTest {

    private ConnectorRateLimiterConfig enabledConfig(int limit, Duration refresh) {
        ConnectorRateLimiterConfig cfg = new ConnectorRateLimiterConfig();
        cfg.setEnabled(true);
        cfg.getDefaultConfig().setLimitForPeriod(limit);
        cfg.getDefaultConfig().setLimitRefreshPeriod(refresh);
        return cfg;
    }

    @Test
    @DisplayName("默认配置：enabled=false，tryAcquire 恒放行且不创建 limiter")
    void disabledByDefaultAlwaysAllows() {
        ConnectorRateLimiterConfig cfg = new ConnectorRateLimiterConfig();
        assertFalse(cfg.isEnabled(), "默认必须关闭——支付主路径行为变更必须显式 opt-in");
        ConnectorRateLimiter rl = new ConnectorRateLimiter(cfg);
        for (int i = 0; i < 1000; i++) {
            assertTrue(rl.tryAcquire("psp-x", "http_psp"));
        }
        assertFalse(rl.isTracked("psp-x"), "关闭态不得创建 limiter 实例（零开销路径）");
    }

    @Test
    @DisplayName("开启后：配额内放行，超出配额拒绝；下一刷新周期恢复")
    void enforcesLimitWithinPeriodAndRecovers() {
        ConnectorRateLimiter rl = new ConnectorRateLimiter(enabledConfig(3, Duration.ofSeconds(1)));

        assertTrue(rl.tryAcquire("psp-a", "http_psp"));
        assertTrue(rl.tryAcquire("psp-a", "http_psp"));
        assertTrue(rl.tryAcquire("psp-a", "http_psp"));
        assertFalse(rl.tryAcquire("psp-a", "http_psp"), "同周期第 4 次应被拒");
        assertFalse(rl.tryAcquire("psp-a", "http_psp"));

        // 刷新周期过后恢复（AtomicRateLimiter 按纳秒时间驱动，直接等待即可）
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(rl.tryAcquire("psp-a", "http_psp"), "新周期应恢复配额");
    }

    @Test
    @DisplayName("分桶隔离：psp-a 配额耗尽不影响 psp-b")
    void bucketsAreIndependent() {
        ConnectorRateLimiter rl = new ConnectorRateLimiter(enabledConfig(1, Duration.ofSeconds(1)));

        assertTrue(rl.tryAcquire("psp-a", "http_psp"));
        assertFalse(rl.tryAcquire("psp-a", "http_psp"), "psp-a 配额应已耗尽");
        assertTrue(rl.tryAcquire("psp-b", "http_psp"), "psp-b 应有独立配额");
    }

    @Test
    @DisplayName("类型覆盖：instances 里配置的类型用覆盖值，未配置的回退 default")
    void typeOverrideWinsOverDefault() {
        ConnectorRateLimiterConfig cfg = enabledConfig(10, Duration.ofSeconds(1));
        ConnectorRateLimiterConfig.RateLimitProperties stripe = new ConnectorRateLimiterConfig.RateLimitProperties();
        stripe.setLimitForPeriod(2);
        cfg.getInstances().put("stripe", stripe);
        ConnectorRateLimiter rl = new ConnectorRateLimiter(cfg);

        RateLimiter stripeLimiter = rl.getOrCreate("psp-s", "stripe");
        assertEquals(2, stripeLimiter.getRateLimiterConfig().getLimitForPeriod(),
                "stripe 应使用 instances 覆盖值");
        RateLimiter otherLimiter = rl.getOrCreate("psp-o", "chain");
        assertEquals(10, otherLimiter.getRateLimiterConfig().getLimitForPeriod(),
                "未覆盖类型应回退 default");
    }

    @Test
    @DisplayName("timeout=0 语义：拒绝立即返回，不阻塞调用线程")
    void rejectionIsImmediateNotBlocking() {
        ConnectorRateLimiter rl = new ConnectorRateLimiter(enabledConfig(1, Duration.ofSeconds(60)));

        assertTrue(rl.tryAcquire("psp-a", "http_psp"));
        long t0 = System.nanoTime();
        assertFalse(rl.tryAcquire("psp-a", "http_psp"));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertTrue(elapsedMs < 100, "拒绝必须立即返回（实测 " + elapsedMs + "ms），"
                + "支付主路径不允许被限流器阻塞");
    }
}
