package org.nexus.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sandbox KeyManager for local development and testing.
 * Simulates Vault/KMS behavior without requiring external infrastructure.
 * Generates deterministic mock keys for reproducible test scenarios.
 *
 * Activated by @Profile("sandbox") - use with --spring.profiles.active=sandbox
 *
 * <p>注：@Primary 确保 sandbox profile 激活时本 Bean 优先于 LocalFileKeyManager
 * （@Profile({"dev","prod"})）。bootstrap.yml 默认激活 dev profile，集成测试
 * 通过 @ActiveProfiles("sandbox") 追加 sandbox，导致 dev+sandbox 同时激活，
 * 两个 KeyManager 候选并存。@Primary 让 sandbox 环境正确使用 mock keys。
 * 生产环境（prod profile）只有 VaultKeyManager，不受影响。</p>
 */
@Component
@Primary
@Profile("sandbox")
public class SandboxKeyManager implements KeyManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxKeyManager.class);
    private final Map<Long, String[]> store = new ConcurrentHashMap<>();

    public SandboxKeyManager() {
        log.info("SandboxKeyManager active - using mock keys (NOT for production)");
    }

    @Override
    public String getPublicKey(Long merchantId) {
        String[] pair = store.get(merchantId);
        if (pair != null) return pair[0];
        // Auto-generate deterministic mock key for sandbox
        String pubKey = "04" + UUID.nameUUIDFromBytes(("pub-" + merchantId).getBytes()).toString().replace("-", "");
        String privKey = UUID.nameUUIDFromBytes(("priv-" + merchantId).getBytes()).toString().replace("-", "");
        store.put(merchantId, new String[]{pubKey, privKey});
        return pubKey;
    }

    @Override
    public String getPrivateKey(Long merchantId) {
        getPublicKey(merchantId); // ensure pair exists
        return store.get(merchantId)[1];
    }

    @Override
    public void storeKeypair(Long merchantId, String publicKey, String privateKey) {
        store.put(merchantId, new String[]{publicKey, privateKey});
        log.info("[SANDBOX] Keypair stored for merchant: {}", merchantId);
    }

    @Override
    public boolean hasKeypair(Long merchantId) {
        return store.containsKey(merchantId);
    }
}