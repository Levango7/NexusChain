package org.nexus.settlement.risk;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 风控交易载体。
 * <p>
 * 网关在调用 {@link RiskEngine#evaluate(Object)} 时统一构造本对象传入，
 * 使规则实现不再依赖 {@code Object} 的反射解析，保证类型安全与可测试性。
 * </p>
 */
public class RiskTransaction {

    /** 交易类型（PAYMENT / REFUND） */
    private String type = "PAYMENT";

    /** 商户 ID */
    private Long merchantId;

    /** 付款方地址 */
    private String payerAddress;

    /** 收款方地址 */
    private String payeeAddress;

    /** 金额（最小单位） */
    private BigDecimal amount;

    /** 币种符号 */
    private String currency;

    /** 幂等键 */
    private String idempotencyKey;

    /** 交易发起时间 */
    private Instant timestamp = Instant.now();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getPayeeAddress() { return payeeAddress; }
    public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "RiskTransaction{type='" + type + "', merchantId=" + merchantId
                + ", payer='" + payerAddress + "', amount=" + amount + ", currency='" + currency + "'}";
    }
}
