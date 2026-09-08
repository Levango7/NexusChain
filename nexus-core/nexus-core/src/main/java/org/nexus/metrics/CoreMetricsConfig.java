package org.nexus.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.nexus.db.StateDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 链核心 Prometheus 指标（PLAN-002 后续，2026-09-07）。
 *
 * <p>暴露到 {@code /actuator/prometheus}（依赖 build.gradle 的
 * spring-boot-starter-actuator + micrometer-registry-prometheus，
 * 端点开关见 application.properties management.endpoints）。</p>
 *
 * <h2>指标</h2>
 * <ul>
 *   <li>{@code nexuschain_block_height}（gauge）：本节点最佳块高
 *       （{@code stateDB.getBestBlock().nHeight}）。Prometheus 抓取时
 *       lazy 回调读取——不引入后台线程、零额外开销。</li>
 * </ul>
 *
 * <h2>消费方</h2>
 * <p>{@code deploy/k8s/50-prometheus-rules.yaml} 的
 * {@code BlockchainCoreStalled} 告警：5 分钟块高无增长即链停摆——
 * 替代此前 {@code MpcEngineCrashLooping} 的间接信号（5min 延迟近似）。</p>
 *
 * <h2>线程安全</h2>
 * <p>Gauge 供值函数在 Prometheus 抓取线程内执行；{@code StateDB.getBestBlock()}
 * 本身被共识/同步线程并发调用（既有契约），读取单字段无状态突变。</p>
 */
@Configuration
public class CoreMetricsConfig {

    private static final Logger logger = LoggerFactory.getLogger(CoreMetricsConfig.class);

    /**
     * 块高 gauge（lazy 供值——注册回调，抓取时才读 StateDB）。
     *
     * @param meterRegistry Micrometer 注册表（Boot 自动装配）
     * @param stateDB       链状态库（getBestBlock 是既有权威来源）
     */
    @Bean
    public Gauge blockHeightGauge(MeterRegistry meterRegistry, StateDB stateDB) {
        logger.info("CoreMetricsConfig: registering nexuschain_block_height gauge (lazy read of stateDB.getBestBlock)");
        return Gauge.builder("nexuschain_block_height",
                        () -> {
                            try {
                                return (double) stateDB.getBestBlock().nHeight;
                            } catch (Exception e) {
                                // 状态库异常（如 H2/PG 未就绪）——返回 NaN 而非让抓取失败
                                return Double.NaN;
                            }
                        })
                .description("Best block height of the local chain (stateDB.getBestBlock().nHeight)")
                .register(meterRegistry);
    }
}
