package org.nexus.l2.blob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EIP-4844 blob 数据携带器默认实现。
 *
 * <p>封装 blob 提交与可用性验证。当前为<b>模拟实现</b>：使用 SHA-256 模拟 KZG 承诺/证明，
 * 不依赖真实 BLS12-381 库。生产环境可替换为对接 L1 KZG 预编译合约的真实实现。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>blob 字段元素编码：将批次数据按 32 字节切分填入 4096 个字段元素，不足补零</li>
 *   <li>KZG 承诺模拟：C = SHA-256("KZG_COMMIT" ‖ blob)</li>
 *   <li>KZG 证明模拟：π = SHA-256("KZG_PROOF" ‖ blob ‖ z)，z 为固定评估点</li>
 *   <li>版本化哈希：h = 0x01 ‖ SHA-256(C)[1..32]（EIP-4844 规定 version=0x01）</li>
 *   <li>可用性验证：重新计算 C'、π' 并比对，模拟预编译校验</li>
 *   <li>blob base fee：可配置，默认 1 wei，模拟 EIP-4844 blob base fee market</li>
 * </ul>
 *
 * <p>本实现保证语义正确性（commit/proof/verify 自洽），可被欺诈证明测试覆盖；
 * 替换为真实 KZG 时只需修改 commit/proof/verify 三个内部方法。</p>
 *
 * @since 1.3
 */
@Component
public class Eip4844BlobCarrier implements BlobDataCarrier {

    private static final Logger logger = LoggerFactory.getLogger(Eip4844BlobCarrier.class);

    /** 默认 blob base fee（wei per blob gas） */
    private static final long DEFAULT_BLOB_BASE_FEE = 1L;

    /** KZG 评估点 z（模拟固定值，BLS12-381 域元素） */
    private static final byte[] KZG_EVALUATION_POINT = sha256("KZG_EVAL_POINT".getBytes(StandardCharsets.UTF_8));

    /** 版本化哈希版本前缀（EIP-4844 规定 0x01） */
    private static final byte VERSION_PREFIX = 0x01;

    /** 默认 blob base fee */
    private final long defaultBlobBaseFee;

    /** 已携带的 blob：batchId -> result */
    private final Map<Long, BlobCarrierResult> carriedBlobs = new ConcurrentHashMap<>();

    /** L1 blob base fee（可动态更新，模拟 EIP-4844 fee market） */
    private volatile long currentBlobBaseFee;

    public Eip4844BlobCarrier() {
        this(DEFAULT_BLOB_BASE_FEE);
    }

    public Eip4844BlobCarrier(long defaultBlobBaseFee) {
        this.defaultBlobBaseFee = defaultBlobBaseFee;
        this.currentBlobBaseFee = defaultBlobBaseFee;
    }

    @Override
    public BlobCarrierResult carryBatchData(long batchId, byte[] data) {
        if (data == null || data.length == 0) {
            logger.warn("carryBatchData: empty data for batch {}", batchId);
            return null;
        }
        // 1. 编码为 blob 字段元素（4096 × 32 字节，不足补零）
        byte[] blob = encodeToBlob(data);
        // 2. 计算 KZG 承诺
        String commitment = kzgCommit(blob);
        // 3. 计算 KZG 证明
        String proof = kzgProof(blob, KZG_EVALUATION_POINT);
        // 4. 计算版本化哈希
        String blobHash = versionedHash(commitment);
        // 5. 计算 blob gas 与成本
        long blobGasUsed = BlobCarrierResult.BLOB_GAS_PER_BLOB;
        long blobBaseFee = currentBlobBaseFee;

        BlobCarrierResult result = new BlobCarrierResult(
                batchId, blobHash, commitment, proof, blobBaseFee, blobGasUsed, blob);
        carriedBlobs.put(batchId, result);
        logger.info("Batch {} data carried via blob: hash={}, gasUsed={}, baseFee={}, cost={}",
                batchId, blobHash, blobGasUsed, blobBaseFee, result.getBlobCost());
        return result;
    }

    @Override
    public boolean verifyAvailability(long batchId, String blobHash) {
        BlobCarrierResult result = carriedBlobs.get(batchId);
        if (result == null) {
            logger.warn("verifyAvailability: no blob carried for batch {}", batchId);
            return false;
        }
        if (blobHash == null || !blobHash.equals(result.getBlobHash())) {
            logger.warn("verifyAvailability: blob hash mismatch for batch {} (expected={}, got={})",
                    batchId, result.getBlobHash(), blobHash);
            return false;
        }
        // 重新计算 KZG 承诺与证明并比对（模拟预编译校验）
        byte[] blob = result.getBlobData();
        if (blob == null) {
            // 仅凭 hash 校验（已携带且 hash 匹配即视为可用）
            logger.debug("verifyAvailability: blob data not retained, hash-only check for batch {}", batchId);
            return true;
        }
        String recomputedCommitment = kzgCommit(blob);
        String recomputedProof = kzgProof(blob, KZG_EVALUATION_POINT);
        boolean commitOk = recomputedCommitment.equals(result.getKzgCommitment());
        boolean proofOk = recomputedProof.equals(result.getKzgProof());
        boolean hashOk = versionedHash(recomputedCommitment).equals(blobHash);
        if (commitOk && proofOk && hashOk) {
            logger.info("Blob availability VERIFIED for batch {} (hash={})", batchId, blobHash);
            return true;
        }
        logger.warn("Blob availability FAILED for batch {} (commitOk={}, proofOk={}, hashOk={})",
                batchId, commitOk, proofOk, hashOk);
        return false;
    }

    @Override
    public long getBlobBaseFee() {
        return currentBlobBaseFee;
    }

    @Override
    public BlobCarrierResult getCarriedBlob(long batchId) {
        return carriedBlobs.get(batchId);
    }

    /**
     * 更新当前 blob base fee（模拟 EIP-4844 fee market 动态调整）。
     *
     * @param blobBaseFee 新的 blob base fee
     */
    public void setBlobBaseFee(long blobBaseFee) {
        if (blobBaseFee > 0) {
            this.currentBlobBaseFee = blobBaseFee;
        }
    }

    /**
     * 将原始数据编码为 blob 字段元素（4096 × 32 字节）。
     *
     * <p>数据按 32 字节切分填入字段元素，不足补零；超过单 blob 容量截断
     * （生产实现应分多 blob，本模拟仅支持单 blob）。</p>
     *
     * @param data 原始数据
     * @return blob 字节数组（长度固定 131072）
     */
    private byte[] encodeToBlob(byte[] data) {
        byte[] blob = new byte[BlobCarrierResult.BYTES_PER_BLOB];
        int copyLen = Math.min(data.length, BlobCarrierResult.BYTES_PER_BLOB);
        System.arraycopy(data, 0, blob, 0, copyLen);
        return blob;
    }

    /**
     * 计算 KZG 承诺（模拟实现）。
     *
     * <p>真实实现：C = Σ blob[i] × sᵢ，其中 sᵢ 为 trusted setup 中 G1 元素。
     * 模拟实现：C = SHA-256("KZG_COMMIT" ‖ blob)，输出 48 字节 hex（前 48 字节）。</p>
     *
     * @param blob blob 字节数据
     * @return KZG 承诺 hex 字符串（96 字符 = 48 字节）
     */
    private String kzgCommit(byte[] blob) {
        MessageDigest md = newDigest();
        md.update("KZG_COMMIT".getBytes(StandardCharsets.UTF_8));
        md.update(blob);
        return bytesToHex(md.digest()).substring(0, 96);
    }

    /**
     * 计算 KZG 证明（模拟实现）。
     *
     * <p>真实实现：π = (commit(blob) - y) / (X - z)，其中 z 为评估点、y = blob(z)。
     * 模拟实现：π = SHA-256("KZG_PROOF" ‖ blob ‖ z)，输出 48 字节 hex。</p>
     *
     * @param blob blob 字节数据
     * @param z    评估点
     * @return KZG 证明 hex 字符串（96 字符 = 48 字节）
     */
    private String kzgProof(byte[] blob, byte[] z) {
        MessageDigest md = newDigest();
        md.update("KZG_PROOF".getBytes(StandardCharsets.UTF_8));
        md.update(blob);
        md.update(z);
        return bytesToHex(md.digest()).substring(0, 96);
    }

    /**
     * 计算版本化哈希（EIP-4844 规定）。
     *
     * <p>h = 0x01 ‖ SHA-256(commitment)[1..32]，共 32 字节。</p>
     *
     * @param commitment KZG 承诺 hex
     * @return 版本化哈希 hex（64 字符 = 32 字节）
     */
    private String versionedHash(String commitment) {
        byte[] commitBytes = hexToBytes(commitment);
        byte[] hash = sha256(commitBytes);
        hash[0] = VERSION_PREFIX;
        return bytesToHex(hash);
    }

    private static byte[] sha256(byte[] data) {
        MessageDigest md = newDigest();
        return md.digest(data);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}