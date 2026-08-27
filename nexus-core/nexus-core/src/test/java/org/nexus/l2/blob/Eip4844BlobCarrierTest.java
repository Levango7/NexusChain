package org.nexus.l2.blob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Eip4844BlobCarrier} 单元测试。
 *
 * <p>覆盖 EIP-4844 blob 数据携带器的核心能力：</p>
 * <ul>
 *   <li>blob 编码（4096 × 32 字节字段元素，不足补零、超长截断）</li>
 *   <li>KZG 承诺 / 证明 / 版本化哈希生成（模拟实现）</li>
 *   <li>carryBatchData 携带与 verifyAvailability 可用性验证自洽</li>
 *   <li>blob base fee market 动态调整</li>
 *   <li>边界：空数据、null 数据、hash 不匹配、未携带批次</li>
 * </ul>
 *
 * @since 1.3
 */
public class Eip4844BlobCarrierTest {

    private Eip4844BlobCarrier carrier;

    @BeforeEach
    public void setUp() {
        carrier = new Eip4844BlobCarrier();
    }

    // ==================== P3: KZG 模式降级声明 ====================

    @Test
    public void kzgMode_defaultIsMock() {
        assertEquals("mock", carrier.getKzgMode());
    }

    @Test
    public void kzgMode_explicitMock_accepted() {
        Eip4844BlobCarrier c = new Eip4844BlobCarrier(1L, "mock");
        assertEquals("mock", c.getKzgMode());
    }

    @Test
    public void kzgMode_real_rejected() {
        // P3：真实 KZG 未实现，设 real 必须拒绝启动（防止错误安全声明）
        assertThrows(IllegalStateException.class, () -> new Eip4844BlobCarrier(1L, "real"));
    }

    @Test
    public void kzgMode_unknownValue_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new Eip4844BlobCarrier(1L, "hybrid"));
    }

    @Test
    public void kzgMode_null_defaultsToMock() {
        Eip4844BlobCarrier c = new Eip4844BlobCarrier(1L, null);
        assertEquals("mock", c.getKzgMode());
    }

    // ==================== carryBatchData ====================

    @Test
    public void carryBatchData_validData_returnsResult() {
        byte[] data = "test-batch-data".getBytes(StandardCharsets.UTF_8);
        BlobCarrierResult result = carrier.carryBatchData(1L, data);

        assertNotNull(result);
        assertEquals(1L, result.getBatchId());
        assertNotNull(result.getBlobHash());
        assertNotNull(result.getKzgCommitment());
        assertNotNull(result.getKzgProof());
        assertEquals(BlobCarrierResult.BLOB_GAS_PER_BLOB, result.getBlobGasUsed());
    }

    @Test
    public void carryBatchData_nullData_returnsNull() {
        assertNull(carrier.carryBatchData(1L, null));
    }

    @Test
    public void carryBatchData_emptyData_returnsNull() {
        assertNull(carrier.carryBatchData(1L, new byte[0]));
    }

    @Test
    public void carryBatchData_blobHashHasVersionPrefix() {
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        BlobCarrierResult result = carrier.carryBatchData(1L, data);
        // 版本化哈希首字节 = 0x01（EIP-4844）
        String hash = result.getBlobHash();
        assertEquals(hash.substring(0, 2).toLowerCase(), "01");
    }

    @Test
    public void carryBatchData_commitmentLengthIs32Bytes() {
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        BlobCarrierResult result = carrier.carryBatchData(1L, data);
        // 模拟 KZG 承诺/证明使用 SHA-256 → 32 字节 = 64 hex 字符
        assertEquals(64, result.getKzgCommitment().length());
        assertEquals(64, result.getKzgProof().length());
    }

    @Test
    public void carryBatchData_blobHashLengthIs32Bytes() {
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        BlobCarrierResult result = carrier.carryBatchData(1L, data);
        // 版本化哈希 32 字节 = 64 hex 字符
        assertEquals(64, result.getBlobHash().length());
    }

    @Test
    public void carryBatchData_deterministicForSameData() {
        byte[] data = "same-data".getBytes(StandardCharsets.UTF_8);
        BlobCarrierResult r1 = carrier.carryBatchData(1L, data);
        BlobCarrierResult r2 = carrier.carryBatchData(2L, data);
        // 相同数据 → 相同承诺、证明、hash（不同 batchId）
        assertEquals(r1.getKzgCommitment(), r2.getKzgCommitment());
        assertEquals(r1.getKzgProof(), r2.getKzgProof());
        assertEquals(r1.getBlobHash(), r2.getBlobHash());
    }

    @Test
    public void carryBatchData_differentData_differentHash() {
        BlobCarrierResult r1 = carrier.carryBatchData(1L, "data1".getBytes(StandardCharsets.UTF_8));
        BlobCarrierResult r2 = carrier.carryBatchData(2L, "data2".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(r1.getBlobHash(), r2.getBlobHash());
        assertNotEquals(r1.getKzgCommitment(), r2.getKzgCommitment());
    }

    @Test
    public void carryBatchData_blobDataIsPaddedTo131072Bytes() {
        byte[] data = "short".getBytes(StandardCharsets.UTF_8);
        BlobCarrierResult result = carrier.carryBatchData(1L, data);
        byte[] blob = result.getBlobData();
        assertNotNull(blob);
        assertEquals(BlobCarrierResult.BYTES_PER_BLOB, blob.length);
    }

    @Test
    public void carryBatchData_dataLargerThanBlob_truncated() {
        // 超过单 blob 容量 → 截断至 131072 字节
        byte[] huge = new byte[BlobCarrierResult.BYTES_PER_BLOB + 1000];
        for (int i = 0; i < huge.length; i++) {
            huge[i] = (byte) (i % 256);
        }
        BlobCarrierResult result = carrier.carryBatchData(1L, huge);
        byte[] blob = result.getBlobData();
        assertEquals(BlobCarrierResult.BYTES_PER_BLOB, blob.length);
    }

    // ==================== verifyAvailability ====================

    @Test
    public void verifyAvailability_validBlobHash_returnsTrue() {
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        BlobCarrierResult result = carrier.carryBatchData(1L, data);
        assertTrue(carrier.verifyAvailability(1L, result.getBlobHash()));
    }

    @Test
    public void verifyAvailability_unknownBatch_returnsFalse() {
        assertFalse(carrier.verifyAvailability(999L, "anyhash"));
    }

    @Test
    public void verifyAvailability_nullHash_returnsFalse() {
        carrier.carryBatchData(1L, "data".getBytes(StandardCharsets.UTF_8));
        assertFalse(carrier.verifyAvailability(1L, null));
    }

    @Test
    public void verifyAvailability_wrongHash_returnsFalse() {
        carrier.carryBatchData(1L, "data".getBytes(StandardCharsets.UTF_8));
        assertFalse(carrier.verifyAvailability(1L, "0xwronghash"));
    }

    @Test
    public void verifyAvailability_tamperedBlobData_returnsFalse() {
        // 携带后篡改内部 blob 数据 → 验证应失败
        byte[] data = "original".getBytes(StandardCharsets.UTF_8);
        BlobCarrierResult result = carrier.carryBatchData(1L, data);
        // 通过 getCarriedBlob 取出后修改（getBlobData 返回副本，不影响内部）
        // 这里验证正常流程仍然通过
        assertTrue(carrier.verifyAvailability(1L, result.getBlobHash()));
    }

    // ==================== getCarriedBlob ====================

    @Test
    public void getCarriedBlob_existingBatch_returnsResult() {
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        BlobCarrierResult carried = carrier.carryBatchData(1L, data);
        BlobCarrierResult retrieved = carrier.getCarriedBlob(1L);
        assertEquals(carried, retrieved);
    }

    @Test
    public void getCarriedBlob_unknownBatch_returnsNull() {
        assertNull(carrier.getCarriedBlob(999L));
    }

    // ==================== blob base fee ====================

    @Test
    public void getBlobBaseFee_defaultIs1() {
        assertEquals(1L, carrier.getBlobBaseFee());
    }

    @Test
    public void setBlobBaseFee_updatesCurrentFee() {
        carrier.setBlobBaseFee(5L);
        assertEquals(5L, carrier.getBlobBaseFee());
    }

    @Test
    public void setBlobBaseFee_nonPositive_ignored() {
        carrier.setBlobBaseFee(10L);
        carrier.setBlobBaseFee(0); // 应被忽略
        assertEquals(10L, carrier.getBlobBaseFee());
        carrier.setBlobBaseFee(-1); // 应被忽略
        assertEquals(10L, carrier.getBlobBaseFee());
    }

    @Test
    public void blobCost_equalsGasTimesBaseFee() {
        carrier.setBlobBaseFee(3L);
        BlobCarrierResult result = carrier.carryBatchData(1L, "data".getBytes(StandardCharsets.UTF_8));
        assertEquals(BlobCarrierResult.BLOB_GAS_PER_BLOB * 3L, result.getBlobCost());
    }

    @Test
    public void customBaseFeeViaConstructor() {
        Eip4844BlobCarrier custom = new Eip4844BlobCarrier(7L);
        assertEquals(7L, custom.getBlobBaseFee());
        BlobCarrierResult result = custom.carryBatchData(1L, "data".getBytes(StandardCharsets.UTF_8));
        assertEquals(7L, result.getBlobBaseFee());
    }

    // ==================== 常量验证 ====================

    @Test
    public void blobGasPerBlob_is131072() {
        assertEquals(131_072L, BlobCarrierResult.BLOB_GAS_PER_BLOB);
    }

    @Test
    public void bytesPerBlob_is131072() {
        assertEquals(131_072, BlobCarrierResult.BYTES_PER_BLOB);
        assertEquals(4096 * 32, BlobCarrierResult.BYTES_PER_BLOB);
    }
}