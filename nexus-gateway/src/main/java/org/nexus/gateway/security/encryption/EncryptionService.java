package org.nexus.gateway.security.encryption;

import io.micrometer.core.instrument.Timer;
import org.nexus.gateway.security.audit.SecurityMetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 字段级加密服务。
 *
 * <p>提供业务数据的加密/解密操作，使用 AES-256-GCM（AEAD）算法：
 * <ul>
 *   <li><b>机密性</b> — AES-256 对称加密</li>
 *   <li><b>完整性</b> — GCM 128-bit 认证标签，解密时自动验证，篡改数据抛
 *       {@code AEADBadTagException}</li>
 *   <li><b>IV 唯一性</b> — 每次加密使用 CSPRNG 生成的 96-bit IV</li>
 * </ul>
 *
 * <p><b>fail-closed 策略</b>：解密认证标签验证失败时拒绝返回数据，
 * 不允许静默降级为返回密文或空值。
 * 来源：经验 2026-09-10-java-springboot-oidc-fallback-hmac-fail-closed-fix</p>
 *
 * <p>设计文档 §5.1.1 — AES-256-GCM 作为唯一字段级加密算法。</p>
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;   // 96-bit IV (NIST 推荐)
    private static final int GCM_TAG_LENGTH = 128;  // 128-bit 认证标签

    private final SecureRandom secureRandom;

    /**
     * 业务指标（可空）：单元测试可直接 {@code new EncryptionService()}，不强制注入。
     *
     * <p>2026-11-08 接线前，{@code SecurityMetricsRecorder} 的两个加密 meter
     * 在全仓没有任何真实调用点 —— {@code getEncryptionLatencyTimer()} 无调用方，
     * {@code recordEncryptionOperation()} 的唯一 grep 命中是
     * {@code SecurityAuditService} 里**同名不同类**的审计方法。属"注册了没人喂"
     * 缺陷：{@code /actuator/prometheus} 能看到序列，值恒为 0。</p>
     */
    private SecurityMetricsRecorder securityMetricsRecorder;

    @Autowired(required = false)
    public void setSecurityMetricsRecorder(SecurityMetricsRecorder securityMetricsRecorder) {
        this.securityMetricsRecorder = securityMetricsRecorder;
    }

    public EncryptionService() throws Exception {
        this.secureRandom = SecureRandom.getInstanceStrong();
    }

    /**
     * 加密明文字段 — 使用 DEK 执行 AES-256-GCM 加密。
     *
     * <p>流程：
     * <ol>
     *   <li>CSPRNG 生成 96-bit IV</li>
     *   <li>使用 DEK + IV 初始化 GCM Cipher（128-bit auth tag）</li>
     *   <li>加密明文，GCM 模式下认证标签自动附加在密文末尾</li>
     * </ol>
     *
     * @param plaintext 明文内容
     * @param dek       数据加密密钥（32 字节 AES-256 密钥）
     * @return {@link EncryptedField} 包含密文和 IV
     * @throws EncryptionException 如果加密失败
     */
    public EncryptedField encrypt(String plaintext, byte[] dek) {
        if (plaintext == null) {
            return null;
        }
        Timer.Sample sample = startMetricsSample();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            SecretKey key = new SecretKeySpec(dek, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // GCM 模式下认证标签附加在密文末尾
            return new EncryptedField(ciphertext, iv);
        } catch (Exception e) {
            log.error("字段加密失败 — fail-closed", e);
            throw new EncryptionException("ENCRYPTION_KEY_UNAVAILABLE", "字段加密失败", e);
        } finally {
            stopMetricsSample(sample);
        }
    }

    /**
     * 解密密文字段 — 使用 DEK 和 IV 执行 AES-256-GCM 解密。
     *
     * <p>GCM 解密时自动验证认证标签，验证失败抛 {@code AEADBadTagException}，
     * 表示数据被篡改或密钥不正确。此异常被封装为 {@link EncryptionException}
     * （错误码 DATA_INTEGRITY_VIOLATION），fail-closed 拒绝返回数据。</p>
     *
     * @param ciphertext 密文（含附加的 auth tag）
     * @param iv         初始化向量
     * @param dek        数据加密密钥
     * @return 解密后的明文
     * @throws EncryptionException 如果解密失败或认证标签验证失败
     */
    public String decrypt(byte[] ciphertext, byte[] iv, byte[] dek) {
        if (ciphertext == null || iv == null) {
            return null;
        }
        Timer.Sample sample = startMetricsSample();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            SecretKey key = new SecretKeySpec(dek, "AES");
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("认证降级：数据完整性验证失败 — fail-closed，拒绝返回数据", e);
            throw new EncryptionException("DATA_INTEGRITY_VIOLATION", "数据完整性验证失败", e);
        } finally {
            stopMetricsSample(sample);
        }
    }

    /**
     * 业务指标采样起点。未注入 recorder（纯单测）时返回 {@code null}，调用方无副作用。
     */
    private Timer.Sample startMetricsSample() {
        return securityMetricsRecorder == null ? null : Timer.start();
    }

    /**
     * 结束采样并计一次加密/解密操作。
     *
     * <p><b>口径</b>：计入所有**已完成的尝试**（成功与失败都算）—— 这是
     * {@code nexus_encryption_operations_total} 的字面语义「操作总数」。
     * 因此本计数器不能直接当成功率分母；失败率目前**没有**独立 meter
     * （见 {@code docs/ops/slo.md} 的缺口清单），需要时应新增
     * {@code nexus_encryption_failures_total}，而不是在本计数器上做减法。</p>
     *
     * <p>{@link #decryptFromBase64} 委托给 {@link #decrypt}，**只**在 {@code decrypt}
     * 内计数，避免一次 Base64 解密被计两次。</p>
     */
    private void stopMetricsSample(Timer.Sample sample) {
        if (sample == null || securityMetricsRecorder == null) {
            return;
        }
        sample.stop(securityMetricsRecorder.getEncryptionLatencyTimer());
        securityMetricsRecorder.recordEncryptionOperation();
    }

    /**
     * 解密密文字段（Base64 编码输入）。
     *
     * @param ciphertextBase64 Base64 编码的密文
     * @param ivBase64          Base64 编码的 IV
     * @param dek               数据加密密钥
     * @return 解密后的明文
     */
    public String decryptFromBase64(String ciphertextBase64, String ivBase64, byte[] dek) {
        if (ciphertextBase64 == null || ivBase64 == null) {
            return null;
        }
        byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);
        byte[] iv = Base64.getDecoder().decode(ivBase64);
        return decrypt(ciphertext, iv, dek);
    }
}