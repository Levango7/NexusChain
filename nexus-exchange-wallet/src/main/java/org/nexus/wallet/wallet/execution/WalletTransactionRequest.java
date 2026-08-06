package org.nexus.wallet.wallet.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Wallet 端链上交易请求 DTO。
 * <p>
 * 与 gateway 端 {@code org.nexus.settlement.execution.TransactionRequest}
 * 的 JSON 结构保持一致，通过 HTTP 传输。wallet 不依赖 settlement，因此
 * 本地定义一份结构等价的 DTO。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WalletTransactionRequest {

    /** 业务场景类型 */
    public enum Type {
        SETTLEMENT,
        REFUND,
        WITHDRAWAL,
        SWEEP
    }

    @JsonProperty("fromAddress")
    private String fromAddress;

    @JsonProperty("toAddress")
    private String toAddress;

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("asset")
    private String asset = "NEX";

    @JsonProperty("memo")
    private String memo;

    @JsonProperty("type")
    private Type type;

    @JsonProperty("requestId")
    private String requestId;

    public WalletTransactionRequest() {
    }

    public WalletTransactionRequest(Type type, String fromAddress, String toAddress,
                                     BigDecimal amount, String asset, String memo, String requestId) {
        this.type = type;
        this.fromAddress = fromAddress;
        this.toAddress = toAddress;
        this.amount = amount;
        this.asset = asset != null ? asset : "NEX";
        this.memo = memo;
        this.requestId = requestId;
    }

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
        if (!(o instanceof WalletTransactionRequest)) return false;
        WalletTransactionRequest that = (WalletTransactionRequest) o;
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
}