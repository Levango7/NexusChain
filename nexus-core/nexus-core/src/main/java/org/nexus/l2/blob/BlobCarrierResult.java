package org.nexus.l2.blob;

import java.util.Arrays;

/**
 * EIP-4844 blob 数据携带结果。
 *
 * <p>记录批次数据通过 blob 提交到 L1 后的可用性凭据，包含：</p>
 * <ul>
 *   <li>{@code blobHash}：blob 的版本化哈希（versioned hash，0x01 前缀 + KZG commitment 哈希后 31 字节），
 *       供 blob tx 引用，并作为 DA 凭据索引</li>
 *   <li>{@code kzgCommitment}：blob 多项式的 KZG 承诺（48 字节 BLS12-381 G1 点）</li>
 *   <li>{@code kzgProof}：KZG 证明（48 字节 BLS12-381 G1 点），用于验证 blob 在评估点的求值</li>
 *   <li>{@code blobBaseFee}：blob 的 base fee（单位 wei per blob gas）</li>
 *   <li>{@code blobGasUsed}：blob gas 消耗（每 blob 131072 gas）</li>
 *   <li>{@code blobData}：原始 blob 字段元素字节数据（4096 × 32 字节）</li>
 * </ul>
 *
 * <p>验证者通过 {@link BlobDataCarrier#verifyAvailability} 校验
 * (blob, commitment, proof) 三元组的 KZG 关系，确认数据可用。</p>
 *
 * @since 1.3
 */
public class BlobCarrierResult {

    /** 单个 blob 的 gas 消耗（EIP-4844 规定 131072） */
    public static final long BLOB_GAS_PER_BLOB = 131_072L;

    /** blob 字段元素个数（EIP-4844 规定 4096） */
    public static final int FIELD_ELEMENTS_PER_BLOB = 4096;

    /** 单个字段元素字节长度（BLS12-381 模数 32 字节） */
    public static final int BYTES_PER_FIELD_ELEMENT = 32;

    /** 单个 blob 字节长度 = 4096 × 32 = 131072 */
    public static final int BYTES_PER_BLOB = FIELD_ELEMENTS_PER_BLOB * BYTES_PER_FIELD_ELEMENT;

    /** 批次 ID */
    private final long batchId;

    /** blob 版本化哈希（0x01 前缀 + commitment 哈希后 31 字节，hex 字符串） */
    private final String blobHash;

    /** KZG 承诺（48 字节 G1 点，hex 字符串） */
    private final String kzgCommitment;

    /** KZG 证明（48 字节 G1 点，hex 字符串） */
    private final String kzgProof;

    /** blob base fee（wei per blob gas） */
    private final long blobBaseFee;

    /** blob gas 消耗 */
    private final long blobGasUsed;

    /** 原始 blob 数据（可能为 null，仅本地保留用于可用性验证） */
    private final byte[] blobData;

    public BlobCarrierResult(long batchId, String blobHash, String kzgCommitment,
                             String kzgProof, long blobBaseFee, long blobGasUsed, byte[] blobData) {
        this.batchId = batchId;
        this.blobHash = blobHash;
        this.kzgCommitment = kzgCommitment;
        this.kzgProof = kzgProof;
        this.blobBaseFee = blobBaseFee;
        this.blobGasUsed = blobGasUsed;
        this.blobData = blobData == null ? null : blobData.clone();
    }

    public long getBatchId() {
        return batchId;
    }

    public String getBlobHash() {
        return blobHash;
    }

    public String getKzgCommitment() {
        return kzgCommitment;
    }

    public String getKzgProof() {
        return kzgProof;
    }

    public long getBlobBaseFee() {
        return blobBaseFee;
    }

    public long getBlobGasUsed() {
        return blobGasUsed;
    }

    public byte[] getBlobData() {
        return blobData == null ? null : blobData.clone();
    }

    /**
     * 计算 blob 提交的 L1 成本（blob gas × blob base fee）。
     *
     * @return L1 blob 提交成本（wei）
     */
    public long getBlobCost() {
        return blobGasUsed * blobBaseFee;
    }

    @Override
    public String toString() {
        return "BlobCarrierResult{batchId=" + batchId
                + ", blobHash=" + blobHash
                + ", blobGasUsed=" + blobGasUsed
                + ", blobBaseFee=" + blobBaseFee
                + ", blobCost=" + getBlobCost() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlobCarrierResult)) {
            return false;
        }
        BlobCarrierResult that = (BlobCarrierResult) o;
        return batchId == that.batchId && blobGasUsed == that.blobGasUsed
                && blobBaseFee == that.blobBaseFee
                && java.util.Objects.equals(blobHash, that.blobHash)
                && java.util.Objects.equals(kzgCommitment, that.kzgCommitment)
                && java.util.Objects.equals(kzgProof, that.kzgProof)
                && Arrays.equals(blobData, that.blobData);
    }

    @Override
    public int hashCode() {
        int h = java.util.Objects.hash(batchId, blobHash, kzgCommitment, kzgProof, blobBaseFee, blobGasUsed);
        h = 31 * h + Arrays.hashCode(blobData);
        return h;
    }
}