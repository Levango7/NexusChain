package org.nexus.sdk;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nexus.sdk.wallet.WalletUtils;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Wallet management backed by nexus-core keystore utilities.
 */
public class Wallet {

    private final RpcClient rpcClient;
    private final String network;

    public Wallet(RpcClient rpcClient, String network) {
        this.rpcClient = rpcClient;
        this.network = network;
    }

    /**
     * Create a new wallet with a random password.
     * Uses WalletUtils.fromPassword() to generate a keystore with Ed25519 key pair.
     */
    public WalletInfo create() {
        byte[] pwBytes = new byte[16];
        new SecureRandom().nextBytes(pwBytes);
        String password = HexFormat.of().formatHex(pwBytes);
        ObjectNode node = WalletUtils.fromPassword(password);
        if (node == null || node.isEmpty()) throw new RuntimeException("Wallet generation failed");
        String keystoreJson = node.toString();
        String address = node.has("address") ? node.get("address").asText() : "";
        String privateKey = WalletUtils.obtainPrikey(keystoreJson, password);
        String publicKey = WalletUtils.prikeyToPubkey(privateKey);
        return new WalletInfo(address, privateKey, publicKey);
    }

    /**
     * Import a wallet from a private key hex string.
     */
    public WalletInfo fromPrivateKey(String privateKey) {
        String publicKey = WalletUtils.prikeyToPubkey(privateKey);
        if (publicKey == null || publicKey.isEmpty())
            throw new IllegalArgumentException("Invalid private key");
        String pubkeyHash = WalletUtils.pubkeyStrToPubkeyHashStr(publicKey);
        String address = WalletUtils.pubkeyHashToAddress(pubkeyHash);
        return new WalletInfo(address, privateKey, publicKey);
    }

    public WalletInfo fromMnemonic(String mnemonic, String path) {
        throw new UnsupportedOperationException("Mnemonic import not yet implemented");
    }

    public BigInteger getBalance(String address) {
        Object result = rpcClient.call("nexus_getBalance", new Object[]{address});
        if (result == null) return BigInteger.ZERO;
        if (result instanceof Number) return BigInteger.valueOf(((Number) result).longValue());
        return new BigInteger(result.toString());
    }

    public BigInteger getTokenBalance(String address, String tokenContract) {
        Object result = rpcClient.call("nexus_getTokenBalance", new Object[]{address, tokenContract});
        if (result == null) return BigInteger.ZERO;
        if (result instanceof Number) return BigInteger.valueOf(((Number) result).longValue());
        return new BigInteger(result.toString());
    }

    public boolean validateAddress(String address) {
        if (address == null) return false;
        int result = WalletUtils.verifyAddress(address);
        return result == 0;
    }

    public String getNetwork() { return network; }

    public static class WalletInfo {
        private final String address, privateKey, publicKey;
        public WalletInfo(String a, String pk, String pub) { address = a; privateKey = pk; publicKey = pub; }
        public String getAddress() { return address; }
        public String getPrivateKey() { return privateKey; }
        public String getPublicKey() { return publicKey; }
    }
}
