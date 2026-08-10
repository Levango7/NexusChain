package org.nexus.bridge;

import java.util.Map;
import java.util.Objects;

/**
 * 铸造请求 DTO，用于发起 BRIDGE_MINT 操作。
 *
 * <p>验证者确认源链锁定交易后，在目标链铸造等量 NEX 给用户。
 * 本请求需携带达到阈值的验证者签名集合，桥服务验签通过后方可执行铸造。</p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code lockTxId} — 对应的锁定桥交易 ID</li>
 *   <li>{@code signatures} — 验证者签名集合（key: 验证者 ID, value: 签名）</li>
 *   <li>{@code minterAddress} — 执行铸造的验证者地址</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class MintRequest {

    /** 对应的锁定桥交易 ID。 */
    private String lockTxId;

    /** 验证者签名集合（验证者 ID → 签名）。 */
    private Map<String, String> signatures;

    /** 执行铸造操作的验证者地址。 */
    private String minterAddress;

    /** 目标链 ID（铸造所在链）。 */
    private String targetChainId;

    /** 请求时间戳（毫秒）。 */
    private long timestamp;

    /**
     * 默认构造函数。
     */
    public MintRequest() {
    }

    /**
     * 全参数构造函数。
     *
     * @param lockTxId       锁定桥交易 ID
     * @param signatures     验证者签名集合
     * @param minterAddress  执行铸造的验证者地址
     * @param targetChainId  目标链 ID
     */
    public MintRequest(String lockTxId, Map<String, String> signatures,
                       String minterAddress, String targetChainId) {
        this.lockTxId = lockTxId;
        this.signatures = signatures;
        this.minterAddress = minterAddress;
        this.targetChainId = targetChainId;
        this.timestamp = System.currentTimeMillis();
    }

    public String getLockTxId() {
        return lockTxId;
    }

    public void setLockTxId(String lockTxId) {
        this.lockTxId = lockTxId;
    }

    public Map<String, String> getSignatures() {
        return signatures;
    }

    public void setSignatures(Map<String, String> signatures) {
        this.signatures = signatures;
    }

    public String getMinterAddress() {
        return minterAddress;
    }

    public void setMinterAddress(String minterAddress) {
        this.minterAddress = minterAddress;
    }

    public String getTargetChainId() {
        return targetChainId;
    }

    public void setTargetChainId(String targetChainId) {
        this.targetChainId = targetChainId;
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
        MintRequest that = (MintRequest) o;
        return timestamp == that.timestamp
                && Objects.equals(lockTxId, that.lockTxId)
                && Objects.equals(signatures, that.signatures)
                && Objects.equals(minterAddress, that.minterAddress)
                && Objects.equals(targetChainId, that.targetChainId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lockTxId, signatures, minterAddress, targetChainId, timestamp);
    }

    @Override
    public String toString() {
        return "MintRequest{"
                + "lockTxId='" + lockTxId + '\''
                + ", signatures=" + (signatures != null ? signatures.size() : 0) + " sigs"
                + ", minterAddress='" + minterAddress + '\''
                + ", targetChainId='" + targetChainId + '\''
                + ", timestamp=" + timestamp
                + '}';
    }
}
