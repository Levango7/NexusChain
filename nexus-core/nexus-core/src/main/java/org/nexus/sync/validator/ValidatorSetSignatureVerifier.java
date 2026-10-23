package org.nexus.sync.validator;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 验证人集消息签名器/验证器 —— PLAN-001 签名化扩展【第一切片】。
 *
 * <p>背景与边界（逐字对齐 {@code docs/plan/PLAN-001-signature-extension.md}）：
 * <ul>
 *   <li>承接 F1 修复（PLAN-030）的如实边界"中继未签名（沿用 PLAN-001 信任模型）"；
 *       本切片提供<b>纯 JDK 17 的 Ed25519 签名/验证内核与测试</b>，<b>不接线任何生产路径</b>
 *       （{@code SyncManager} 挂载、消息格式 v2 字段、星型广播灰度均为后续切片）。</li>
 *   <li>密钥编解码为 RFC 8410 raw 形态：32 字节种子（PKCS#8 DER 包装）与 32 字节公钥
 *       （X.509 SPKI 包装）——与现有 {@code validator-private-key} 的十六进制配置格式
 *       （64 字符 = 32 字节）直接兼容；亦兼容 64 字节 libsodium 形态（取前 32 字节种子）。</li>
 *   <li>算法无新外部依赖：{@code java.security} 内置 Ed25519（JDK 15+），哈希由算法内部完成
 *       （Ed25519 标准用法，直接对载荷字节签名，无额外预哈希步骤）。</li>
 *   <li>语义（与方案文档 §2.2 逐条一致）：无签名 → {@link SignatureCheckResult#NO_SIGNATURE}
 *       （向后兼容，继续处理）；无效签名 → {@link SignatureCheckResult#INVALID}
 *       （记录警告，不抛异常、不拒绝——行为差异仅在日志）；有效签名 → {@link SignatureCheckResult#VALID}
 *       （继续既有幂等注册路径）。</li>
 *   <li>重放检测为<b>纯函数</b>（{@code nonce <= lastSeen}），`lastSeen` 水位由调用方
 *       从既有 {@code validators_synced.nonce} 提供（与现有顺序水位字段对齐，不引入新状态表）。</li>
 * </ul>
 *
 * <p>并发面：全部为无状态纯函数（无共享可变状态、无新线程、无跨线程可见性面）——
 * 与 Fifo 修复（PLAN-031）的"不新增跨线程面"口径一致。
 */
public final class ValidatorSetSignatureVerifier {

    /** RFC 8410：raw 32 字节 Ed25519 种子 → PKCS#8 PrivateKeyInfo 的 DER 前置（16 字节）。 */
    private static final byte[] PKCS8_PREFIX = {
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    };

    /** RFC 8410：raw 32 字节 Ed25519 公钥 → X.509 SubjectPublicKeyInfo 的 DER 前置（12 字节）。 */
    private static final byte[] X509_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    /** 检查结果三分语义（与方案文档 §2.2 逐条一致）。 */
    public enum SignatureCheckResult {
        /** 有效签名：继续既有注册路径（不增加新行为）。 */
        VALID,
        /** 无效签名：记录警告，不影响现有流程（不拒绝消息，向后兼容）。 */
        INVALID,
        /** 无签名：继续处理（向后兼容；日志可标记 unsigned 便于迁移监控）。 */
        NO_SIGNATURE
    }

    /**
     * 对载荷签名（发送侧）。
     *
     * @param payload        待签名载荷字节（例如 TRANSACTION 广播体，不含签名字段本身）
     * @param privateKeyHex  32 字节种子的十六进制（64 字符）；兼容 64 字节（128 字符）形态（取前 32 字节）
     * @return base64 编码的 64 字节 Ed25519 签名
     * @throws IllegalArgumentException 十六进制格式非法或种子长度不是 32/64 字节
     */
    public String sign(byte[] payload, String privateKeyHex) {
        byte[] raw;
        try {
            raw = HexFormat.of().parseHex(privateKeyHex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ed25519 key is not valid hex", e);
        }
        if (raw.length == 64) {
            raw = Arrays.copyOf(raw, 32);
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException("ed25519 seed must be 32 bytes (or 64 with public suffix): got " + raw.length);
        }
        try {
            PrivateKey sk = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(concat(PKCS8_PREFIX, raw)));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(sk);
            s.update(payload);
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException("ed25519 sign failed", e);
        }
    }

    /**
     * 验证（接收侧入口，三分语义；本方法不抛异常——任何格式错误归入 INVALID）。
     *
     * @param payload           载荷字节（须与签名侧逐字节一致）
     * @param expectedPubKeyHex 32 字节 Ed25519 公钥的十六进制（64 字符）
     * @param signatureBase64   base64 签名；null/空 → NO_SIGNATURE（向后兼容）
     */
    public SignatureCheckResult check(byte[] payload, String expectedPubKeyHex, String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isEmpty()) {
            return SignatureCheckResult.NO_SIGNATURE;
        }
        return verify(payload, expectedPubKeyHex, signatureBase64)
            ? SignatureCheckResult.VALID
            : SignatureCheckResult.INVALID;
    }

    /**
     * 逐字节 Ed25519 验证（内部实现：raw 公钥 → SPKI 包装 → 验签；任何异常返回 false）。
     */
    public boolean verify(byte[] payload, String pubKeyHex, String signatureBase64) {
        try {
            byte[] raw = HexFormat.of().parseHex(pubKeyHex);
            if (raw.length != 32) {
                return false;
            }
            PublicKey pk = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(concat(X509_PREFIX, raw)));
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(pk);
            v.update(payload);
            return v.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 重放检测（纯函数）：{@code nonce <= lastSeen} 视为重放。
     *
     * <p>{@code lastSeenNonce} 由调用方从既有水位提供（{@code validators_synced.nonce}），
     * 本方法不查库、无状态——DB 对接留给接线切片（与"不引入新状态表"边界一致）。
     */
    public boolean isReplay(long nonce, long lastSeenNonce) {
        return nonce <= lastSeenNonce;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
