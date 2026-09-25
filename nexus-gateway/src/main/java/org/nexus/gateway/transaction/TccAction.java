package org.nexus.gateway.transaction;

/**
 * TCC 动作接口 — 定义 Try/Confirm/Cancel 三个阶段的契约。
 *
 * <p>实现此接口的参与方需保证三个方法的幂等性：
 * 同一 {@code transactionId} 重复调用不应产生副作用。</p>
 *
 * <p>TCC 流程：</p>
 * <ol>
 *   <li>{@link #tryAction} — 资源预留（如锁定库存、冻结余额）</li>
 *   <li>{@link #confirmAction} — 确认提交（如扣减库存、转移余额）</li>
 *   <li>{@link #cancelAction} — 取消预留（如释放库存、解冻余额）</li>
 * </ol>
 */
public interface TccAction {

    /**
     * Try 阶段：资源预留。
     *
     * <p>预留业务资源，但不做最终提交。必须幂等。</p>
     *
     * @param ctx 事务上下文
     * @return {@code true} 预留成功；{@code false} 预留失败
     */
    boolean tryAction(TransactionContext ctx);

    /**
     * Confirm 阶段：确认提交。
     *
     * <p>将 Try 阶段预留的资源正式提交。必须幂等。</p>
     *
     * @param ctx 事务上下文
     */
    void confirmAction(TransactionContext ctx);

    /**
     * Cancel 阶段：取消预留。
     *
     * <p>释放 Try 阶段预留的资源，回滚到 Try 之前的状态。必须幂等。</p>
     *
     * @param ctx 事务上下文
     */
    void cancelAction(TransactionContext ctx);

    /**
     * 获取参与方标识。
     *
     * @return 参与方标识（如 "payment-service"）
     */
    default String getParticipantId() {
        return this.getClass().getSimpleName();
    }
}