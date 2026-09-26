package org.nexus.gateway.sandbox.util;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * 测试用 RSA 密钥对生成工具 — 为端到端测试生成临时 RSA 密钥对，
 * 模拟微信支付商户私钥签名和平台证书公钥验签。
 */
public class TestKeyPairGenerator {

    private final String privateKeyBase64;
    private final String publicKeyBase64;

    public TestKeyPairGenerator() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair keyPair = kpg.generateKeyPair();
            this.privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            this.publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    public String getPrivateKeyBase64() { return privateKeyBase64; }
    public String getPublicKeyBase64() { return publicKeyBase64; }
}