package org.nexus.wallet.keystore;

import org.nexus.sdk.wallet.WalletUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Server-side platform (hot-wallet) keystore for exchange-wallet.
 *
 * <p>Loads the platform private key from a configured keystore so that the gateway
 * can request signing WITHOUT ever transmitting a private key. The keystore JSON
 * may be supplied inline ({@code wallet.keystore.json}) or as a file path (loaded
 * at startup). When unset, callers may still supply their own keystore per-request
 * via the existing {@code /ClientToTransferAccount} endpoint.</p>
 */
@Component
public class PlatformKeystore {

    private static final Logger log = LoggerFactory.getLogger(PlatformKeystore.class);

    @Value("${wallet.keystore.json:}")
    private String keystoreJson;

    @Value("${wallet.keystore.password:}")
    private String keystorePassword;

    private String prikey;
    private String pubkey;

    @PostConstruct
    public void init() {
        if (keystoreJson == null || keystoreJson.isBlank()) {
            log.warn("wallet.keystore.json not configured; /api/v1/transfers/sign requires a server keystore or caller-supplied keystore");
            return;
        }
        String json = keystoreJson;
        if (looksLikePath(keystoreJson)) {
            try {
                json = new String(Files.readAllBytes(Paths.get(keystoreJson)));
            } catch (Exception e) {
                log.error("Failed to read platform keystore file: {}", e.getMessage());
                return;
            }
        }
        try {
            this.prikey = WalletUtils.obtainPrikey(json, keystorePassword);
            this.pubkey = WalletUtils.keystoreToPubkey(json, keystorePassword);
            log.info("Platform keystore loaded; pubkey present={}", pubkey != null);
        } catch (Exception e) {
            log.error("Failed to load platform keystore: {}", e.getMessage());
        }
    }

    private boolean looksLikePath(String s) {
        return s.contains("/") || s.contains("\\") || s.toLowerCase().endsWith(".json");
    }

    public String getPrikey() { return prikey; }
    public String getPubkey() { return pubkey; }
    public boolean isLoaded() { return prikey != null && pubkey != null; }
}
