package org.nexus.gateway.risk;

import java.math.BigDecimal;

/**
 * Lightweight request DTO capturing the inputs required for payment risk
 * evaluation.
 *
 * <p>This is intentionally a self-contained POJO so the risk service can be
 * evaluated without depending on the full {@code CreateOrderRequest} validation
 * graph.</p>
 */
public class PaymentRequest {

    /** Merchant initiating the payment. */
    private Long merchantId;

    /** Payer wallet address. */
    private String payerAddress;

    /** Payment amount in the smallest unit of the token. */
    private BigDecimal amount;

    /** Token symbol (e.g. NEX, USDT). */
    private String tokenSymbol;

    /** Optional payer IP for geo-risk checks. */
    private String payerIp;

    /** Optional idempotency key from the original order. */
    private String idempotencyKey;

    public PaymentRequest() {}

    public PaymentRequest(Long merchantId, String payerAddress, BigDecimal amount, String tokenSymbol) {
        this.merchantId = merchantId;
        this.payerAddress = payerAddress;
        this.amount = amount;
        this.tokenSymbol = tokenSymbol;
    }

    // --- Getters and Setters ---

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    public String getPayerIp() { return payerIp; }
    public void setPayerIp(String payerIp) { this.payerIp = payerIp; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}