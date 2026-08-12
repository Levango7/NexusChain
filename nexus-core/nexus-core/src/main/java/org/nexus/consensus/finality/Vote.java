package org.nexus.consensus.finality;

/**
 * BFT 检查点投票记录。
 *
 * <p>一条 Vote 代表一个验证者对某个 epoch 检查点区块的最终化投票。
 * M1/M2 阶段签名用现有 Ed25519 字节承载（接口先行），M3 阶段
 * 切换到 {@link org.nexus.core.crypto.bls.BlsSignature} 以实现聚合验签。</p>
 */
public final class Vote {

    private final long epoch;
    private final byte[] checkpointHash;
    private final String validatorAddress;
    private final byte[] signature;

    public Vote(long epoch, byte[] checkpointHash, String validatorAddress, byte[] signature) {
        this.epoch = epoch;
        this.checkpointHash = checkpointHash;
        this.validatorAddress = validatorAddress;
        this.signature = signature;
    }

    public long getEpoch() { return epoch; }
    public byte[] getCheckpointHash() { return checkpointHash; }
    public String getValidatorAddress() { return validatorAddress; }
    public byte[] getSignature() { return signature; }

    /**
     * 投票签名内容（供验签与双签比对）。
     */
    public byte[] signingPayload() {
        // payload = epoch || checkpointHash
        byte[] payload = new byte[8 + checkpointHash.length];
        for (int i = 0; i < 8; i++) {
            payload[i] = (byte) (epoch >>> (56 - 8 * i));
        }
        System.arraycopy(checkpointHash, 0, payload, 8, checkpointHash.length);
        return payload;
    }
}
