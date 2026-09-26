package org.nexus.gateway.security.encryption;

import java.util.Arrays;
import java.util.Base64;

/**
 * 加密结果 DTO — 包含密文和 IV。
 *
 * <p>AES-256-GCM 加密输出包含：
 * <ul>
 *   <li>{@code ciphertext} — 加密后的密文（GCM 模式下认证标签附加在密文末尾）</li>
 *   <li>{@code iv} — 96-bit 初始化向量（每次加密由 CSPRNG 生成）</li>
 * </ul>
 *
 * <p>解密时需同时提供 ciphertext 和 iv，GCM 模式自动验证认证标签。</p>
 */
public class EncryptedField {

    private final byte[] ciphertext;
    private final byte[] iv;

    public EncryptedField(byte[] ciphertext, byte[] iv) {
        this.ciphertext = ciphertext;
        this.iv = iv;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public byte[] getIv() {
        return iv;
    }

    /**
     * 获取 Base64 编码的密文（用于存储到数据库文本列）。
     */
    public String getCiphertextBase64() {
        return Base64.getEncoder().encodeToString(ciphertext);
    }

    /**
     * 获取 Base64 编码的 IV。
     */
    public String getIvBase64() {
        return Base64.getEncoder().encodeToString(iv);
    }

    /**
     * 从 Base64 编码的字符串构造 EncryptedField。
     */
    public static EncryptedField fromBase64(String ciphertextBase64, String ivBase64) {
        byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);
        byte[] iv = Base64.getDecoder().decode(ivBase64);
        return new EncryptedField(ciphertext, iv);
    }

    @Override
    public String toString() {
        return "EncryptedField{iv=" + Arrays.hashCode(iv) +
                ", ciphertextLength=" + ciphertext.length + "}";
    }
}