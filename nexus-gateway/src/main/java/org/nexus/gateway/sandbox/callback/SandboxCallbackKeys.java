package org.nexus.gateway.sandbox.callback;

import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * 沙盒回调模拟器密钥持有者 — 在启动时生成测试 RSA 密钥对，
 * 供 {@link CallbackSimulatorService} 构造签名回调通知使用。
 *
 * <p>包含两组密钥：</p>
 * <ul>
 *   <li>微信平台密钥对 — 模拟微信支付平台签名（RSA-SHA256），对应公钥注册到
 *       {@link org.nexus.gateway.orchestration.connectors.WeChatPlatformCertificateManager} 数据库</li>
 *   <li>支付宝商户密钥对 — 模拟支付宝商户签名（RSA2），对应公钥作为 alipay-public-key 配置</li>
 * </ul>
 *
 * <p>密钥在 {@link SandboxCallbackCertInitializer} 中生成并注入此 Bean。
 * 所有密钥仅在 sandbox profile 下存在，绝不用于生产环境。</p>
 */
@Component
public class SandboxCallbackKeys {

    /** 微信平台 RSA 密钥对（模拟微信支付平台证书） */
    private KeyPair wechatPlatformKeyPair;
    /** 微信平台证书序列号（32 位十六进制，模拟微信证书序列号格式） */
    private String wechatPlatformSerialNo;
    /** 微信 APIv3 密钥（32 字节，用于 AES-256-GCM 加密/解密 resource） */
    private String wechatApiV3Key;

    /** 支付宝商户 RSA 密钥对（模拟支付宝商户签名） */
    private KeyPair alipayMerchantKeyPair;
    /** 支付宝应用 ID */
    private String alipayAppId;

    /**
     * 获取微信平台私钥（PKCS#8 Base64 编码）。
     *
     * @return PKCS#8 Base64 编码的私钥字符串
     */
    public String getWechatPlatformPrivateKeyBase64() {
        return Base64.getEncoder().encodeToString(
                wechatPlatformKeyPair.getPrivate().getEncoded());
    }

    /**
     * 获取微信平台公钥（X.509 Base64 编码）。
     *
     * @return X.509 Base64 编码的公钥字符串
     */
    public String getWechatPlatformPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(
                wechatPlatformKeyPair.getPublic().getEncoded());
    }

    /**
     * 获取微信平台私钥对象。
     *
     * @return {@link PrivateKey} 实例
     */
    public PrivateKey getWechatPlatformPrivateKey() {
        return wechatPlatformKeyPair.getPrivate();
    }

    /**
     * 获取微信平台公钥对象。
     *
     * @return {@link PublicKey} 实例
     */
    public PublicKey getWechatPlatformPublicKey() {
        return wechatPlatformKeyPair.getPublic();
    }

    /**
     * 获取微信平台证书序列号。
     *
     * @return 32 位十六进制序列号字符串
     */
    public String getWechatPlatformSerialNo() {
        return wechatPlatformSerialNo;
    }

    /**
     * 获取微信 APIv3 密钥。
     *
     * @return 32 字节 APIv3 密钥字符串
     */
    public String getWechatApiV3Key() {
        return wechatApiV3Key;
    }

    /**
     * 获取支付宝商户私钥（PKCS#8 Base64 编码）。
     *
     * @return PKCS#8 Base64 编码的私钥字符串
     */
    public String getAlipayMerchantPrivateKeyBase64() {
        return Base64.getEncoder().encodeToString(
                alipayMerchantKeyPair.getPrivate().getEncoded());
    }

    /**
     * 获取支付宝商户公钥（X.509 Base64 编码）。
     *
     * <p>此公钥需配置为 {@code nexus.connectors.alipay.alipay-public-key}，
     * 供 {@link org.nexus.gateway.orchestration.controller.PaymentCallbackController}
     * 验签使用。</p>
     *
     * @return X.509 Base64 编码的公钥字符串
     */
    public String getAlipayMerchantPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(
                alipayMerchantKeyPair.getPublic().getEncoded());
    }

    /**
     * 获取支付宝应用 ID。
     *
     * @return 支付宝应用 ID 字符串
     */
    public String getAlipayAppId() {
        return alipayAppId;
    }

    // === Setter 方法（由 SandboxCallbackCertInitializer 调用） ===

    public void setWechatPlatformKeyPair(KeyPair wechatPlatformKeyPair) {
        this.wechatPlatformKeyPair = wechatPlatformKeyPair;
    }

    public void setWechatPlatformSerialNo(String wechatPlatformSerialNo) {
        this.wechatPlatformSerialNo = wechatPlatformSerialNo;
    }

    public void setWechatApiV3Key(String wechatApiV3Key) {
        this.wechatApiV3Key = wechatApiV3Key;
    }

    public void setAlipayMerchantKeyPair(KeyPair alipayMerchantKeyPair) {
        this.alipayMerchantKeyPair = alipayMerchantKeyPair;
    }

    public void setAlipayAppId(String alipayAppId) {
        this.alipayAppId = alipayAppId;
    }
}