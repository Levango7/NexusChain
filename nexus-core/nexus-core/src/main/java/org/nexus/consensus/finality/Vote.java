package org.nexus.consensus.finality;

/**
 * BFT 检查点投票记录。
 *
 * <p>一条 Vote 代表一个验证者对某个 epoch 检查点区块的最终化投票。
 * 签名使用验证人注册的 Ed25519 密钥产生（P0-1 审计修复：替代早期
 * 可伪造的 BLS-like 构造，该构造类已删除）；{@code validatorPublicKey}
 * 承载投票者公钥，供聚合器逐一验签与 FinalityGadget 注册表绑定校验。</p>
 */
public final class Vote {

    private final long epoch;
    private final byte[] checkpointHash;
    private final String validatorAddress;
    private final byte[] signature;
    /**
     * 验证者 BLS 公钥（压缩字节）。
     *
     * <p>M3 阶段引入：聚合器在 {@code verifyAggregate} 中通过此公钥做完整 BLS 验签。
     * 可为 {@code null}（M1/M2 兼容路径，仅做格式校验）。</p>
     */
    private final byte[] validatorPublicKey;

    public Vote(long epoch, byte[] checkpointHash, String validatorAddress, byte[] signature) {
        this(epoch, checkpointHash, validatorAddress, signature, null);
    }

    public Vote(long epoch, byte[] checkpointHash, String validatorAddress, byte[] signature, byte[] validatorPublicKey) {
        this.epoch = epoch;
        this.checkpointHash = checkpointHash;
        this.validatorAddress = validatorAddress;
        this.signature = signature;
        this.validatorPublicKey = validatorPublicKey;
    }

    public long getEpoch() { return epoch; }
    public byte[] getCheckpointHash() { return checkpointHash; }
    public String getValidatorAddress() { return validatorAddress; }
    public byte[] getSignature() { return signature; }

    /**
     * 返回验证者 BLS 公钥压缩字节，未携带时返回 {@code null}。
     *
     * <p>聚合器据此判断是否走完整 BLS 验签路径；为 {@code null} 时回退到格式校验。</p>
     */
    public byte[] getPublicKeyBytes() { return validatorPublicKey; }

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
