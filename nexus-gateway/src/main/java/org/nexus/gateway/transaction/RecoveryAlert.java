package org.nexus.gateway.transaction;

/**
 * 分布式事务恢复链路的结构化告警标记 — 供日志/监控精确匹配。
 *
 * <p>当 {@link TccTransactionManager} / {@link SagaTransactionManager} 的恢复方法在
 * <b>进程内注册表 miss</b>（典型为服务重启后 {@code actionRegistry}/{@code stepRegistry} 空）时，
 * 不再静默把超时事务标成 {@link TransactionStepStatus#FAILED}（那会让"预留资源从未释放"
 * 看起来像"已处理"），而是落到 {@link TransactionStepStatus#PENDING_MANUAL} 并打一条带本标记的
 * WARN，让"资金/库存被挂起、需人工确认释放"这件事可见可告警。</p>
 *
 * <p>安全取向与 #97「宁可漏收、不可双扣」同源：这里「宁可显式挂起 + 告警，不可静默标 FAILED 假装处理过」。</p>
 */
public final class RecoveryAlert {

    private RecoveryAlert() {
    }

    /**
     * 恢复动作被移交人工确认时写入 WARN 首字段的稳定标记。
     * 监控应据此告警：出现即表示有事务的自动释放/补偿没能完成，需人工核对资源是否仍被占用。
     */
    public static final String MANUAL_REQUIRED_MARKER = "DISTRIBUTED_TX_RECOVERY_MANUAL_REQUIRED";
}
