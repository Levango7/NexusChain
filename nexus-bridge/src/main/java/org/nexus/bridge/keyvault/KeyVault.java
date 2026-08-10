package org.nexus.bridge.keyvault;

/**
 * KeyVault abstraction — signing operations without exposing raw private keys.
 *
 * <p>Implementations MUST keep private-key material inside the vault
 * (HSM, remote KMS, or an encrypted-at-rest file store).
 * The {@code sign()} method performs the cryptographic operation
 * entirely within the vault boundary.</p>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@code FileKeyVault} — AES-256-GCM encrypted file (dev/staging)</li>
 *   <li>{@code HsmKeyVault} — PKCS#11 HSM (production)</li>
 *   <li>{@code VaultKeyVault} — HashiCorp Vault transit engine (cloud)</li>
 * </ul>
 */
public interface KeyVault {

    /** Sign payload for the given validator. The private key never leaves the vault. */
    String sign(String validatorId, byte[] payload);

    /** Return the public key (hex) for verification — safe to expose. */
    String getPublicKey(String validatorId);

    /** Health check — is the vault reachable and key material accessible? */
    boolean isAvailable();

    /** List all validator IDs managed by this vault. */
    java.util.Set<String> getValidatorIds();
}
