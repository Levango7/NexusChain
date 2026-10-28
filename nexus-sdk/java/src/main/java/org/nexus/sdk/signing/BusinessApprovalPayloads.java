package org.nexus.sdk.signing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A2 业务批准凭据的 canonical 载荷摘要（ADR-036 §2.2 / 设计草案 §2.3）。
 *
 * <p>登记（{@code POST /api/v1/signing/business-approvals}）与签名
 * （{@code POST /api/v1/transfers/sign?businessApprovalId=...}）两侧各自用
 * <b>同一函数</b>重算摘要并比对，杜绝"登记的是 A、签名的是 B"的载荷替换。
 * 放在 nexus-sdk 共享层：signing-service 与 wallet-service 各自实现必然漂移。</p>
 *
 * <p>canonical 形式（字段顺序固定，`|` 分隔）：</p>
 * <pre>
 * A2-v1 | fromPubkey | toPubkeyHash | amountNormalized | businessApprovalId
 * </pre>
 *
 * <p>金额归一：{@code stripTrailingZeros().toPlainString()}——{@code 50000} 与
 * {@code 50000.00} 必须得到同一摘要；{@code toPlainString} 保证不出现科学计数法
 * （{@code stripTrailingZeros} 对整数会产出 {@code 5E+4} 指数形式，必须用它还原）。
 * SHA-256 输出为小写 64 位 hex。</p>
 *
 * <p>安全边界（照设计草案 §3.1）：本摘要防的是<b>载荷篡改</b>（改金额/改收款人），
 * 不防来源伪造——来源可信度由服务间认证承担（与 {@code /transfers/sign} 同级信任）。</p>
 */
public final class BusinessApprovalPayloads {

    /** canonical 前缀（版本化：未来字段演进时换前缀，旧凭据自然失效）。 */
    public static final String PREFIX = "A2-v1";

    private BusinessApprovalPayloads() {
    }

    /**
     * 计算 A2 业务批准凭据的 canonical SHA-256 摘要（小写 64 位 hex）。
     *
     * @param fromPubkey         转出公钥（signing 侧会校验其等于平台公钥，此处原样参与摘要）
     * @param toPubkeyHash       转入公钥 hash
     * @param amount             金额（归一后参与摘要；null 视为字面量 "null"，登记端会另行拒绝）
     * @param businessApprovalId 业务单号（wallet 提现 requestId）
     * @return 小写 64 位 hex 摘要
     */
    public static String canonicalHash(String fromPubkey, String toPubkeyHash,
                                       BigDecimal amount, String businessApprovalId) {
        String canonical = PREFIX
                + "|" + (fromPubkey == null ? "null" : fromPubkey)
                + "|" + (toPubkeyHash == null ? "null" : toPubkeyHash)
                + "|" + normalizeAmount(amount)
                + "|" + (businessApprovalId == null ? "null" : businessApprovalId);
        return sha256Hex(canonical);
    }

    /**
     * 金额归一：{@code stripTrailingZeros().toPlainString()}。
     * null → "null"；0 / 0.00 → "0"。
     */
    public static String normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            return "null";
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String sha256Hex(String input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JDK 标准算法，不存在即环境损坏；包装为不可恢复错误
            throw new IllegalStateException("SHA-256 not available", e);
        }
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
