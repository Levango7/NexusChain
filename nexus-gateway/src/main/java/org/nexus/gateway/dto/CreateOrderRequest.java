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

    /**
     * Optional order expiry in minutes (defaults to gateway config).
     *
     * <p>{@code @Min(1)}：0/负数会落到 {@code LocalDateTime.now().plusMinutes(0/-n)}
     * ⇒ 生成一个「出生即过期」的订单，不是有效请求（B-13 载荷电池实测：修复前
     * 负值被静默接受）。上界不设：那是策略数字（产品决策），此处只堵形态错误。</p>
     *
     * <p>{@link StrictIntegerDeserializer}：拒绝 {@code 3.5} 这类小数被 Jackson
     * 静默截断成 {@code 3}（实测修复前返回 201，调用方无从知晓时长被改）。</p>
     */
    @Min(value = 1, message = "expiryMinutes must be a positive number of minutes")
    @tools.jackson.databind.annotation.JsonDeserialize(using = StrictIntegerDeserializer.class)
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
