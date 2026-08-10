package org.nexus.gateway.risk;

import java.math.BigDecimal;

/**
 * Lightweight request DTO capturing the inputs required for refund risk
 * evaluation.
 */
public class RefundRequest {

    /** Original order ID being refunded. */
    private Long orderId;

    /** Owning merchant ID. */
    private Long merchantId;

    /** Refund amount in the smallest unit of the token. */
    private BigDecimal amount;

    /** Refund reason (free text). */
    private String reason;

    /** Payer wallet address receiving the refund. */
    private String receiverAddress;

    public RefundRequest() {}

    public RefundRequest(Long orderId, Long merchantId, BigDecimal amount, String reason) {
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.reason = reason;
    }

    // --- Getters and Setters ---

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }
}