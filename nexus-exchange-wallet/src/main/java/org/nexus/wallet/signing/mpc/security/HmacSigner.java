package org.nexus.wallet.signing.mpc.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * HMAC-SHA256 消息签名器。
 *
 * <p>对每条 {@code MpcMessage} 计算 HMAC-SHA256 签名，防止消息在传输过程中
 * 被篡改。HMAC 密钥（Message Authentication Key，MAK）独立于 mTLS 证书，
 * 提供应用层消息完整性保护（即使 TLS 终止于反向代理也仍然有效）。</p>
 *
 * <p><b>签名内容</b>（按固定顺序拼接，UTF-8 编码）：</p>
 * <pre>
 *   messageId | sessionId | round | type | fromParticipantId
 *   | toParticipantId | payloadHex | timestamp | nonce
 * </pre>
 *
 * <p>注意：HMAC 本身不签名 hmacHex 字段（避免循环）。</p>
 *
 * <p><b>密钥来源</b>：环境变量 {@code NEXUS_MPC_MAK}（base64 编码的 32 字节 MAK）。
 * 生产环境应通过 KMS 派生 per-session MAK。</p>
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec mak;

    /**
     * 构造 HMAC 签名器。
     *
     * @param makBase64 base64 编码的 32 字节 MAK；若为 {@code null} 则从
     *                  环境变量 {@code NEXUS_MPC_MAK} 读取
     */
    public HmacSigner(String makBase64) {
        if (makBase64 == null || makBase64.isEmpty()) {
            makBase64 = System.getenv("NEXUS_MPC_MAK");
        }
        if (makBase64 == null || makBase64.isEmpty()) {
            throw new IllegalStateException(
                    "MPC MAK not configured: set env NEXUS_MPC_MAK or pass base64(32-byte MAK)");
        }
        byte[] keyBytes = Base64.getDecoder().decode(makBase64);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "MPC MAK must be >= 32 bytes, got " + keyBytes.length);
        }
        this.mak = new SecretKeySpec(keyBytes, ALGORITHM);
        Arrays.fill(keyBytes, (byte) 0);
    }

    /** 从环境变量构造。 */
    public HmacSigner() {
        this(null);
    }

    /**
     * 计算指定字段的 HMAC-SHA256 签名。
     *
     * @param messageId         消息 ID
     * @param sessionId         会话 ID
     * @param round             轮次
     * @param type              消息类型名
     * @param fromParticipantId 发送者 ID
     * @param toParticipantId   接收者 ID（可为 null）
     * @param payloadHex        消息体
     * @param timestamp         时间戳
     * @param nonce             随机数
     * @return HMAC 签名（hex 编码）
     */
    public String sign(String messageId, String sessionId, int round, String type,
                       String fromParticipantId, String toParticipantId,
                       String payloadHex, long timestamp, String nonce) {
        String canonical = canonicalize(messageId, sessionId, String.valueOf(round), type,
                fromParticipantId, toParticipantId, payloadHex, String.valueOf(timestamp), nonce);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(mak);
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 sign failed", e);
        }
    }

    /**
     * 验证 HMAC 签名（常量时间比较，防时序攻击）。
     *
     * @param expectedHex 期望的 HMAC（hex）
     * @param args        同 {@link #sign} 的参数
     * @return {@code true} iff 签名匹配
     */
    public boolean verify(String expectedHex, String... args) {
        String actual = sign(args[0], args[1], Integer.parseInt(args[2]), args[3],
                args[4], args[5], args[6], Long.parseLong(args[7]), args[8]);
        return MessageDigest.isEqual(
                fromHex(actual), fromHex(expectedHex));
    }

    private static String canonicalize(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(fields[i] == null ? "" : fields[i]);
        }
        return sb.toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}