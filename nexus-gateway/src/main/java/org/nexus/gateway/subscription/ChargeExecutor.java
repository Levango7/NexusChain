package org.nexus.gateway.subscription;

import java.math.BigDecimal;

/**
 * 扣款执行器接口（P4-T8 订阅与循环计费引擎）。
 *
 * <p>封装"通过 RoutingEngine 选择最优扣款通道并执行扣款"的逻辑，
 * 供 {@link DefaultSubscriptionService} 调用。抽象为接口便于单元测试
 * mock，避免直接依赖 Spring 容器与外部签名服务。</p>
 */
public interface ChargeExecutor {

    /**
     * 执行一次扣款。
     *
     * @param subscription 订阅实体（提供付款人/收款人地址、币种）
     * @param amount       扣款金额
     * @param description  扣款描述
     * @return 扣款结果
     */
    ChargeResult charge(Subscription subscription, BigDecimal amount, String description);
}