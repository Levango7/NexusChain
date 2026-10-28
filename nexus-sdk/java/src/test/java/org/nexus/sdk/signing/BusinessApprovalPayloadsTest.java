package org.nexus.sdk.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 canonical 载荷摘要契约测试（nexus-sdk 共享层，signing / wallet 双侧同源）。
 *
 * <p>钉住：确定性、金额归一（{@code 50000} == {@code 50000.00} == {@code 5E+4}）、
 * 任一字段变化必须改变摘要、输出为小写 64 位 hex。</p>
 */
class BusinessApprovalPayloadsTest {

    private static final String FROM = "0xplatform0000000000000000000000deadbeef";
    private static final String TO = "0xrecipienthash0011";
    private static final String ID = "WD-a2-0001";

    @Test
    @DisplayName("确定性：同输入同输出")
    void deterministic() {
        String a = BusinessApprovalPayloads.canonicalHash(FROM, TO, new BigDecimal("50000"), ID);
        String b = BusinessApprovalPayloads.canonicalHash(FROM, TO, new BigDecimal("50000"), ID);
        assertEquals(a, b);
    }

    @Test
    @DisplayName("金额归一：50000 / 50000.00 / 5E+4 同摘要")
    void amountNormalized() {
        String plain = BusinessApprovalPayloads.canonicalHash(FROM, TO, new BigDecimal("50000"), ID);
        String withZeros = BusinessApprovalPayloads.canonicalHash(FROM, TO, new BigDecimal("50000.00"), ID);
        String scientific = BusinessApprovalPayloads.canonicalHash(FROM, TO, new BigDecimal("5E+4"), ID);
        assertEquals(plain, withZeros);
        assertEquals(plain, scientific);
    }

    @Test
    @DisplayName("任一字段变化 → 摘要变化（防载荷替换的核心性质）")
    void fieldChangeChangesDigest() {
        String base = BusinessApprovalPayloads.canonicalHash(FROM, TO, new BigDecimal("50000"), ID);
        assertNotEquals(base, BusinessApprovalPayloads.canonicalHash(FROM, TO, new BigDecimal("50001"), ID),
                "改金额必须改变摘要");
        assertNotEquals(base, BusinessApprovalPayloads.canonicalHash("0xother", TO, new BigDecimal("50000"), ID),
                "改转出公钥必须改变摘要");
        assertNotEquals(base, BusinessApprovalPayloads.canonicalHash(FROM, "0xothertarget", new BigDecimal("50000"), ID),
                "改收款人必须改变摘要");
        assertNotEquals(base, BusinessApprovalPayloads.canonicalHash(FROM, TO, new BigDecimal("50000"), "WD-a2-0002"),
                "换单号必须改变摘要");
    }

    @Test
    @DisplayName("输出格式：64 位小写 hex")
    void outputFormat() {
        String hash = BusinessApprovalPayloads.canonicalHash(FROM, TO, new BigDecimal("50000"), ID);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "应为小写 hex: " + hash);
    }

    @Test
    @DisplayName("归一函数：null → \"null\"；0.00 → \"0\"")
    void normalizeAmountEdgeCases() {
        assertEquals("null", BusinessApprovalPayloads.normalizeAmount(null));
        assertEquals("0", BusinessApprovalPayloads.normalizeAmount(new BigDecimal("0.00")));
        assertEquals("50000", BusinessApprovalPayloads.normalizeAmount(new BigDecimal("5E+4")),
                "toPlainString 必须还原科学计数法");
    }
}
