package org.nexus.gateway.event;

/**
 * Fired when a refund is completed.
 */
public class RefundCompletedEvent extends PaymentEvent {

    private final String refundNo;
    private final String amount;
    private final String chainTxHash;

    public RefundCompletedEvent(Object source, Long orderId, String orderNo, Long merchantId,
                                String refundNo, String amount, String chainTxHash) {
        super(source, orderId, orderNo, merchantId);
        this.refundNo = refundNo;
        this.amount = amount;
        this.chainTxHash = chainTxHash;
    }

    @Override
    public String getEventType() { return "REFUND_COMPLETED"; }

    public String getRefundNo() { return refundNo; }
    public String getAmount() { return amount; }
    public String getChainTxHash() { return chainTxHash; }
}