package org.nexus.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地文件 KeyManager（B-24 修复：私钥加密存储）。
 *
 * <p>原先 {@code publicKey,privateKey} 以明文 Properties 落盘，任何有文件读权限的
 * 进程/用户都能直接拿到私钥。修复后整个 value（{@code publicKey + "," + privateKey}）
 * 用 AES-256-GCM 加密，文件中只存 Base64 编码的 {@code [16字节IV || ciphertext || GCM-tag]}。</p>
 *
 * <p>加密密钥来源（不硬编码）：
 * <ul>
 *   <li>环境变量 {@code NEX_LOCAL_KEYSTORE_KEY}（Base64 编码的 32 字节 AES-256 密钥），或</li>
 *   <li>Spring 配置 {@code nexus.keystore.encryption-key}</li>
 * </ul>
 * 启动时校验密钥非空且长度为 32 字节，否则抛 {@link IllegalStateException} 让应用快速失败。</p>
 */
@Component
@Profile({"dev", "prod"})
public class LocalFileKeyManager implements KeyManager {

    private static final Logger log = LoggerFactory.getLogger(LocalFileKeyManager.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 16; // B-24 要求：16 字节 IV
    private static final String ENCRYPTED_VALUE_PREFIX = "ENC:"; // 标识加密格式，兼容旧明文检测

    private final ConcurrentHashMap<Long, String[]> cache = new ConcurrentHashMap<>();
    private final Path keyStorePath;
    private final byte[] encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public LocalFileKeyManager(
            @Value("${nexus.keystore.path:}") String path,
            @Value("${nexus.keystore.encryption-key:${NEX_LOCAL_KEYSTORE_KEY:}}") String encryptionKeyBase64) {
        if (path != null && !path.isEmpty()) {
            this.keyStorePath = Paths.get(path);
        } else {
            this.keyStorePath = Paths.get(System.getProperty("java.io.tmpdir"), "nexus-keystore.properties");
        }
        this.encryptionKey = resolveEncryptionKey(encryptionKeyBase64);
        loadFromFile();
    }

    private byte[] resolveEncryptionKey(String encryptionKeyBase64) {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isEmpty()) {
            throw new IllegalStateException(
                    "LocalFileKeyManager requires an encryption key. Set env NEX_LOCAL_KEYSTORE_KEY " +
                    "or config nexus.keystore.encryption-key to a Base64-encoded 32-byte AES-256 key.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encryptionKeyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("nexus.keystore.encryption-key must be valid Base64", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException(
                    "nexus.keystore.encryption-key must decode to 32 bytes (256-bit AES), got " + key.length);
        }
        return key;
    }

    @Override
    public String getPublicKey(Long merchantId) {
        String[] pair = cache.get(merchantId);
        return pair != null ? pair[0] : null;
    }

    @Override
    public String getPrivateKey(Long merchantId) {
        String[] pair = cache.get(merchantId);
        return pair != null ? pair[1] : null;
    }

    @Override
    public void storeKeypair(Long merchantId, String publicKey, String privateKey) {
        cache.put(merchantId, new String[]{publicKey, privateKey});
        persistToFile();
        log.info("Keypair stored for merchant: {}", merchantId);
    }

    @Override
    public boolean hasKeypair(Long merchantId) {
        return cache.containsKey(merchantId);
    }

    private void loadFromFile() {
        if (!Files.exists(keyStorePath)) { return; }
        try (InputStream is = Files.newInputStream(keyStorePath)) {
            Properties props = new Properties();
            props.load(is);
            for (String key : props.stringPropertyNames()) {
                String storedValue = props.getProperty(key);
                String plaintext = decryptValue(storedValue);
                String[] parts = plaintext.split(",", 2);
                if (parts.length == 2) { cache.put(Long.parseLong(key), parts); }
            }
            log.info("Loaded {} keypairs from {}", cache.size(), keyStorePath);
        } catch (IOException | RuntimeException e) {
            // 静默吞异常修复：加载失败会导致 cache 为空，后续 getPublicKey/getPrivateKey 返回 null，
            // 商户签名失败但原因难以诊断。提高日志级别并抛出 IllegalStateException，让构造失败暴露问题。
            log.error("Failed to load keystore from {}: {}", keyStorePath, e.getMessage(), e);
            throw new IllegalStateException("Failed to load keystore from " + keyStorePath, e);
        }
    }

    private synchronized void persistToFile() {
        try {
            Properties props = new Properties();
            cache.forEach((id, pair) -> {
                String plaintext = pair[0] + "," + pair[1];
                props.setProperty(id.toString(), encryptValue(plaintext));
            });
            try (OutputStream os = Files.newOutputStream(keyStorePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(os, "NexusChain Dev Keystore (AES-256-GCM encrypted)");
            }
        } catch (IOException | RuntimeException e) {
            // 静默吞异常修复：持久化失败时 cache 已更新但文件未写入，重启后数据丢失。
            // 抛出 IllegalStateException 让调用方 storeKeypair 感知失败，避免静默数据丢失。
            log.error("Failed to persist keystore to {}: {}", keyStorePath, e.getMessage(), e);
            throw new IllegalStateException("Failed to persist keystore to " + keyStorePath, e);
        }
    }

    /**
     * 加密 value：输出 {@code ENC:Base64([16B IV || ciphertext || GCM-tag])}。
     * 前缀用于区分加密格式与历史明文（加载时据此判断是否需要解密）。
     */
    private String encryptValue(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return ENCRYPTED_VALUE_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Keystore encryption failed", e);
        }
    }

    /**
     * 解密 value：支持 {@code ENC:Base64(...)} 加密格式。
     * 不带前缀的值视为历史明文，直接返回（向后兼容一次性迁移）。
     */
    private String decryptValue(String storedValue) {
        if (storedValue == null || storedValue.isEmpty()) {
            return storedValue;
        }
        if (!storedValue.startsWith(ENCRYPTED_VALUE_PREFIX)) {
            // 历史明文 value（迁移前遗留），直接返回让上层 split 解析。
            log.warn("Detected plaintext keystore entry (no {} prefix); consider re-storing to encrypt", ENCRYPTED_VALUE_PREFIX);
            return storedValue;
        }
        String base64Part = storedValue.substring(ENCRYPTED_VALUE_PREFIX.length());
        try {
            byte[] combined = Base64.getDecoder().decode(base64Part);
            if (combined.length < IV_LENGTH + GCM_TAG_LENGTH / 8) {
                throw new IllegalStateException("Corrupted encrypted entry: too short");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Keystore decryption failed", e);
        }
    }
}
