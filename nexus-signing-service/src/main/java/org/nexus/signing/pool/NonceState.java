package org.nexus.signing.pool;

/**
 * Nonce 状态实体。
 *
 * <p>从 {@code org.nexus.wallet.wallet.pool.NonceState}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.pool}。</p>
 *
 * <p>Phase 3 任务 #62：新增 {@code status} 字段以支持 TCC 预锁定。
 * 非 TCC 路径不设置 status（默认 {@link #STATUS_AVAILABLE}），保持向后兼容。</p>
 */
public class NonceState {

    /** 可用：未被任何事务占用（非 TCC 路径默认状态）。 */
    public static final String STATUS_AVAILABLE = "AVAILABLE";

    /** 已锁定：TCC Try 阶段预锁定，等待 Confirm/Cancel。 */
    public static final String STATUS_LOCKED = "LOCKED";

    /** 已使用：TCC Confirm 阶段签名广播完成。 */
    public static final String STATUS_USED = "USED";

    private String TranHash;
    private long nonce;
    private long datetime;
    /** Phase 3 任务 #62：TCC 预锁定状态，默认 {@link #STATUS_AVAILABLE}（向后兼容）。 */
    private String status = STATUS_AVAILABLE;

    public NonceState() {
    }

    public NonceState(String tranHash,  long nonce, long datetime ) {
        TranHash = tranHash;
        this.nonce = nonce;
        this.datetime = datetime;
    }

    public NonceState(String tranHash, long nonce, long datetime, String status) {
        TranHash = tranHash;
        this.nonce = nonce;
        this.datetime = datetime;
        this.status = status == null ? STATUS_AVAILABLE : status;
    }

    public String getTranHash() {
        return TranHash;
    }

    public void setTranHash(String tranHash) {
        TranHash = tranHash;
    }

    public long getNonce() {
        return nonce;
    }

    public void setNonce(long nonce) {
        this.nonce = nonce;
    }

    public long getDatetime() {
        return datetime;
    }

    public void setDatetime(long datetime) {
        this.datetime = datetime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isLocked() {
        return STATUS_LOCKED.equals(status);
    }

    public boolean isUsed() {
        return STATUS_USED.equals(status);
    }
}