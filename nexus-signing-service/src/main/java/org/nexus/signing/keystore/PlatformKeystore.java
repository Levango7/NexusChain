package org.nexus.signing.keystore;

import org.nexus.sdk.wallet.WalletUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 服务端平台（热钱包）密钥库。
 *
 * <p>从 {@code org.nexus.wallet.signing.keystore.PlatformKeystore}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.keystore}。</p>
 *
 * <p>从配置加载平台私钥，使 gateway 可以请求签名而无需传输私钥。
 * keystore JSON 可内联提供（{@code wallet.keystore.json}）或作为文件路径
 * （启动时加载）。未设置时，调用方仍可通过现有 {@code /ClientToTransferAccount}
 * 端点按请求提供自己的 keystore。</p>
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
                log.error("Failed to read platform keystore file: {}", e.getMessage(), e);
                return;
            }
        }
        try {
            this.prikey = WalletUtils.obtainPrikey(json, keystorePassword);
            this.pubkey = WalletUtils.keystoreToPubkey(json, keystorePassword);
            log.info("Platform keystore loaded; pubkey present={}", pubkey != null);
        } catch (Exception e) {
            log.error("Failed to load platform keystore: {}", e.getMessage(), e);
        }
    }

    private boolean looksLikePath(String s) {
        return s.contains("/") || s.contains("\\") || s.toLowerCase().endsWith(".json");
    }

    public String getPrikey() { return prikey; }
    public String getPubkey() { return pubkey; }
    public boolean isLoaded() { return prikey != null && pubkey != null; }
}