package org.nexus.gateway.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request DTO for creating a payment order.
 */
public class CreateOrderRequest {

    @NotBlank(message = "merchantId must not be blank")
    private String merchantId;

    @NotNull(message = "amount must not be null")
    @Min(value = 1, message = "amount must be positive")
    private BigDecimal amount;

    /** Optional token symbol override (defaults to NEX). */
    private String tokenSymbol = "NEX";

    @Size(max = 256, message = "description must not exceed 256 characters")
    private String description;

    /** Optional payer wallet address; if absent, filled at checkout time. */
    private String payerAddress;

    /** Merchant callback URL for payment result notification. */
    @NotBlank(message = "notifyUrl must not be blank")
    private String notifyUrl;

    /** Optional order expiry in minutes (defaults to gateway config). */
    private Integer expiryMinutes;

    /** Idempotency key to prevent duplicate order creation. */
    private String idempotencyKey;

    // --- Getters and Setters ---

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }

    public Integer getExpiryMinutes() { return expiryMinutes; }
    public void setExpiryMinutes(Integer expiryMinutes) { this.expiryMinutes = expiryMinutes; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
