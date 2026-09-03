package org.nexus.analytics.event;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付完成事件。
 *
 * <p>由 nexus-gateway 在支付订单链上确认成功后发布，
 * nexus-analytics 的 {@code PaymentEventCollector} 通过 {@code @EventListener} 接收并采集。
 *
 * <p>事件类放在 nexus-analytics 而非 gateway，是为了避免 analytics 反向依赖 gateway
 * （gateway 依赖 analytics，构成单向依赖图）。gateway 通过 composite build 引入本模块后
 * 即可发布本事件类型。
 */
public class PaymentCompletedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /** 支付订单 ID（gateway PaymentOrder.id） */
    private final Long paymentId;
    /** 支付金额（法币最小单位，如分） */
    private final BigDecimal amount;
    /** 法币币种代码（ISO 4217，如 CNY/USD） */
    private final String currency;
    /** 链连接器标识（如 ETHEREUM/BSC/POLYGON） */
    private final String connector;
    /** 商户 ID */
    private final Long merchantId;
    /** 链上交易哈希 */
    private final String chainTxHash;
    /** 付款方地址 */
    private final String payerAddress;
    /** 收款方地址 */
    private final String payeeAddress;
    /** 事件发生时间戳 */
    private final Instant occurredAt;
    /** 路由决策耗时（毫秒） */
    private final long latencyMs;
    /** 支付成本（basis points） */
    private final int costBps;

    public PaymentCompletedEvent(Object source, Long paymentId, BigDecimal amount, String currency,
                                 String connector, Long merchantId, String chainTxHash,
                                 String payerAddress, String payeeAddress, Instant occurredAt,
                                 long latencyMs, int costBps) {
        super(source);
        this.paymentId = paymentId;
        this.amount = amount;
        this.currency = currency;
        this.connector = connector;
        this.merchantId = merchantId;
        this.chainTxHash = chainTxHash;
        this.payerAddress = payerAddress;
        this.payeeAddress = payeeAddress;
        this.occurredAt = occurredAt;
        this.latencyMs = latencyMs;
        this.costBps = costBps;
    }

    public Long getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getConnector() { return connector; }
    public Long getMerchantId() { return merchantId; }
    public String getChainTxHash() { return chainTxHash; }
    public String getPayerAddress() { return payerAddress; }
    public String getPayeeAddress() { return payeeAddress; }
    public Instant getOccurredAt() { return occurredAt; }
    public long getLatencyMs() { return latencyMs; }
    public int getCostBps() { return costBps; }
}
