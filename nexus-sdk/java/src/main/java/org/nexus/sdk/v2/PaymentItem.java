package org.nexus.sdk.v2;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 批量支付项（v2 SDK）。
 *
 * <p>对应 {@code POST /api/v2/payments/batch} 请求中的单笔支付。</p>
 */
public final class PaymentItem {

    private final Long merchantId;
    private final BigDecimal amount;
    private final String tokenSymbol;
    private final String description;
    private final String payerAddress;
    private final String notifyUrl;
    private final String idempotencyKey;

    private PaymentItem(Builder b) {
        this.merchantId = b.merchantId;
        this.amount = b.amount;
        this.tokenSymbol = b.tokenSymbol;
        this.description = b.description;
        this.payerAddress = b.payerAddress;
        this.notifyUrl = b.notifyUrl;
        this.idempotencyKey = b.idempotencyKey;
    }

    public Long getMerchantId() { return merchantId; }
    public BigDecimal getAmount() { return amount; }
    public String getTokenSymbol() { return tokenSymbol; }
    public String getDescription() { return description; }
    public String getPayerAddress() { return payerAddress; }
    public String getNotifyUrl() { return notifyUrl; }
    public String getIdempotencyKey() { return idempotencyKey; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long merchantId;
        private BigDecimal amount;
        private String tokenSymbol = "NEX";
        private String description;
        private String payerAddress;
        private String notifyUrl;
        private String idempotencyKey;

        public Builder merchantId(Long merchantId) { this.merchantId = merchantId; return this; }
        public Builder amount(BigDecimal amount) { this.amount = amount; return this; }
        public Builder tokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder payerAddress(String payerAddress) { this.payerAddress = payerAddress; return this; }
        public Builder notifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }

        public PaymentItem build() {
            Objects.requireNonNull(merchantId, "merchantId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(notifyUrl, "notifyUrl");
            return new PaymentItem(this);
        }
    }
}