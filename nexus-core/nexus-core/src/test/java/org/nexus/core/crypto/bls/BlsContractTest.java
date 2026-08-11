package org.nexus.core.crypto.bls;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BLS 接口契约测试（NexFinality M2 验收模板）。
 *
 * <p>当前所有用例 @Disabled：等待 P1/M2 引入 Supranational blst 真实实现后启用。
 * 在此之前，BlsSigner.generate() 会抛 UnsupportedOperationException，
 * 这是符合预期的「接口先行」占位行为。</p>
 *
 * <p>M2 启用条件：build.gradle 解除 blst 注释 + 引入 BlstBlsSigner 实现。</p>
 */
class BlsContractTest {

    @Test
    @Disabled("P1/M2: 等待 blst 真实实现（当前接口仅定义协议面）")
    void generateAndSignRoundTrip() {
        BlsSigner signer = BlsSigner.generate();
        BlsSignature sig = signer.sign("hello-nexus".getBytes());
        assertNotNull(sig);
        assertNotNull(sig.toBytesCompressed());
        assertTrue(sig.toBytesCompressed().length > 0);
    }

    @Test
    @Disabled("M2: blst 实现后启用聚合签名验证")
    void aggregateSignaturesConstantSize() {
        // M2 目标：N 个签名聚合后仍为 96 字节常数大小
        fail("awaiting blst implementation");
    }

    @Test
    @Disabled("M2: blst 实现后启用跨节点验签一致性测试")
    void crossNodeVerificationCompatible() {
        fail("awaiting blst implementation");
    }

    /**
     * 当前接口的诚实占位断言：generate() 尚未实现。
     * 若此断言失败，说明 M2 已落地（届时应删除本用例）。
     */
    @Test
    void signerGenerationIsStubbedUntilM2() {
        assertThrows(UnsupportedOperationException.class, BlsSigner::generate);
    }
}
