package org.nexus.oracle.random;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * {@link RandomOracle} 默认实现（HMAC 可验证随机方案）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>{@link #generateRandom}：以节点 VRF 密钥对 seed 做 HMAC-SHA256 得到随机数
 *       {@code random}；再以同一密钥对 random 做 HMAC 得到伴随证明 {@code proof}</li>
 *   <li>{@link #verifyRandom}：用密钥重算 {@code hmac(random)} 与 proof 比对，
 *       一致即证明 random 确由持有密钥的生成方产出，且未被篡改</li>
 * </ul>
 *
 * <p>说明：这是确定性可验证方案（给定 seed 与密钥输出唯一），满足
 * 「可验证 + 不可篡改」；「不可预测」依赖 seed 来自链上未来区块哈希。
 * 生产环境可替换为标准 ECVRF / Chainlink VRF v2，本实现接口不变。
 */
@Slf4j
@Service
public class DefaultRandomOracle implements RandomOracle {

    /** HMAC 算法 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** VRF 密钥（生产应从安全存储注入，默认值为开发占位） */
    private final String vrfSecret;

    public DefaultRandomOracle(@Value("${nexus.oracle.vrf-secret:nexus-vrf-dev-secret}") String vrfSecret) {
        this.vrfSecret = vrfSecret;
    }

    @Override
    public RandomProof generateRandom(String seed) {
        if (seed == null || seed.isBlank()) {
            throw new IllegalArgumentException("seed is required");
        }
        try {
            String random = hmacHex(seed);
            String proof = hmacHex(random);
            return RandomProof.builder()
                    .seed(seed)
                    .random(random)
                    .proof(proof)
                    .signature(hmacHex(random + "|" + seed))
                    .generator(keyFingerprint())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate random for seed: " + seed, e);
        }
    }

    @Override
    public boolean verifyRandom(String random, String proof) {
        if (random == null || random.isBlank() || proof == null || proof.isBlank()) {
            return false;
        }
        try {
            // 用密钥重算 hmac(random)，与 proof 比对；一致即校验通过
            String expectedProof = hmacHex(random);
            return constantTimeEquals(expectedProof, proof);
        } catch (Exception e) {
            log.debug("verifyRandom failed", e);
            return false;
        }
    }

    /**
     * 计算 HMAC-SHA256 并编码为 URL-safe Base64（无填充）。
     */
    private String hmacHex(String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(vrfSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /**
     * 密钥指纹（生成者标识）。
     */
    private String keyFingerprint() {
        try {
            return hmacHex("nexus-vrf-fingerprint").substring(0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 常量时间字符串比较，避免时序侧信道。
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
