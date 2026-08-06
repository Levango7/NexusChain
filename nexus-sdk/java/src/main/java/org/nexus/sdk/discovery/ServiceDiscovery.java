package org.nexus.sdk.discovery;

import java.util.List;

/**
 * 服务发现接口骨架。
 *
 * <p>P2 方向5「签名服务独立部署 PoC」引入。定义跨服务调用前的服务实例发现边界。
 * 当前阶段（PoC）使用 {@link InProcessServiceDiscovery} 返回固定占位实例，
 * 未来完整阶段切换为 Nacos 实现（{@code NacosServiceDiscovery}）。</p>
 *
 * <p>本接口仅定义边界，不绑定任何具体服务发现框架（Nacos / Eureka / Consul 等），
 * 实现由部署环境决定。</p>
 */
public interface ServiceDiscovery {

    /**
     * 根据服务名发现一个可用实例。
     *
     * @param serviceName 服务名（如 "nexus-signing-service"）
     * @return 服务实例信息；未找到返回 {@code null}
     */
    ServiceInstance discoverOne(String serviceName);

    /**
     * 根据服务名发现全部可用实例。
     *
     * @param serviceName 服务名
     * @return 服务实例列表；未找到返回空列表
     */
    List<ServiceInstance> discoverAll(String serviceName);

    /**
     * 服务是否启用服务发现（即调用方是否应通过本接口解析地址）。
     *
     * <p>当前 PoC 阶段返回 {@code false}，表示进程内直连，
     * 不经过服务发现。未来 http 模式下返回 {@code true}。</p>
     *
     * @return {@code true} 表示启用服务发现
     */
    boolean isEnabled();
}