package org.nexus.bridge;

import java.util.Map;
import java.util.Objects;

/**
 * 解锁请求 DTO，用于发起 BRIDGE_UNLOCK 操作（反向跨链）。
 *
 * <p>验证者确认目标链销毁交易后，在原链解锁等量 NEX 给用户。
 * 本请求需携带达到阈值的验证者签名集合。</p>
 *
 * @since 1.0.0
 */
public class UnlockRequest {

    /** 对应的销毁桥交易 ID。 */
    private String burnTxId;

    /** 验证者签名集合（验证者 ID → 签名）。 */
    private Map<String, String> signatures;

    /** 执行解锁操作的验证者地址。 */
    private String unlockerAddress;

    /** 原链 ID（解锁所在链）。 */
    private String sourceChainId;

    /** 请求时间戳（毫秒）。 */
    private long timestamp;

    /**
     * 默认构造函数。
     */
    public UnlockRequest() {
    }

    /**
     * 全参数构造函数。
     *
     * @param burnTxId         销毁桥交易 ID
     * @param signatures       验证者签名集合
     * @param unlockerAddress  执行解锁的验证者地址
     * @param sourceChainId    原链 ID
     */
    public UnlockRequest(String burnTxId, Map<String, String> signatures,
                         String unlockerAddress, String sourceChainId) {
        this.burnTxId = burnTxId;
        this.signatures = signatures;
        this.unlockerAddress = unlockerAddress;
        this.sourceChainId = sourceChainId;
        this.timestamp = System.currentTimeMillis();
    }

    public String getBurnTxId() {
        return burnTxId;
    }

    public void setBurnTxId(String burnTxId) {
        this.burnTxId = burnTxId;
    }

    public Map<String, String> getSignatures() {
        return signatures;
    }

    public void setSignatures(Map<String, String> signatures) {
        this.signatures = signatures;
    }

    public String getUnlockerAddress() {
        return unlockerAddress;
    }

    public void setUnlockerAddress(String unlockerAddress) {
        this.unlockerAddress = unlockerAddress;
    }

    public String getSourceChainId() {
        return sourceChainId;
    }

    public void setSourceChainId(String sourceChainId) {
        this.sourceChainId = sourceChainId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 获取参与签名的验证者数量。
     *
     * @return 签名数量
     */
    public int getSignatureCount() {
        return signatures != null ? signatures.size() : 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UnlockRequest that = (UnlockRequest) o;
        return timestamp == that.timestamp
                && Objects.equals(burnTxId, that.burnTxId)
                && Objects.equals(signatures, that.signatures)
                && Objects.equals(unlockerAddress, that.unlockerAddress)
                && Objects.equals(sourceChainId, that.sourceChainId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(burnTxId, signatures, unlockerAddress, sourceChainId, timestamp);
    }

    @Override
    public String toString() {
        return "UnlockRequest{"
                + "burnTxId='" + burnTxId + '\''
                + ", signatures=" + (signatures != null ? signatures.size() : 0) + " sigs"
                + ", unlockerAddress='" + unlockerAddress + '\''
                + ", sourceChainId='" + sourceChainId + '\''
                + ", timestamp=" + timestamp
                + '}';
    }
}
