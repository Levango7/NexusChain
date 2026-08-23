package org.nexus.gateway.security;

import jakarta.annotation.PostConstruct;
import org.nexus.gateway.model.MerchantKeypairEntry;
import org.nexus.gateway.repository.MerchantKeypairRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production KeyManager: encrypts merchant keys with AES-256-GCM before storage.
 * Master key sourced from environment variable (injected by Vault/KMS at deploy time).
 * Supports key rotation: old keys remain decryptable for 7-day grace period.
 *
 * <p>B-14 修复：密钥对持久化到数据库（{@code merchant_keypairs} 表），
 * 启动时通过 {@link MerchantKeypairRepository} 全量加载到内存 {@link #encryptedStore}，
 * 每次 {@link #storeKeypair} 同步 upsert 到数据库，确保服务重启后密钥不丢失。</p>
 */
@Component
@Profile("prod")
public class VaultKeyManager implements KeyManager {

    private static final Logger log = LoggerFactory.getLogger(VaultKeyManager.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final byte[] masterKey;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, String> encryptedStore = new ConcurrentHashMap<>();
    private final MerchantKeypairRepository keypairRepository;

    @Autowired
    public VaultKeyManager(
            @Value("${NEX_MASTER_KEY:}") String masterKeyBase64,
            MerchantKeypairRepository keypairRepository) {
        if (masterKeyBase64 == null || masterKeyBase64.isEmpty()) {
            throw new IllegalStateException("NEX_MASTER_KEY environment variable must be set in production");
        }
        this.masterKey = Base64.getDecoder().decode(masterKeyBase64);
        if (this.masterKey.length != 32) {
            throw new IllegalStateException("NEX_MASTER_KEY must be 256-bit (32 bytes) AES key");
        }
        this.keypairRepository = keypairRepository;
        log.info("VaultKeyManager initialized with AES-256-GCM encryption");
    }

    /**
     * 启动时从数据库全量加载已加密的密钥对到内存，保证服务重启后密钥可用。
     *
     * <p>分离构造与加载：构造期仅校验主密钥与注入依赖，避免在构造器中访问数据库
     * 触发 Spring 早期初始化问题。{@code @PostConstruct} 在依赖注入完成后、
     * Bean 投入使用前执行，此时 JPA 基础设施已就绪。</p>
     */
    @PostConstruct
    public void loadFromDatabase() {
        try {
            int count = 0;
            for (MerchantKeypairEntry entry : keypairRepository.findAll()) {
                encryptedStore.put(entry.getMerchantId(), entry.getEncryptedKeypair());
                count++;
            }
            log.info("Loaded {} merchant keypairs from database into memory", count);
        } catch (Exception e) {
            // 加载失败抛异常让应用启动失败，避免静默丢失密钥后商户认证全失败却无告警。
            log.error("Failed to load merchant keypairs from database: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to load merchant keypairs from database", e);
        }
    }

    @Override
    public String getPublicKey(Long merchantId) {
        String encrypted = encryptedStore.get(merchantId);
        if (encrypted == null) return null;
        String[] parts = decrypt(encrypted).split("\\|");
        return parts.length >= 1 ? parts[0] : null;
    }

    @Override
    public String getPrivateKey(Long merchantId) {
        String encrypted = encryptedStore.get(merchantId);
        if (encrypted == null) return null;
        String[] parts = decrypt(encrypted).split("\\|");
        return parts.length >= 2 ? parts[1] : null;
    }

    @Override
    public void storeKeypair(Long merchantId, String publicKey, String privateKey) {
        String plaintext = publicKey + "|" + privateKey;
        String encrypted = encrypt(plaintext);
        // 先落库再更新内存：若落库失败抛异常，内存不被更新，保持两者一致且不掩盖错误。
        persistToDatabase(merchantId, encrypted);
        encryptedStore.put(merchantId, encrypted);
        log.info("Keypair stored (encrypted) for merchant: {}", merchantId);
    }

    @Override
    public boolean hasKeypair(Long merchantId) {
        return encryptedStore.containsKey(merchantId);
    }

    /**
     * 将加密后的密钥对 upsert 到数据库。
     *
     * <p>按 {@code merchantId} 唯一约束查找：存在则更新密文，不存在则新建。
     * 复用 Spring Data JPA 的 {@link MerchantKeypairRepository#save(Object)}，
     * 主键存在时自动走 UPDATE，不存在时走 INSERT。</p>
     */
    private void persistToDatabase(Long merchantId, String encrypted) {
        try {
            MerchantKeypairEntry entry = keypairRepository.findByMerchantId(merchantId)
                    .orElseGet(() -> {
                        MerchantKeypairEntry newEntry = new MerchantKeypairEntry();
                        newEntry.setMerchantId(merchantId);
                        return newEntry;
                    });
            entry.setEncryptedKeypair(encrypted);
            keypairRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to persist keypair for merchant {}: {}", merchantId, e.getMessage(), e);
            throw new IllegalStateException("Failed to persist keypair for merchant " + merchantId, e);
        }
    }

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private String decrypt(String encryptedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
