package org.nexus.consensus.finality.persistence;

import org.nexus.consensus.finality.Vote;

import java.util.Arrays;

/**
 * outbox 中的一条待补写投票（PLAN-018）。
 *
 * <p>它是"**完整** Vote"的持久化载体：除了计数用的 (epoch, checkpoint, validator)，
 * 还带 Ed25519 签名与公钥 —— 没有这两项，重启后即使把票补写进
 * {@code finality_votes}，{@code FinalityGadget} 也无法做聚合验签，
 * 检查点将永远停在"权重够了但验签不了"的状态。</p>
 *
 * <p>不可变值对象；{@link #toVote()} 可还原成 {@link Vote} 供重试提交。</p>
 */
public final class FinalityOutboxEntry {

    private final long epoch;
    private final byte[] checkpointHash;
    private final String validatorAddress;
    private final byte[] signature;
    private final byte[] publicKey;
    private final FinalityVoteOrigin origin;
    private final long createdAt;
    private final int attempts;
    private final String lastError;

    public FinalityOutboxEntry(long epoch, byte[] checkpointHash, String validatorAddress,
                               byte[] signature, byte[] publicKey, FinalityVoteOrigin origin,
                               long createdAt, int attempts, String lastError) {
        this.epoch = epoch;
        this.checkpointHash = checkpointHash == null ? new byte[0] : checkpointHash.clone();
        this.validatorAddress = validatorAddress;
        this.signature = signature == null ? null : signature.clone();
        this.publicKey = publicKey == null ? null : publicKey.clone();
        this.origin = origin == null ? FinalityVoteOrigin.RECEIVED : origin;
        this.createdAt = createdAt;
        this.attempts = attempts;
        this.lastError = lastError;
    }

    public long getEpoch() {
        return epoch;
    }

    public byte[] getCheckpointHash() {
        return checkpointHash.clone();
    }

    public String getValidatorAddress() {
        return validatorAddress;
    }

    public byte[] getSignature() {
        return signature == null ? null : signature.clone();
    }

    public byte[] getPublicKey() {
        return publicKey == null ? null : publicKey.clone();
    }

    public FinalityVoteOrigin getOrigin() {
        return origin;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    /** 还原成可重新提交的 {@link Vote}（含签名与公钥）。 */
    public Vote toVote() {
        return new Vote(epoch, checkpointHash, validatorAddress, signature, publicKey);
    }

    /**
     * 与另一条 outbox 记录是否指向同一张票（用于去重与"内存里已有就不必再从库里捞"）。
     */
    public boolean sameVote(FinalityOutboxEntry other) {
        return other != null
                && epoch == other.epoch
                && origin == other.origin
                && validatorAddress != null
                && validatorAddress.equals(other.validatorAddress)
                && Arrays.equals(checkpointHash, other.checkpointHash);
    }

    @Override
    public String toString() {
        return "FinalityOutboxEntry{epoch=" + epoch + ", validator=" + validatorAddress
                + ", origin=" + origin.code() + ", attempts=" + attempts + "}";
    }
}
