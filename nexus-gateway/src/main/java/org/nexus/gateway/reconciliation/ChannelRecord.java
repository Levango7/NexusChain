package org.nexus.gateway.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 渠道对账文件中的单条交易记录。
 *
 * <p>从渠道提供的对账文件（CSV/JSON）解析而来，作为 {@link ReconciliationEngine}
 * 比对的渠道侧输入。每条记录包含交易ID、金额、状态、时间等维度信息。</p>
 */
public class ChannelRecord {

    /** 交易 ID（对应内部 PaymentOrder.orderNo） */
    private String transactionId;

    /** 交易金额 */
    private BigDecimal amount;

    /** 交易状态（渠道侧的状态描述） */
    private String status;

    /** 币种 */
    private String currency;

    /** 渠道交易号/连接器 ID */
    private String connectorId;

    /** 渠道侧记录的交易创建时间 */
    private LocalDateTime createdAt;

    /** 渠道侧记录的交易完成时间 */
    private LocalDateTime paidAt;

    public ChannelRecord() {}

    public ChannelRecord(String transactionId, BigDecimal amount, String status) {
        this.transactionId = transactionId;
        this.amount = amount;
        this.status = status;
    }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }

    @Override
    public String toString() {
        return "ChannelRecord{transactionId='" + transactionId + '\''
                + ", amount=" + amount
                + ", status='" + status + '\''
                + '}';
    }
}