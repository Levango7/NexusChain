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

    /** IP 地址（用于 IP 风险评分） */
    private String ipAddress;

    /** 地域/国家代码（用于地域风险评分） */
    private String region;

    /** 设备指纹哈希（用于设备聚集检测） */
    private String deviceFingerprint;

    /** 支付渠道（用于渠道风险评分） */
    private String channel;

    /** 商户投诉率（0-1，用于商户历史风险评分） */
    private Double merchantComplaintRate;

    /** 商户退款率（0-1，用于商户历史风险评分） */
    private Double merchantRefundRate;

    /** 商户违规记录数（用于商户历史风险评分） */
    private Integer merchantViolationCount;

    /** 渠道故障率（0-1，用于渠道风险评分） */
    private Double channelFailureRate;

    /** 渠道拒付率（0-1，用于渠道风险评分） */
    private Double channelChargebackRate;

    /** 关联设备数（用于设备聚集检测） */
    private Integer linkedDeviceCount;

    /** 关联账号数（用于设备聚集检测） */
    private Integer linkedAccountCount;

    /** 短时间内拆单次数（用于交易模式异常检测） */
    private Integer splitOrderCount;

    /** 短时间内同主体交易次数（用于交易模式异常检测） */
    private Integer recentTransactionCount;

    /** 是否跨境交易（用于地域风险评分） */
    private Boolean crossBorder;

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

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getDeviceFingerprint() { return deviceFingerprint; }
    public void setDeviceFingerprint(String deviceFingerprint) { this.deviceFingerprint = deviceFingerprint; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public Double getMerchantComplaintRate() { return merchantComplaintRate; }
    public void setMerchantComplaintRate(Double merchantComplaintRate) { this.merchantComplaintRate = merchantComplaintRate; }

    public Double getMerchantRefundRate() { return merchantRefundRate; }
    public void setMerchantRefundRate(Double merchantRefundRate) { this.merchantRefundRate = merchantRefundRate; }

    public Integer getMerchantViolationCount() { return merchantViolationCount; }
    public void setMerchantViolationCount(Integer merchantViolationCount) { this.merchantViolationCount = merchantViolationCount; }

    public Double getChannelFailureRate() { return channelFailureRate; }
    public void setChannelFailureRate(Double channelFailureRate) { this.channelFailureRate = channelFailureRate; }

    public Double getChannelChargebackRate() { return channelChargebackRate; }
    public void setChannelChargebackRate(Double channelChargebackRate) { this.channelChargebackRate = channelChargebackRate; }

    public Integer getLinkedDeviceCount() { return linkedDeviceCount; }
    public void setLinkedDeviceCount(Integer linkedDeviceCount) { this.linkedDeviceCount = linkedDeviceCount; }

    public Integer getLinkedAccountCount() { return linkedAccountCount; }
    public void setLinkedAccountCount(Integer linkedAccountCount) { this.linkedAccountCount = linkedAccountCount; }

    public Integer getSplitOrderCount() { return splitOrderCount; }
    public void setSplitOrderCount(Integer splitOrderCount) { this.splitOrderCount = splitOrderCount; }

    public Integer getRecentTransactionCount() { return recentTransactionCount; }
    public void setRecentTransactionCount(Integer recentTransactionCount) { this.recentTransactionCount = recentTransactionCount; }

    public Boolean getCrossBorder() { return crossBorder; }
    public void setCrossBorder(Boolean crossBorder) { this.crossBorder = crossBorder; }

    @Override
    public String toString() {
        return "RiskTransaction{type='" + type + "', merchantId=" + merchantId
                + ", payer='" + payerAddress + "', amount=" + amount + ", currency='" + currency + "'}";
    }
}
