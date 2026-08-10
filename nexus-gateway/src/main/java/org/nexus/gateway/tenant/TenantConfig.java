package org.nexus.gateway.tenant;

import jakarta.persistence.Embeddable;

import java.util.HashSet;
import java.util.Set;

/**
 * 租户配置（P4-T6 多租户改造）。
 *
 * <p>每个租户独立配置限流配额、最大支付金额、允许的币种、手续费率（基点 bps）和
 * webhook 回调地址。配置以 {@link Embeddable} 形式嵌入 {@link Tenant} 实体，存储在
 * tenants 表的 config_* 列中。</p>
 *
 * <ul>
 *   <li>{@code rateLimitPerSecond} / {@code rateLimitPerMinute}：限流配额，由
 *       {@link org.nexus.gateway.tenant.TenantRateLimiter} 读取并按 tenantId + endpoint 维度生效。</li>
 *   <li>{@code maxPaymentAmount}：单笔支付上限，超过则拒绝（风控前置校验）。</li>
 *   <li>{@code allowedCurrencies}：允许的币种白名单（如 NEX、USDT）。</li>
 *   <li>{@code feeRateBps}：手续费率，单位基点（1 bps = 0.01%），100 bps = 1%。</li>
 *   <li>{@code webhookUrl}：租户级 webhook 回调地址，覆盖平台默认配置。</li>
 * </ul>
 */
@Embeddable
public class TenantConfig {

    /** 每秒限流配额（QPS）。 */
    private Integer rateLimitPerSecond = 100;

    /** 每分钟限流配额。 */
    private Integer rateLimitPerMinute = 6000;

    /** 单笔支付最大金额（最小单位）。 */
    private Long maxPaymentAmount = 10000000000L;

    /** 允许的币种白名单（逗号分隔存储）。 */
    private String allowedCurrencies = "NEX";

    /** 手续费率（基点 bps，100 = 1%）。 */
    private Integer feeRateBps = 100;

    /** 租户级 webhook 回调 URL。 */
    private String webhookUrl;

    public TenantConfig() {
    }

    public TenantConfig(Integer rateLimitPerSecond, Integer rateLimitPerMinute,
                        Long maxPaymentAmount, String allowedCurrencies,
                        Integer feeRateBps, String webhookUrl) {
        this.rateLimitPerSecond = rateLimitPerSecond;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.maxPaymentAmount = maxPaymentAmount;
        this.allowedCurrencies = allowedCurrencies;
        this.feeRateBps = feeRateBps;
        this.webhookUrl = webhookUrl;
    }

    /**
     * 解析允许的币种白名单为 Set。
     *
     * @return 币种集合（大写）；空配置返回空集合
     */
    public Set<String> allowedCurrencySet() {
        Set<String> result = new HashSet<>();
        if (allowedCurrencies == null || allowedCurrencies.isEmpty()) {
            return result;
        }
        for (String c : allowedCurrencies.split(",")) {
            String trimmed = c.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed.toUpperCase());
            }
        }
        return result;
    }

    /**
     * 判断给定币种是否被允许。
     *
     * @param currency 币种符号
     * @return {@code true} 若白名单为空（默认允许全部）或包含该币种
     */
    public boolean isCurrencyAllowed(String currency) {
        if (currency == null) {
            return false;
        }
        Set<String> allowed = allowedCurrencySet();
        return allowed.isEmpty() || allowed.contains(currency.toUpperCase());
    }

    // --- Getters and Setters ---

    public Integer getRateLimitPerSecond() { return rateLimitPerSecond; }
    public void setRateLimitPerSecond(Integer rateLimitPerSecond) { this.rateLimitPerSecond = rateLimitPerSecond; }

    public Integer getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(Integer rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }

    public Long getMaxPaymentAmount() { return maxPaymentAmount; }
    public void setMaxPaymentAmount(Long maxPaymentAmount) { this.maxPaymentAmount = maxPaymentAmount; }

    public String getAllowedCurrencies() { return allowedCurrencies; }
    public void setAllowedCurrencies(String allowedCurrencies) { this.allowedCurrencies = allowedCurrencies; }

    public Integer getFeeRateBps() { return feeRateBps; }
    public void setFeeRateBps(Integer feeRateBps) { this.feeRateBps = feeRateBps; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
}