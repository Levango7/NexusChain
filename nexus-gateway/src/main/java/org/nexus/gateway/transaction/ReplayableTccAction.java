package org.nexus.gateway.transaction;

/**
 * 可重放的 TCC 参与方 — <b>无状态、仅从 {@link TransactionContext} 读取输入</b>的 {@link TccAction}。
 *
 * <p>普通 {@code TccAction} 常以匿名闭包形式创建，捕获方法局部变量 ⇒ 进程重启后闭包消失，
 * {@link TccTransactionManager} 的进程内 {@code actionRegistry} 也丢失，恢复调度器无法重试
 * Confirm/Cancel（#102 PLAN §3.1 的"冻结永不释放"）。</p>
 *
 * <p>实现本接口的参与方满足两点，使其可在<b>任意副本、重启后</b>被重建并安全重放：</p>
 * <ol>
 *   <li>无实例状态：Try/Confirm/Cancel 三阶段所需的一切输入都从传入的 {@code ctx}（由
 *       {@code TransactionLog.payload} 反序列化重建）读取，不依赖任何构造期捕获的局部变量；</li>
 *   <li>三阶段幂等（与 {@link TccAction} 契约一致）：重复调用不产生额外副作用。</li>
 * </ol>
 *
 * <p>{@link TccActionResolver} 按 {@link TccAction#getParticipantId()} 收集这些 bean；恢复调度器在
 * 进程内注册表 miss 时据此重建 action、用持久化上下文重试；解析不到才落
 * {@link TransactionStepStatus#PENDING_MANUAL}（#107 Step1 的兜底）。</p>
 */
public interface ReplayableTccAction extends TccAction {

    /**
     * 稳定的参与方标识，作为 {@link TccActionResolver} 的查找键。<b>必须跨重启、跨副本一致</b>，
     * 不要依赖 {@code getClass().getSimpleName()}（匿名类会返回空串）。
     */
    @Override
    String getParticipantId();
}
