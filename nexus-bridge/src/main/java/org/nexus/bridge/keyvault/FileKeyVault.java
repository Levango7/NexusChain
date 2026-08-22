package org.nexus.bridge.keyvault;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
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
 * SHA-256(password).</p>
 */
public class FileKeyVault implements KeyVault {

    private static final Logger log = LoggerFactory.getLogger(FileKeyVault.class);
    private static final int GCM_NONCE_LEN = 12;
    private static final int GCM_TAG_LEN   = 128;

    private final Path basePath;
    private final SecretKey masterKey;
    private final Map<String, byte[]> publicKeys = new ConcurrentHashMap<>();
    private volatile boolean available;

    public FileKeyVault(String basePath, String password) {
        this.basePath = Paths.get(basePath);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
            this.masterKey = new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to derive master key", e);
        }
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(basePath);
            available = true;
            log.info("FileKeyVault initialised: basePath={}", basePath.toAbsolutePath());
        } catch (IOException e) {
            available = false;
            log.error("Cannot create vault directory: {}", basePath, e);
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
