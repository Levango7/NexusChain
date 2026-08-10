package org.nexus.gateway.apiversion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * v2 批量创建支付请求（P4-T7）。
 *
 * <p>对应端点 {@code POST /api/v2/payments/batch}，一次提交多笔支付。
 * 单次最多 {@link #MAX_BATCH_SIZE} 笔，超出返回 {@code BAD_REQUEST}。</p>
 */
public class BatchPaymentRequest {

    /** 单次批量上限 */
    public static final int MAX_BATCH_SIZE = 50;

    @NotEmpty(message = "payments must not be empty")
    @Size(max = MAX_BATCH_SIZE, message = "payments must not exceed " + MAX_BATCH_SIZE + " items per batch")
    @Valid
    private List<PaymentItem> payments;

    /**
     * 失败处理策略：
     * <ul>
     *   <li>{@code ALL_OR_NOTHING}：任一失败则全部回滚（默认）</li>
     *   <li>{@code PARTIAL}：成功的提交、失败的逐项返回错误</li>
     * </ul>
     */
    private FailureStrategy onFailure = FailureStrategy.ALL_OR_NOTHING;

    public List<PaymentItem> getPayments() {
        return payments;
    }

    public void setPayments(List<PaymentItem> payments) {
        this.payments = payments;
    }

    public FailureStrategy getOnFailure() {
        return onFailure;
    }

    public void setOnFailure(FailureStrategy onFailure) {
        this.onFailure = onFailure;
    }

    /** 单笔支付项 */
    public static class PaymentItem {
        @NotNull
        @Min(1)
        private Long merchantId;

        @NotNull
        @Min(1)
        private java.math.BigDecimal amount;

        private String tokenSymbol = "NEX";

        @Size(max = 256)
        private String description;

        private String payerAddress;

        @NotNull
        private String notifyUrl;

        private String idempotencyKey;

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public java.math.BigDecimal getAmount() { return amount; }
        public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }
        public String getTokenSymbol() { return tokenSymbol; }
        public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getPayerAddress() { return payerAddress; }
        public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }
        public String getNotifyUrl() { return notifyUrl; }
        public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    }

    /** 失败处理策略 */
    public enum FailureStrategy {
        ALL_OR_NOTHING,
        PARTIAL
    }
}