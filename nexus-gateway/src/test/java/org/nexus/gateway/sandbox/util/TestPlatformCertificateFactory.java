package org.nexus.gateway.sandbox.util;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.UUID;

/**
 * 测试用平台证书工厂 — 生成自签名 X.509 证书，
 * 模拟微信支付平台证书用于回调验签测试。
 *
 * <p>简化方案：仅生成 RSA 2048 密钥对，公钥以 X.509 Base64 编码提供，
 * 私钥以 PKCS#8 Base64 编码提供。PEM 内容为模拟格式，证书序列号为随机生成。
 * 足够用于测试 {@link org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil#verifyCallbackSignatureWithPlatformCert}
 * 和 {@link org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil#decryptResource}。</p>
 *
 * <p>注意：{@link #getCertificate()} 在简化方案中返回 null，
 * 因为不使用 BouncyCastle 或 sun.security.x509 内部 API 无法生成真正的 X.509 证书。
 * 测试应使用 {@link #getPublicKeyBase64()} 进行验签测试，
 * 使用 {@link #getPrivateKeyBase64()} 模拟微信平台签名。</p>
 *
 * <p>经验来源：2026-09-26-payment-connector-test-sandbox-rsa-adaptation
 * （RSA 密钥动态生成方案）</p>
 */
public class TestPlatformCertificateFactory {

    private final X509Certificate certificate;
    private final String publicKeyBase64;
    private final String privateKeyBase64;
    private final String pemContent;
    private final String serialNumber;

    public TestPlatformCertificateFactory() {
        try {
            // 1. 生成 RSA 2048 密钥对
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair keyPair = kpg.generateKeyPair();

            // 2. 提取公钥 Base64（X.509 格式）— 用于平台证书验签
            this.publicKeyBase64 = Base64.getEncoder().encodeToString(
                    keyPair.getPublic().getEncoded());

            // 3. 提取私钥 Base64（PKCS#8 格式）— 用于模拟微信平台签名
            this.privateKeyBase64 = Base64.getEncoder().encodeToString(
                    keyPair.getPrivate().getEncoded());

            // 4. 生成模拟的 PEM 格式证书内容（使用公钥 DER 编码模拟证书内容）
            this.pemContent = buildPemContent(this.publicKeyBase64);

            // 5. 生成模拟的证书序列号（32 位十六进制，模拟微信证书序列号格式）
            this.serialNumber = UUID.randomUUID().toString().replace("-", "");

            // 6. 简化方案：不生成真正的 X.509 证书（无 BouncyCastle / 内部 API 依赖）
            this.certificate = null;
        } catch (Exception e) {
            throw new RuntimeException("生成测试平台证书失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 X.509 证书对象。
     *
     * <p>简化方案中返回 null，因为不使用 BouncyCastle 或 sun.security.x509
     * 无法生成真正的自签名 X.509 证书。测试应使用 {@link #getPublicKeyBase64()}
     * 进行验签测试。</p>
     *
     * @return null（简化方案不支持真正的 X.509 证书）
     */
    public X509Certificate getCertificate() {
        return certificate;
    }

    /**
     * 获取平台证书公钥（X.509 Base64 编码）。
     *
     * <p>用于 {@link org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil#verifyCallbackSignatureWithPlatformCert}
     * 的 platformCertPublicKey 参数，验签微信支付回调通知。</p>
     *
     * @return X.509 Base64 编码的公钥
     */
    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    /**
     * 获取私钥（PKCS#8 Base64 编码）。
     *
     * <p>用于模拟微信支付平台签名，在测试中用私钥对回调内容签名，
     * 再用 {@link #getPublicKeyBase64()} 对应的公钥验签。</p>
     *
     * @return PKCS#8 Base64 编码的私钥
     */
    public String getPrivateKeyBase64() {
        return privateKeyBase64;
    }

    /**
     * 获取模拟的 PEM 格式证书内容。
     *
     * <p>格式为标准 PEM 证书格式（-----BEGIN CERTIFICATE----- / -----END CERTIFICATE-----），
     * 内容为公钥 DER 编码的 Base64 表示，非真正的 X.509 证书。
     * 仅供测试中模拟证书下载/存储场景使用。</p>
     *
     * @return PEM 格式字符串
     */
    public String getPemContent() {
        return pemContent;
    }

    /**
     * 获取模拟的证书序列号。
     *
     * <p>格式为 32 位十六进制字符串（UUID 去横线），模拟微信支付平台证书序列号格式。
     * 仅供测试中模拟证书序列号匹配场景使用。</p>
     *
     * @return 32 位十六进制序列号
     */
    public String getSerialNumber() {
        return serialNumber;
    }

    /**
     * 构造 PEM 格式证书内容（每 64 字符换行，符合 RFC 7468）。
     *
     * @param base64Content Base64 编码的证书内容
     * @return PEM 格式字符串
     */
    private static String buildPemContent(String base64Content) {
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < base64Content.length(); i += 64) {
            sb.append(base64Content, i, Math.min(i + 64, base64Content.length()));
            sb.append('\n');
        }
        sb.append("-----END CERTIFICATE-----\n");
        return sb.toString();
    }
}