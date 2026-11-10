package org.nexus.gateway.clearing;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 清算批次完成事件 — 清算批次按权威路径（{@code ClearingEngine.batchClear}）记账完毕后发布，
 * 是「既成事实的通知」，不是记账指令（板面 20261110-1800 裁决 Q1）。
 *
 * <p>只携既成事实：批次号、订单数、批次净额、完成时间。消费者只读
 * （对账视图/通知/审计），禁止任何 {@code credit}/{@code book}/冲正动作；
 * 唯一记账源是清算引擎命令路径（{@code ledger.bookSettlement}）。</p>
 *
 * <p>发布点在 {@code SettlementScheduler.settle()} 的 {@code batchClear} 返回 SETTLED 分支，
 * 即全部 {@code bookSettlement} 已提交之后（不变量 I2）；不得在记账前发布。</p>
 */
public class ClearingBatchCompletedEvent extends ApplicationEvent {

    private final String batchNo;
    private final int orderCount;
    private final BigDecimal netAmount;
    private final LocalDateTime completedAt;

    public ClearingBatchCompletedEvent(Object source, String batchNo, int orderCount,
                                        BigDecimal netAmount, LocalDateTime completedAt) {
        super(source);
        this.batchNo = batchNo;
        this.orderCount = orderCount;
        this.netAmount = netAmount;
        this.completedAt = completedAt;
    }

    public String getBatchNo() { return batchNo; }
    public int getOrderCount() { return orderCount; }
    public BigDecimal getNetAmount() { return netAmount; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}
