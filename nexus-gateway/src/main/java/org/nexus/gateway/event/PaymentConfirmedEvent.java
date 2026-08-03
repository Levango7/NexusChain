package org.nexus.gateway.event;

/**
 * Fired when a payment order is confirmed on-chain.
 */
public class PaymentConfirmedEvent extends PaymentEvent {

    private final String chainTxHash;
    private final String payerAddress;
    private final String amount;

    public PaymentConfirmedEvent(Object source, Long orderId, String orderNo, Long merchantId,
                                 String chainTxHash, String payerAddress, String amount) {
        super(source, orderId, orderNo, merchantId);
        this.chainTxHash = chainTxHash;
        this.payerAddress = payerAddress;
        this.amount = amount;
    }

    @Override
    public String getEventType() { return "PAYMENT_CONFIRMED"; }

    public String getChainTxHash() { return chainTxHash; }
    public String getPayerAddress() { return payerAddress; }
    public String getAmount() { return amount; }
}