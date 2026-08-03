package org.nexus.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
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