package org.nexus.settlement.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 链上交易请求的统一封装。
 * <p>
 * 支持结算（SETTLEMENT）、退款（REFUND）、提币（WITHDRAWAL）、归集（SWEEP）
 * 四种业务场景。所有链上执行通道的调用方都应构造本对象作为入参。
 * </p>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@code fromAddress} / {@code toAddress}：链上发送方与接收方地址</li>
 *   <li>{@code amount}：转账金额（最小单位，非负）</li>
 *   <li>{@code asset}：资产标识（如 "NEX"、"USDT"），默认 "NEX"</li>
 *   <li>{@code memo}：业务备注（如订单号、退款单号），可选</li>
 *   <li>{@code type}：业务场景类型，必填</li>
 *   <li>{@code requestId}：业务请求标识（如订单 ID、退款 ID），用于幂等与可追溯</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionRequest {

    /** 业务场景类型 */
    public enum Type {
        /** 结算转账 */
        SETTLEMENT,
        /** 退款转账 */
        REFUND,
        /** 提币转账 */
        WITHDRAWAL,
        /** 资金归集 */
        SWEEP
    }

    /** 发送方链上地址 */
    @JsonProperty("fromAddress")
    private String fromAddress;

    /** 接收方链上地址 */
    @JsonProperty("toAddress")
    private String toAddress;

    /** 转账金额（最小单位） */
    @JsonProperty("amount")
    private BigDecimal amount;

    /** 资产标识，默认 NEX */
    @JsonProperty("asset")
    private String asset = "NEX";

    /** 业务备注 */
    @JsonProperty("memo")
    private String memo;

    /** 业务场景类型 */
    @JsonProperty("type")
    private Type type;

    /** 业务请求标识（订单 ID / 退款 ID 等），用于幂等与可追溯 */
    @JsonProperty("requestId")
    private String requestId;

    public TransactionRequest() {
    }

    public TransactionRequest(Type type, String fromAddress, String toAddress,
                              BigDecimal amount, String asset, String memo, String requestId) {
        this.type = type;
        this.fromAddress = fromAddress;
        this.toAddress = toAddress;
        this.amount = amount;
        this.asset = asset != null ? asset : "NEX";
        this.memo = memo;
        this.requestId = requestId;
    }

    // --- Getters and Setters ---

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getAsset() { return asset; }
    public void setAsset(String asset) { this.asset = asset; }

    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransactionRequest)) return false;
        TransactionRequest that = (TransactionRequest) o;
        return Objects.equals(fromAddress, that.fromAddress)
                && Objects.equals(toAddress, that.toAddress)
                && Objects.equals(amount, that.amount)
                && Objects.equals(asset, that.asset)
                && Objects.equals(memo, that.memo)
                && type == that.type
                && Objects.equals(requestId, that.requestId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromAddress, toAddress, amount, asset, memo, type, requestId);
    }

    @Override
    public String toString() {
        return "TransactionRequest{type=" + type
                + ", fromAddress='" + fromAddress + '\''
                + ", toAddress='" + toAddress + '\''
                + ", amount=" + amount
                + ", asset='" + asset + '\''
                + ", memo='" + memo + '\''
                + ", requestId='" + requestId + '\'' + '}';
    }
}