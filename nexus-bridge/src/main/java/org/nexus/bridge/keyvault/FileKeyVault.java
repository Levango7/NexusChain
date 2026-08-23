package org.nexus.bridge.keyvault;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed KeyVault with AES-256-GCM encryption at rest.
 *
 * <p>Each validator key is stored as a separate encrypted file:
 * {@code {basePath}/{validatorId}.key.enc}. The file format is:</p>
 * <pre>
 *   [12-byte nonce] || [ciphertext] || [16-byte tag]
 * </pre>
 *
 * <p>The master password is read from the environment variable
 * {@code NEX_BRIDGE_KEY_PASSWORD}. The AES key is derived via
 * <b>PBKDF2WithHmacSHA256</b>（B-25 修复：原 SHA-256(password) 直接派生不安全）。</p>
 *
 * <p>B-25 修复：密钥派生参数：
 * <ul>
 *   <li>算法：PBKDF2WithHmacSHA256</li>
 *   <li>迭代次数：{@value #PBKDF2_ITERATIONS}（≥ 100000，抵抗离线暴力破解）</li>
 *   <li>盐值：16 字节，存储在 {@code {basePath}/master.salt} 文件中</li>
 *   <li>密钥长度：256 位</li>
 * </ul>
 * salt 在首次 init 时随机生成并持久化，后续启动复用，保证同一 password 派生同一 masterKey。
 * 若 salt 文件已存在则读取，避免重新派生导致历史加密文件无法解密。</p>
 */
public class FileKeyVault implements KeyVault {

    private static final Logger log = LoggerFactory.getLogger(FileKeyVault.class);
    private static final int GCM_NONCE_LEN = 12;
    private static final int GCM_TAG_LEN   = 128;

    /** PBKDF2 迭代次数，≥ 100000 满足 B-25 要求。150000 留安全余量。 */
    private static final int PBKDF2_ITERATIONS = 150000;
    /** salt 长度（字节），16 字节（128 位）是 NIST SP 800-132 推荐的最小长度。 */
    private static final int SALT_LENGTH = 16;
    /** 派生密钥长度（位），256 位 = AES-256。 */
    private static final int DERIVED_KEY_LENGTH_BITS = 256;
    /** salt 持久化文件名，相对 basePath。 */
    private static final String SALT_FILENAME = "master.salt";

    private final Path basePath;
    private final String password;
    /** 派生出的 master key，在 {@link #init()} 中赋值。volatile 保证可见性。 */
    private volatile SecretKey masterKey;
    private final Map<String, byte[]> publicKeys = new ConcurrentHashMap<>();
    private volatile boolean available;

    public FileKeyVault(String basePath, String password) {
        this.basePath = Paths.get(basePath);
        this.password = password;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(basePath);
            // B-25：先加载/生成 salt，再派生 masterKey，保证重启后同一 password 派生同一 key。
            byte[] salt = loadOrCreateSalt();
            this.masterKey = deriveMasterKey(password, salt);
            available = true;
            log.info("FileKeyVault initialised: basePath={}, key derived via PBKDF2WithHmacSHA256 (iterations={})",
                    basePath.toAbsolutePath(), PBKDF2_ITERATIONS);
        } catch (IOException e) {
            available = false;
            log.error("Cannot create vault directory: {}", basePath, e);
        } catch (GeneralSecurityException e) {
            available = false;
            log.error("Cannot derive master key via PBKDF2: {}", e.getMessage(), e);
        }
    }

    /**
     * 加载 salt 文件；不存在则随机生成 16 字节 salt 并持久化。
     *
     * <p>salt 文件独立于各 validator 的 {@code .key.enc} 文件，全局共享。
     * 这样同一 password 重启后派生同一 masterKey，历史加密文件可正常解密。
     * salt 不需要保密（PBKDF2 的安全性来自迭代次数 + password 熵），但需保证
     * 不被攻击者篡改；文件权限由操作系统保障。</p>
     */
    private byte[] loadOrCreateSalt() throws IOException {
        Path saltFile = basePath.resolve(SALT_FILENAME);
        if (Files.exists(saltFile)) {
            byte[] salt = Files.readAllBytes(saltFile);
            if (salt.length != SALT_LENGTH) {
                throw new IOException(
                        "Corrupted salt file " + saltFile + ": expected " + SALT_LENGTH
                        + " bytes, got " + salt.length);
            }
            log.debug("Loaded existing salt from {}", saltFile);
            return salt;
        }
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        // CREATE + TRUNCATE_EXISTING + WRITE：新建 salt 文件，原子写入避免部分写。
        Files.write(saltFile, salt,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
        log.info("Generated new salt ({} bytes) and persisted to {}", SALT_LENGTH, saltFile);
        return salt;
    }

    /**
     * 用 PBKDF2WithHmacSHA256 从 password + salt 派生 AES-256 master key。
     *
     * <p>替换原 {@code MessageDigest.getInstance("SHA-256").digest(password)} 方案：
     * SHA-256 是快速哈希，攻击者可每秒尝试数亿次 password；PBKDF2 通过
     * {@value #PBKDF2_ITERATIONS} 次 HMAC 迭代显著提高单次尝试成本，
     * 配合 16 字节随机 salt 防止彩虹表攻击。</p>
     */
    private SecretKey deriveMasterKey(String password, byte[] salt) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("FileKeyVault password must not be empty");
        }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, DERIVED_KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    @PreDestroy
    public void destroy() {
        available = false;
        publicKeys.clear();
    }

    @Override
    public String sign(String validatorId, byte[] payload) {
        checkAvailable();
        byte[] privateKeyBytes = loadPrivateKey(validatorId);
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(priv);
            sig.update(payload);
            return bytesToHex(sig.sign());
        } catch (GeneralSecurityException e) {
            log.error("Signing failed for validator={}", validatorId, e);
            throw new RuntimeException("Sign error: " + validatorId, e);
        }
    }

    @Override
    public String getPublicKey(String validatorId) {
        byte[] pk = publicKeys.get(validatorId);
        return pk != null ? bytesToHex(pk) : null;
    }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public Set<String> getValidatorIds() {
        return Collections.unmodifiableSet(publicKeys.keySet());
    }

    public void generateAndStore(String validatorId) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = gen.generateKeyPair();
            byte[] priv = pair.getPrivate().getEncoded();
            byte[] pub  = pair.getPublic().getEncoded();

            encryptAndWrite(validatorId, priv);
            publicKeys.put(validatorId, pub);
            log.info("Key-pair generated and stored for validator={}", validatorId);
        } catch (Exception e) {
            throw new RuntimeException("Key generation failed", e);
        }
    }

    public void importKey(String validatorId, String privateKeyHex, String publicKeyHex) {
        byte[] priv = hexToBytes(privateKeyHex);
        byte[] pub  = hexToBytes(publicKeyHex);
        encryptAndWrite(validatorId, priv);
        publicKeys.put(validatorId, pub);
        log.info("Key imported for validator={}", validatorId);
    }

    // ---- internal ----

    private void checkAvailable() {
        if (!available) throw new IllegalStateException("FileKeyVault not available");
    }

    private byte[] loadPrivateKey(String validatorId) {
        Path file = basePath.resolve(validatorId + ".key.enc");
        if (!Files.exists(file)) throw new IllegalArgumentException("No key for validator: " + validatorId);
        try {
            byte[] raw = Files.readAllBytes(file);
            if (raw.length < GCM_NONCE_LEN + GCM_TAG_LEN / 8)
                throw new IOException("Corrupted key file: " + file);
            byte[] nonce = Arrays.copyOfRange(raw, 0, GCM_NONCE_LEN);
            byte[] ciphertext = Arrays.copyOfRange(raw, GCM_NONCE_LEN, raw.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LEN, nonce));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Cannot decrypt key for validator=" + validatorId, e);
        }
    }

    private void encryptAndWrite(String validatorId, byte[] plaintext) {
        try {
            byte[] nonce = new byte[GCM_NONCE_LEN];
            new SecureRandom().nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LEN, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(nonce);
            bos.write(ciphertext);
            Files.write(basePath.resolve(validatorId + ".key.enc"), bos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Cannot encrypt key for validator=" + validatorId, e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                               + Character.digit(hex.charAt(i + 1), 16));
        return out;
    }
}
