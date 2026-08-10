package org.nexus.gateway;

import org.nexus.gateway.model.Merchant;

import java.util.Optional;

/**
 * Merchant service interface covering registration, KYC verification, and API key management.
 *
 * <p>Implementations are responsible for:
 * <ul>
 *   <li>Generating unique merchant codes</li>
 *   <li>Assigning NEX settlement wallet addresses</li>
 *   <li>Creating and rotating API keys</li>
 *   <li>Tracking KYC verification status</li>
 * </ul></p>
 */
public interface MerchantService {

    /**
     * Register a new merchant.
     *
     * @param merchantName    display name of the merchant
     * @param email           contact email
     * @param settlementAddress NEX settlement wallet address
     * @return the persisted merchant entity
     */
    Merchant register(String merchantName, String email, String settlementAddress);

    /**
     * Submit or update KYC verification for a merchant.
     *
     * @param merchantId merchant ID
     * @param status     target verification status
     * @return the updated merchant entity
     */
    Merchant verify(Long merchantId, Merchant.VerificationStatus status);

    /**
     * Generate a new API key pair for a merchant.
     *
     * @param merchantId merchant ID
     * @return a map containing {@code apiKey} and {@code secret} (plaintext, shown once)
     */
    ApiKeyPair generateApiKey(Long merchantId);

    /**
     * Revoke an existing API key.
     *
     * @param merchantId merchant ID
     * @param apiKey     public API key to revoke
     */
    void revokeApiKey(Long merchantId, String apiKey);

    /**
     * Validate an API key and return the associated merchant.
     *
     * @param apiKey public API key
     * @return the merchant if the key is valid and active
     */
    Optional<Merchant> findByApiKey(String apiKey);

    /**
     * Look up a merchant by ID.
     *
     * @param merchantId merchant ID
     * @return the merchant if found
     */
    Optional<Merchant> findById(Long merchantId);

    /**
     * Carrier for a freshly generated API key pair.
     */
    final class ApiKeyPair {
        private final String apiKey;
        private final String secret;

        public ApiKeyPair(String apiKey, String secret) {
            this.apiKey = apiKey;
            this.secret = secret;
        }

        public String getApiKey() { return apiKey; }
        public String getSecret() { return secret; }
    }
}
