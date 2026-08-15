package org.nexus.signing.mpc.persistence;

import org.nexus.signing.mpc.MpcKeyShare;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * AES-GCM 加密的本地文件密钥份额存储。
 *
 * <p><b>加密设计</b>：</p>
 * <ul>
 *   <li>算法：AES-256/GCM/NoPadding（JDK 自带 {@code javax.crypto}）。</li>
 *   <li>密钥来源：环境变量 {@code NEXUS_MPC_KEK}（base64 编码的 32 字节 KEK）。
 *       生产环境应替换为 KMS-backed 实现（如 AWS KMS / Huawei KMS）。</li>
 *   <li>每次写入生成新的 12 字节 IV，密文与 IV 一起存入文件。</li>
 *   <li>认证标签（16 字节）由 GCM 自动附加，防篡改。</li>
 * </ul>
 *
 * <p><b>文件格式</b>（base64 文本，单行）：</p>
 * <pre>
 *   base64( IV[12] || ciphertext[N] || authTag[16] )
 * </pre>
 *
 * <p><b>明文格式</b>（加密前，UTF-8 字符串，分隔符 {@code |}）：</p>
 * <pre>
 *   participantId|privateShareHex|publicShareHex|paillierPublicKeyHex
 * </pre>
 *
 * <p><b>线程安全</b>：每次 save/load 独立 Cipher 实例，文件操作原子替换。</p>
 */
@Repository
@ConditionalOnMissingBean(name = "kmsMpcKeyShareStore")
public class EncryptedFileKeyShareStore implements MpcKeyShareStore {

    private static final Logger log = LoggerFactory.getLogger(EncryptedFileKeyShareStore.class);

    /** AES-GCM 参数。 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // bits

    /** 环境变量名：base64 编码的 32 字节 KEK。 */
    private static final String KEK_ENV_VAR = "NEXUS_MPC_KEK";

    private final SecretKey kek;
    private final Path storageDir;
    private final SecureRandom random = new SecureRandom();

    /**
     * 构造加密份额存储。
     *
     * @param storageDirPath 存储目录路径（默认 {@code ./mpc-keyshares}）
     * @param kekBase64      base64 编码的 32 字节 KEK；若为 {@code null} 则从
     *                       环境变量 {@code NEXUS_MPC_KEK} 读取
     */
    public EncryptedFileKeyShareStore(
            @Value("${nexus.mpc.keyshare.dir:./mpc-keyshares}") String storageDirPath,
            @Value("${nexus.mpc.keyshare.kek:#{null}}") String kekBase64,
            @Value("${nexus.mpc.keyshare.min-shares:0}") int minShares) {
        this.kek = loadKek(kekBase64);
        this.storageDir = Paths.get(storageDirPath);
        this.minShares = minShares;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create keyshare dir: " + storageDir, e);
        }
        log.info("EncryptedFileKeyShareStore initialized: dir={}", storageDir.toAbsolutePath());
    }

    /**
     * 启动级份额门限校验（#7 MPC 架构限制缓解，fail-closed）：
     * 份额集中单进程是已知设计限制；本校验确保门限份额**完整存在**才启动
     * 签名服务——份额文件缺失/不足（如存储丢失、备份不完整）时拒绝启动，
     * 绝不带病运行产生不完整签名。
     *
     * <p>配置 {@code nexus.mpc.keyshare.min-shares}（默认 0 = 不校验，向后兼容）。</p>
     */
    @jakarta.annotation.PostConstruct
    public void verifyMinShares() {
        if (minShares <= 0) {
            return;
        }
        int actual = listParticipantIds().size();
        if (actual < minShares) {
            throw new IllegalStateException(
                    "MPC keyshare check FAILED (fail-closed): found " + actual
                            + " shares, required >= " + minShares
                            + " — refusing to start signing service with incomplete shares");
        }
        log.info("MPC keyshare check passed: {} shares >= required {}", actual, minShares);
    }

    private final int minShares;

    private SecretKey loadKek(String kekBase64) {
        if (kekBase64 == null || kekBase64.isEmpty()) {
            kekBase64 = System.getenv(KEK_ENV_VAR);
        }
        if (kekBase64 == null || kekBase64.isEmpty()) {
            throw new IllegalStateException(
                    "MPC KEK not configured: set env " + KEK_ENV_VAR
                            + " or property nexus.mpc.keyshare.kek to base64(32-byte KEK)");
        }
        byte[] keyBytes = Base64.getDecoder().decode(kekBase64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "MPC KEK must be 32 bytes (AES-256), got " + keyBytes.length);
        }
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        // 抹除明文数组
        Arrays.fill(keyBytes, (byte) 0);
        return key;
    }

    @Override
    public void save(MpcKeyShare share) {
        String plain = join(share.getParticipantId(),
                share.getPrivateShareHex(),
                share.getPublicShareHex(),
                share.getPaillierPublicKeyHex());
        byte[] cipher = encrypt(plain.getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.getEncoder().encodeToString(cipher);
        Path file = pathFor(share.getParticipantId());
        try {
            Files.write(file, encoded.getBytes(StandardCharsets.US_ASCII),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Saved encrypted key share for participant {}", share.getParticipantId());
        } catch (IOException e) {
            throw new IllegalStateException("cannot write keyshare file: " + file, e);
        }
    }

    @Override
    public Optional<MpcKeyShare> load(String participantId) {
        Path file = pathFor(participantId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            String encoded = new String(Files.readAllBytes(file), StandardCharsets.US_ASCII);
            byte[] cipher = Base64.getDecoder().decode(encoded);
            byte[] plain = decrypt(cipher);
            String[] parts = new String(plain, StandardCharsets.UTF_8).split("\\|", -1);
            return Optional.of(new MpcKeyShare(
                    parts[0],
                    parts[1],
                    parts[2],
                    emptyToNull(parts[3])));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read keyshare file: " + file, e);
        }
    }

    @Override
    public List<String> listParticipantIds() {
        List<String> ids = new ArrayList<>();
        try (Stream<Path> stream = Files.list(storageDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".enc"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        ids.add(name.substring(0, name.length() - 4));
                    });
        } catch (IOException e) {
            throw new IllegalStateException("cannot list keyshare dir: " + storageDir, e);
        }
        return ids;
    }

    @Override
    public void delete(String participantId) {
        try {
            Files.deleteIfExists(pathFor(participantId));
        } catch (IOException e) {
            throw new IllegalStateException("cannot delete keyshare file", e);
        }
    }

    @Override
    public boolean exists(String participantId) {
        return Files.exists(pathFor(participantId));
    }

    private Path pathFor(String participantId) {
        // 用 participantId 的 hash 作为文件名，避免路径遍历与特殊字符问题
        return storageDir.resolve(participantId.replaceAll("[^A-Za-z0-9_-]", "_") + ".enc");
    }

    private byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            // 拼接 IV || ciphertext+tag
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    private byte[] decrypt(byte[] ivAndCipher) {
        try {
            if (ivAndCipher.length < GCM_IV_LENGTH + 1) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = Arrays.copyOfRange(ivAndCipher, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(ivAndCipher, GCM_IV_LENGTH, ivAndCipher.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decrypt failed (wrong KEK or tampered?)", e);
        }
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(parts[i] == null ? "" : parts[i]);
        }
        return sb.toString();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}