package org.nexus.gateway.transaction;

import java.util.function.Consumer;

/**
 * SAGA 步骤定义 — 包含正向执行动作和逆向补偿动作。
 *
 * <p>每个 SAGA 步骤由两部分组成：</p>
 * <ul>
 *   <li>{@code action} — 正向执行动作（如创建订单、扣减库存、转账）</li>
 *   <li>{@code compensation} — 逆向补偿动作（如取消订单、恢复库存、退回转账）</li>
 * </ul>
 *
 * <p>补偿动作必须幂等：同一 {@code transactionId} 重复调用不应产生副作用。</p>
 */
public class SagaStep {

    /** 步骤名称 */
    private final String stepName;

    /** 正向执行动作 */
    private final Consumer<TransactionContext> action;

    /** 逆向补偿动作 */
    private final Consumer<TransactionContext> compensation;

    public SagaStep(String stepName,
                    Consumer<TransactionContext> action,
                    Consumer<TransactionContext> compensation) {
        this.stepName = stepName;
        this.action = action;
        this.compensation = compensation;
    }

    /**
     * 执行正向动作。
     *
     * @param ctx 事务上下文
     */
    public void execute(TransactionContext ctx) {
        action.accept(ctx);
    }

    /**
     * 执行补偿动作。
     *
     * @param ctx 事务上下文
     */
    public void compensate(TransactionContext ctx) {
        compensation.accept(ctx);
    }

    public String getStepName() { return stepName; }
}