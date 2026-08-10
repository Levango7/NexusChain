package org.nexus.gateway.security;

/**
 * Key management interface for merchant wallet credentials.
 * Implementations may back onto HSM, HashiCorp Vault, AWS KMS,
 * or a local encrypted file for development.
 */
public interface KeyManager {

    String getPublicKey(Long merchantId);

    String getPrivateKey(Long merchantId);

    void storeKeypair(Long merchantId, String publicKey, String privateKey);

    boolean hasKeypair(Long merchantId);
}