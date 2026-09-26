package org.nexus.gateway.security.encryption;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KEK/DEK 两层密钥管理服务。
 *
 * <p>采用 KEK（Key Encryption Key）/ DEK（Data Encryption Key）两层架构：
 * <ul>
 *   <li>KEK — 存储于环境变量 {@code NEXUS_ENCRYPTION_KEK} 或配置项
 *       {@code nexus.encryption.kek}，Base64 编码的 AES-256 密钥。
 *       版本化支持轮换，不直接接触业务数据。</li>
 *   <li>DEK — 每字段独立的数据加密密钥，由 KEK 加密后存储于数据库
 *       {@code encryption_key_metadata} 表。</li>
 * </ul>
 *
 * <p><b>fail-closed 策略</b>：KEK 未配置时 {@link #isAvailable()} 返回 false，
 * 所有加密操作将拒绝执行，不允许静默降级为明文存储。
 * 来源：经验 2026-09-10-java-springboot-oidc-fallback-hmac-fail-closed-fix</p>
 *
 * <p>DEK 缓存使用 {@link ConcurrentHashMap}（简化版，不依赖 Caffeine），
 * 缓存 key 为 metadataId，value 为明文 DEK。</p>
 */
@Service
public class KeyManagementService {

    private static final Logger log = LoggerFactory.getLogger(KeyManagementService.class);

    private static final String KEK_ENV_VAR = "NEXUS_ENCRYPTION_KEK";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;   // 96-bit IV (NIST 推荐)
    private static final int GCM_TAG_LENGTH = 128;  // 128-bit 认证标签
    private static final int KEY_LENGTH_BYTES = 32; // AES-256 = 256-bit = 32 bytes

    @Value("${nexus.encryption.kek:}")
    private String kekBase64;

    /** KEK 版本缓存：version → SecretKey */
    private final Map<Integer, SecretKey> kekCache = new ConcurrentHashMap<>();

    /** DEK 缓存：metadataId → 明文 DEK（简化版，不依赖 Caffeine） */
    private final Map<Long, byte[]> dekCache = new ConcurrentHashMap<>();

    private volatile Integer currentKekVersion;

    private final SecureRandom secureRandom;

    public KeyManagementService() throws Exception {
        this.secureRandom = SecureRandom.getInstanceStrong();
    }

    @PostConstruct
    void init() {
        // 优先从环境变量获取 KEK，其次从配置项
        String kekValue = System.getenv(KEK_ENV_VAR);
        if (kekValue == null || kekValue.isBlank()) {
            kekValue = kekBase64;
        }

        if (kekValue == null || kekValue.isBlank()) {
            log.error("KEK 未配置 (env: {}, config: nexus.encryption.kek) — fail-closed，拒绝写入加密字段",
                    KEK_ENV_VAR);
            // 不抛异常，但标记不可用；加密操作时 fail-closed
            currentKekVersion = null;
            return;
        }

        try {
            loadKek(1, kekValue);
            currentKekVersion = 1;
            log.info("KEK v1 已加载，加密服务可用");
        } catch (Exception e) {
            log.error("KEK 加载失败 — fail-closed", e);
            currentKekVersion = null;
        }
    }

    /**
     * 加载指定版本的 KEK。
     *
     * @param version   KEK 版本号
     * @param kekBase64 Base64 编码的 KEK
     */
    private void loadKek(int version, String kekBase64) {
        byte[] kekBytes = Base64.getDecoder().decode(kekBase64);
        if (kekBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "KEK 长度必须为 " + KEY_LENGTH_BYTES + " 字节 (AES-256)，实际: " + kekBytes.length);
        }
        SecretKey kek = new SecretKeySpec(kekBytes, "AES");
        kekCache.put(version, kek);
    }

    /**
     * 生成新的 AES-256 DEK。
     *
     * @return 32 字节随机 DEK
     */
    public byte[] generateDek() {
        byte[] dek = new byte[KEY_LENGTH_BYTES];
        secureRandom.nextBytes(dek);
        return dek;
    }

    /**
     * 使用指定版本的 KEK 加密 DEK（AES-256-GCM）。
     *
     * <p>GCM 模式下，认证标签附加在密文末尾，返回值为 ciphertext + authTag。</p>
     *
     * @param dek        明文 DEK
     * @param kekVersion KEK 版本号
     * @return 加密后的 DEK（含 IV 前缀 + ciphertext + authTag）
     * @throws KeyVersionUnavailableException 如果指定版本的 KEK 不可用
     */
    public byte[] encryptDek(byte[] dek, int kekVersion) {
        SecretKey kek = kekCache.get(kekVersion);
        if (kek == null) {
            log.warn("禁止回退：KEK 版本 {} 不可用 — fail-closed", kekVersion);
            throw new KeyVersionUnavailableException(kekVersion);
        }
        return aesGcmEncrypt(dek, kek);
    }

    /**
     * 使用指定版本的 KEK 解密 DEK（AES-256-GCM）。
     *
     * @param encryptedDek 加密的 DEK（含 IV 前缀 + ciphertext + authTag）
     * @param kekVersion   KEK 版本号
     * @return 明文 DEK
     * @throws KeyVersionUnavailableException 如果指定版本的 KEK 不可用
     */
    public byte[] decryptDek(byte[] encryptedDek, int kekVersion) {
        SecretKey kek = kekCache.get(kekVersion);
        if (kek == null) {
            log.warn("禁止回退：KEK 版本 {} 不可用 — fail-closed", kekVersion);
            throw new KeyVersionUnavailableException(kekVersion);
        }
        return aesGcmDecrypt(encryptedDek, kek);
    }

    /**
     * 带缓存的 DEK 解密 — 避免频繁解密同一 DEK。
     *
     * @param metadataId   encryption_key_metadata.id
     * @param encryptedDek 加密的 DEK
     * @param kekVersion   KEK 版本号
     * @return 明文 DEK
     */
    public byte[] decryptDekCached(Long metadataId, byte[] encryptedDek, int kekVersion) {
        return dekCache.computeIfAbsent(metadataId, k -> decryptDek(encryptedDek, kekVersion));
    }

    /**
     * 轮换 KEK — 生成新版本 KEK。
     *
     * <p>新 KEK 由 {@link SecureRandom} 随机生成，旧版本 KEK 保留在缓存中
     * 直到渐进式 DEK 迁移完成后由 {@link #archiveKek(int)} 归档。</p>
     *
     * @return 新 KEK 版本号
     */
    public int rotateKek() {
        if (currentKekVersion == null) {
            log.warn("禁止回退：KEK 未配置，无法轮换 — fail-closed");
            throw new KeyVersionUnavailableException(0);
        }
        int newVersion = currentKekVersion + 1;
        byte[] newKekBytes = new byte[KEY_LENGTH_BYTES];
        secureRandom.nextBytes(newKekBytes);
        SecretKey newKek = new SecretKeySpec(newKekBytes, "AES");
        kekCache.put(newVersion, newKek);
        currentKekVersion = newVersion;
        log.info("KEK 已轮换至版本 {}", newVersion);
        return newVersion;
    }

    /**
     * 归档旧版本 KEK — 从缓存中移除。
     *
     * <p>仅在确认无 DEK 引用该版本 KEK 后才可归档，否则解密将失败。</p>
     *
     * @param version 要归档的 KEK 版本号
     */
    public void archiveKek(int version) {
        kekCache.remove(version);
        log.info("KEK 版本 {} 已归档", version);
    }

    /**
     * 检查指定版本 KEK 是否仍在缓存中（仍被引用）。
     *
     * @param version KEK 版本号
     * @return true 如果 KEK 仍在缓存中
     */
    public boolean isKekVersionAvailable(int version) {
        return kekCache.containsKey(version);
    }

    /**
     * 检查 KEK 是否可用（已配置且加载成功）。
     *
     * @return true 如果 KEK 可用
     */
    public boolean isAvailable() {
        return currentKekVersion != null && !kekCache.isEmpty();
    }

    /**
     * 获取当前 KEK 版本号。
     *
     * @return 当前版本号，KEK 不可用时返回 null
     */
    public Integer getCurrentKekVersion() {
        return currentKekVersion;
    }

    // --- AES-256-GCM 内部方法 ---

    /**
     * AES-256-GCM 加密 — 返回格式：IV(12B) + ciphertext + authTag(16B)。
     */
    private byte[] aesGcmEncrypt(byte[] plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintext);

            // 拼接 IV + ciphertext（含 authTag）
            byte[] result = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new EncryptionException("ENCRYPTION_KEY_UNAVAILABLE", "DEK 加密失败", e);
        }
    }

    /**
     * AES-256-GCM 解密 — 输入格式：IV(12B) + ciphertext + authTag(16B)。
     */
    private byte[] aesGcmDecrypt(byte[] encryptedData, SecretKey key) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(encryptedData, 0, iv, 0, GCM_IV_LENGTH);

            byte[] ciphertext = new byte[encryptedData.length - GCM_IV_LENGTH];
            System.arraycopy(encryptedData, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            log.warn("认证降级：DEK 解密失败 — fail-closed", e);
            throw new EncryptionException("DATA_INTEGRITY_VIOLATION", "DEK 解密失败", e);
        }
    }
}