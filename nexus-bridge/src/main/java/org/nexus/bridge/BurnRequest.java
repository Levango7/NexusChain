package org.nexus.bridge;

import java.util.Map;
import java.util.Objects;

/**
 * 销毁请求 DTO，用于发起 BRIDGE_BURN 操作（反向跨链）。
 *
 * <p>用户在目标链销毁 NEX，发起反向跨链。销毁成功后生成桥交易记录，
 * 等待验证者确认后在原链解锁等量 NEX。</p>
 *
 * @since 1.0.0
 */
public class BurnRequest {

    /** 源链 ID（解锁所在链，即原链）。 */
    private String sourceChainId;

    /** 目标链 ID（销毁所在链）。 */
    private String targetChainId;

    /** 销毁金额（NEX 最小单位）。 */
    private long amount;

    /** 用户在目标链的地址。 */
    private String userAddress;

    /** 用户在原链的接收地址。 */
    private String targetAddress;

    /** 目标链销毁交易哈希。 */
    private String sourceTxHash;

    /** 请求时间戳（毫秒）。 */
    private long timestamp;

    /**
     * 默认构造函数。
     */
    public BurnRequest() {
    }

    /**
     * 全参数构造函数。
     *
     * @param sourceChainId  原链 ID（解锁所在链）
     * @param targetChainId 目标链 ID（销毁所在链）
     * @param amount        销毁金额
     * @param userAddress   用户目标链地址
     * @param targetAddress 用户原链接收地址
     * @param sourceTxHash  目标链销毁交易哈希
     */
    public BurnRequest(String sourceChainId, String targetChainId, long amount,
                       String userAddress, String targetAddress, String sourceTxHash) {
        this.sourceChainId = sourceChainId;
        this.targetChainId = targetChainId;
        this.amount = amount;
        this.userAddress = userAddress;
        this.targetAddress = targetAddress;
        this.sourceTxHash = sourceTxHash;
        this.timestamp = System.currentTimeMillis();
    }

    public String getSourceChainId() {
        return sourceChainId;
    }

    public void setSourceChainId(String sourceChainId) {
        this.sourceChainId = sourceChainId;
    }

    public String getTargetChainId() {
        return targetChainId;
    }

    public void setTargetChainId(String targetChainId) {
        this.targetChainId = targetChainId;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getUserAddress() {
        return userAddress;
    }

    public void setUserAddress(String userAddress) {
        this.userAddress = userAddress;
    }

    public String getTargetAddress() {
        return targetAddress;
    }

    public void setTargetAddress(String targetAddress) {
        this.targetAddress = targetAddress;
    }

    public String getSourceTxHash() {
        return sourceTxHash;
    }

    public void setSourceTxHash(String sourceTxHash) {
        this.sourceTxHash = sourceTxHash;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BurnRequest that = (BurnRequest) o;
        return amount == that.amount
                && timestamp == that.timestamp
                && Objects.equals(sourceChainId, that.sourceChainId)
                && Objects.equals(targetChainId, that.targetChainId)
                && Objects.equals(userAddress, that.userAddress)
                && Objects.equals(targetAddress, that.targetAddress)
                && Objects.equals(sourceTxHash, that.sourceTxHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceChainId, targetChainId, amount,
                userAddress, targetAddress, sourceTxHash, timestamp);
    }

    @Override
    public String toString() {
        return "BurnRequest{"
                + "sourceChainId='" + sourceChainId + '\''
                + ", targetChainId='" + targetChainId + '\''
                + ", amount=" + amount
                + ", userAddress='" + userAddress + '\''
                + ", targetAddress='" + targetAddress + '\''
                + ", sourceTxHash='" + sourceTxHash + '\''
                + ", timestamp=" + timestamp
                + '}';
    }
}
