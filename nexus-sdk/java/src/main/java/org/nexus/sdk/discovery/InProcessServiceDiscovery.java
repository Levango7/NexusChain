package org.nexus.sdk.discovery;

import java.util.Collections;
import java.util.List;

/**
 * {@link ServiceDiscovery} 的进程内骨架实现。
 *
 * <p>P2 方向5「签名服务独立部署 PoC」引入。当前阶段 {@link #isEnabled()} 返回 {@code false}，
 * 表示不启用服务发现，调用方走进程内 composite build 直连。</p>
 *
 * <p>未来完整阶段将提供 {@code NacosServiceDiscovery} 实现替换本骨架，
 * 通过 Nacos 客户端订阅服务实例列表。</p>
 */
public class InProcessServiceDiscovery implements ServiceDiscovery {

    /**
     * 默认构造器。
     */
    public InProcessServiceDiscovery() {
    }

    @Override
    public ServiceInstance discoverOne(String serviceName) {
        // PoC 骨架：服务发现未启用，返回 null
        return null;
    }

    @Override
    public List<ServiceInstance> discoverAll(String serviceName) {
        // PoC 骨架：服务发现未启用，返回空列表
        return Collections.emptyList();
    }

    @Override
    public boolean isEnabled() {
        // PoC 骨架：进程内直连，不启用服务发现
        return false;
    }
}