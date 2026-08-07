package org.nexus.sdk.client.feign.fallback;

import org.nexus.sdk.client.feign.BridgeServiceFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Map;

/**
 * BridgeServiceFeignClient 的 FallbackFactory 基类。
 *
 * <p>本类在 nexus-sdk 定义（Phase 3 fallback 绑定方案 G，设计文档 §4.4.1 / §4.4.4），
 * 消费方模块继承本类并按服务定制降级语义：
 * <ul>
 *   <li>gateway：{@code org.nexus.gateway.fallback.GatewayBridgeServiceFallbackFactory}
 *       （跨链桥降级，复用 BridgeServiceFallback，跨链操作返回 FAILED Map）</li>
 * </ul></p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>本类为具体类（非接口），提供默认 fail-closed 降级实现。Spring Cloud OpenFeign
 *       在 {@code @FeignClient(fallbackFactory = ...)} 验证阶段会实例化本类并调用
 *       {@code create(Throwable)} 校验返回类型，因此必须是可实例化的具体类</li>
 *   <li>消费方通过 {@code @Component} 注册子类 Bean，Spring 容器按类型注入子类，
 *       运行时使用消费方定制的降级语义；本类默认实现仅在无子类 Bean 时兜底</li>
 *   <li>{@code FallbackFactory<T>} 比 {@code fallback = T.class} 更灵活：可在
 *       {@code create(Throwable cause)} 中获取触发降级的异常，区分限流/熔断/服务不可用</li>
 * </ul></p>
 *
 * @see BridgeServiceFeignClient
 * @see org.springframework.cloud.openfeign.FallbackFactory
 */
public class BridgeServiceFallbackFactory implements FallbackFactory<BridgeServiceFeignClient> {

    /**
     * 默认 fail-closed 降级实现：所有操作返回 null，调用方按失败处理。
     *
     * <p>消费方应覆盖本方法以注入定制降级逻辑（如返回包含 status=FAILED 的 Map）。</p>
     *
     * @param cause 触发降级的异常（限流/熔断/服务不可用）
     * @return fail-closed 的 BridgeServiceFeignClient 代理
     */
    @Override
    public BridgeServiceFeignClient create(Throwable cause) {
        return new BridgeServiceFeignClient() {
            @Override
            public Map<String, Object> lock(Map<String, Object> request) {
                return null;
            }

            @Override
            public Map<String, Object> mint(Map<String, Object> request) {
                return null;
            }

            @Override
            public Map<String, Object> burn(Map<String, Object> request) {
                return null;
            }

            @Override
            public Map<String, Object> unlock(Map<String, Object> request) {
                return null;
            }

            @Override
            public Map<String, Object> getTransaction(String txId) {
                return null;
            }

            @Override
            public Map<String, Object> getBySourceHash(String sourceTxHash) {
                return null;
            }

            @Override
            public Map<String, Object> status() {
                return null;
            }
        };
    }
}
