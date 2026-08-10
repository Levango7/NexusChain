package org.nexus.bridge;

import java.util.Objects;

/**
 * 锁定请求 DTO，用于发起 BRIDGE_LOCK 操作。
 *
 * <p>用户在源链将 NEX 锁定到桥托管地址时，由桥服务接收本请求。
 * 请求经验证后生成桥交易记录，等待验证者确认后在目标链铸造。</p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code sourceChainId} — 源链标识（如 "ethereum"、"bsc"）</li>
 *   <li>{@code targetChainId} — 目标链标识</li>
 *   <li>{@code amount} — 锁定金额（NEX 最小单位，1 NEX = 10^18 最小单位）</li>
 *   <li>{@code userAddress} — 用户在源链的地址</li>
 *   <li>{@code targetAddress} — 用户在目标链的接收地址</li>
 *   <li>{@code sourceTxHash} — 源链锁定交易的哈希</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class LockRequest {

    /** 源链 ID（如 "ethereum"、"bsc"）。 */
    private String sourceChainId;

    /** 目标链 ID。 */
    private String targetChainId;

    /** 锁定金额（NEX 最小单位）。 */
    private long amount;

    /** 用户在源链的地址。 */
    private String userAddress;

    /** 用户在目标链的接收地址。 */
    private String targetAddress;

    /** 源链锁定交易哈希。 */
    private String sourceTxHash;

    /** 请求时间戳（毫秒）。 */
    private long timestamp;

    /** 可选的备注或附加数据。 */
    private String memo;

    /**
     * 默认构造函数。
     */
    public LockRequest() {
    }

    /**
     * 全参数构造函数。
     *
     * @param sourceChainId  源链 ID
     * @param targetChainId  目标链 ID
     * @param amount         锁定金额
     * @param userAddress    用户源链地址
     * @param targetAddress  目标链接收地址
     * @param sourceTxHash   源链交易哈希
     */
    public LockRequest(String sourceChainId, String targetChainId, long amount,
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

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LockRequest that = (LockRequest) o;
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
        return "LockRequest{"
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
