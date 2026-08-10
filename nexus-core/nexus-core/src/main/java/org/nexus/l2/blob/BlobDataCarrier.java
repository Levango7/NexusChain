package org.nexus.l2.blob;

/**
 * Blob 数据携带器接口（EIP-4844）。
 *
 * <p>定义 L2 批次数据通过 EIP-4844 blob 携带到 L1 的能力，替代传统 calldata，
 * 显著降低 L1 settlement 成本（blob gas 单价远低于 calldata gas）。</p>
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>{@link #carryBatchData}：将批次数据编码为 blob，生成 KZG 承诺/证明，提交到 L1</li>
 *   <li>{@link #verifyAvailability}：验证 (blob, commitment, proof) 三元组满足 KZG 关系，
 *       确认数据可用，无需下载完整 blob</li>
 *   <li>{@link #getBlobBaseFee}：查询当前 blob base fee，供排序器/打包器决策</li>
 * </ul>
 *
 * <p>实现者可对接真实 L1 KZG 预编译合约（point_evaluation_precompile @ 0x0a），
 * 或使用模拟实现（如 {@link Eip4844BlobCarrier}）。</p>
 *
 * @since 1.3
 */
public interface BlobDataCarrier {

    /**
     * 将批次数据通过 blob 携带到 L1。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>将 {@code data} 编码为 blob 字段元素（4096 × 32 字节，不足补零）</li>
     *   <li>计算 KZG 承诺 C = commit(blob)</li>
     *   <li>计算 KZG 证明 π = proof(blob, evaluationPoint)</li>
     *   <li>计算版本化哈希 h = 0x01 ‖ hash(C)[1..32]</li>
     *   <li>提交 (h, C, π) 到 L1 blob tx，支付 blob base fee × blob gas</li>
     * </ol>
     *
     * @param batchId 批次 ID
     * @param data    批次原始数据（RLP 编码的交易列表）
     * @return blob 携带结果；失败返回 null
     */
    BlobCarrierResult carryBatchData(long batchId, byte[] data);

    /**
     * 验证 blob 数据可用性。
     *
     * <p>验证 (blobHash, commitment, proof) 三元组满足 KZG 关系：
     * 调用 L1 point_evaluation_precompile 校验
     * {@code verify_kzg_proof(commitment, z, y, proof)}，
     * 其中 z 为评估点、y 为 blob(z)。</p>
     *
     * <p>验证通过即表明 blob 数据已发布到 L1 且可被任何节点通过
     * blob 采样（DAS）恢复，无需下载完整 blob。</p>
     *
     * @param batchId  批次 ID
     * @param blobHash blob 版本化哈希
     * @return 数据可用返回 true；不可用或未提交返回 false
     */
    boolean verifyAvailability(long batchId, String blobHash);

    /**
     * 查询当前 L1 blob base fee（wei per blob gas）。
     *
     * <p>供排序器/打包器决策是否使用 blob 携带：
     * 当 blob base fee × blob gas &lt; calldata gas × calldata base fee 时启用。</p>
     *
     * @return blob base fee
     */
    long getBlobBaseFee();

    /**
     * 获取指定批次的 blob 携带结果。
     *
     * @param batchId 批次 ID
     * @return blob 携带结果；未携带返回 null
     */
    BlobCarrierResult getCarriedBlob(long batchId);
}