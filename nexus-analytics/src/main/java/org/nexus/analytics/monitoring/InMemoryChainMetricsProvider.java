package org.nexus.analytics.monitoring;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于内存的链上指标采集实现。
 *
 * <p>生产环境应替换为经 nexus-core RPC / 节点指标接口拉取的实现；
 * 当前实现允许外部通过 setter 注入指标快照，用于监控与测试。
 * 未注入时返回一组健康默认值（在线、无同步滞后、内存池空闲）。
 */
@Component
public class InMemoryChainMetricsProvider implements ChainMetricsProvider {

    private final AtomicReference<Map<String, Object>> nodeHealth = new AtomicReference<>(defaultNodeHealth());
    private final AtomicReference<Map<String, Object>> blockPropagation = new AtomicReference<>(defaultBlockPropagation());
    private final AtomicReference<Map<String, Object>> mempool = new AtomicReference<>(defaultMempool());

    @Override
    public Map<String, Object> collectNodeHealth() {
        return new HashMap<>(nodeHealth.get());
    }

    @Override
    public Map<String, Object> collectBlockPropagation() {
        return new HashMap<>(blockPropagation.get());
    }

    @Override
    public Map<String, Object> collectMempool() {
        return new HashMap<>(mempool.get());
    }

    /** 注入节点健康指标快照。 */
    public void setNodeHealth(Map<String, Object> metrics) {
        if (metrics != null) {
            nodeHealth.set(metrics);
        }
    }

    /** 注入区块传播指标快照。 */
    public void setBlockPropagation(Map<String, Object> metrics) {
        if (metrics != null) {
            blockPropagation.set(metrics);
        }
    }

    /** 注入内存池指标快照。 */
    public void setMempool(Map<String, Object> metrics) {
        if (metrics != null) {
            mempool.set(metrics);
        }
    }

    private Map<String, Object> defaultNodeHealth() {
        Map<String, Object> m = new HashMap<>();
        m.put("online", true);
        m.put("syncLag", 0);
        m.put("peerCount", 12);
        return m;
    }

    private Map<String, Object> defaultBlockPropagation() {
        Map<String, Object> m = new HashMap<>();
        m.put("propagationP95Ms", 120L);
        m.put("lastBlockHeight", 0L);
        return m;
    }

    private Map<String, Object> defaultMempool() {
        Map<String, Object> m = new HashMap<>();
        m.put("pendingCount", 0);
        m.put("feeP50", 0.0);
        return m;
    }
}
