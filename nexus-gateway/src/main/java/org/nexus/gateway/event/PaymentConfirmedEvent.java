package org.nexus.gateway.event;

import org.nexus.gateway.model.FinalityStatus;

/**
 * Fired when a payment order is confirmed on-chain.
 *
 * <p>Step 3（统一交易/支付最终性模型）：增加 {@code finalityStatus} 字段，
 * 使下游 Webhook 投递链路可基于最终性级别决定是否通知商户。</p>
 */
public class PaymentConfirmedEvent extends PaymentEvent {

    private final String chainTxHash;
    private final String payerAddress;
    private final String amount;
    private final FinalityStatus finalityStatus;

    public PaymentConfirmedEvent(Object source, Long orderId, String orderNo, Long merchantId,
                                 String chainTxHash, String payerAddress, String amount,
                                 FinalityStatus finalityStatus) {
        super(source, orderId, orderNo, merchantId);
        this.chainTxHash = chainTxHash;
        this.payerAddress = payerAddress;
        this.amount = amount;
        this.finalityStatus = finalityStatus;
    }

    /** 兼容旧构造器：finalityStatus 默认 UNKNOWN（未关联最终性信息）。 */
    public PaymentConfirmedEvent(Object source, Long orderId, String orderNo, Long merchantId,
                                 String chainTxHash, String payerAddress, String amount) {
        this(source, orderId, orderNo, merchantId, chainTxHash, payerAddress, amount, FinalityStatus.UNKNOWN);
    }

    @Override
    public String getEventType() { return "PAYMENT_CONFIRMED"; }

    public String getChainTxHash() { return chainTxHash; }
    public String getPayerAddress() { return payerAddress; }
    public String getAmount() { return amount; }
    public FinalityStatus getFinalityStatus() { return finalityStatus; }
}