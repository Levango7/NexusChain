package org.nexus.analytics.monitoring;

/**
 * 链上实时监控服务。
 *
 * <p>负责持续采集节点健康、区块传播、内存池状态等运行时指标，
 * 并将异常情况转化为告警事件。
 */
public interface ChainMonitorService {

    /**
     * 监控节点健康状态（在线 / 同步进度 / 对等节点数）。
     */
    void monitorNodeHealth();

    /**
     * 监控区块传播延迟（出块到全网广播的时延分布）。
     */
    void monitorBlockPropagation();

    /**
     * 监控内存池状态（待打包交易数、拥堵度、费率分布）。
     */
    void monitorMempool();

    /**
     * 启动全部监控任务。
     */
    void startAll();

    /**
     * 停止全部监控任务。
     */
    void stopAll();
}