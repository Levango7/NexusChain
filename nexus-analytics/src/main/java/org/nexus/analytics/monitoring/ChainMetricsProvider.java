package org.nexus.analytics.monitoring;

import java.util.Map;

/**
 * 链上运行时指标采集端口。
 *
 * <p>供 {@link ChainMonitorService} 采集节点健康、区块传播、内存池指标。
 * 生产实现应通过 nexus-core RPC / JMX / 节点指标接口拉取；
 * 当前默认实现为可注入的内存指标源。
 */
public interface ChainMetricsProvider {

    /**
     * 采集节点健康指标（如 online / syncLag / peerCount）。
     *
     * @return 指标键值对
     */
    Map<String, Object> collectNodeHealth();

    /**
     * 采集区块传播指标（如 propagationP95Ms / lastBlockHeight）。
     *
     * @return 指标键值对
     */
    Map<String, Object> collectBlockPropagation();

    /**
     * 采集内存池指标（如 pendingCount / feeP50）。
     *
     * @return 指标键值对
     */
    Map<String, Object> collectMempool();
}
