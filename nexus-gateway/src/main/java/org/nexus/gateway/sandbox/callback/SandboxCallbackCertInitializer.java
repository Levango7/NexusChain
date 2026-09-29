package org.nexus.gateway.sandbox.callback;

import org.nexus.gateway.orchestration.connectors.CertificateStatus;
import org.nexus.gateway.orchestration.connectors.WeChatPlatformCertificate;
import org.nexus.gateway.orchestration.connectors.WeChatPlatformCertificateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * 沙盒回调模拟器证书初始化器 — 在 sandbox profile 启动时：
 *
 * <ol>
 *   <li>生成微信平台测试 RSA 2048 密钥对</li>
 *   <li>创建自签名 X.509 证书并注册到 {@link WeChatPlatformCertificateRepository}</li>
 *   <li>生成支付宝商户测试 RSA 2048 密钥对</li>
 *   <li>将所有密钥注入 {@link SandboxCallbackKeys}</li>
 *   <li>设置固定的 APIv3 密钥和支付宝 appId</li>
 * </ol>
 *
 * <p>这样回调模拟器发出的模拟回调通知可以通过回调端点的验签流程，
 * 因为验签使用的公钥与模拟器签名的私钥是同一密钥对。</p>
 *
 * <p>使用 {@code sun.security.x509} 内部 API 创建自签名 X.509 证书，
 * 需要 JVM 参数 {@code --add-exports java.base/sun.security.x509=ALL-UNNAMED}。
 * 如果该 API 不可访问，则跳过证书注册并记录警告。</p>
 */
@Component
@Profile("sandbox")
@Order(20)  // 在 SandboxStartupRunner (默认 Order) 之后执行
public class SandboxCallbackCertInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxCallbackCertInitializer.class);

    /** RSA 密钥长度 */
    private static final int RSA_KEY_SIZE = 2048;

    /** 固定的 APIv3 密钥（32 字节，用于 AES-256-GCM 加密/解密） */
    private static final String DEFAULT_API_V3_KEY = "sandbox-apiv3-key-32bytes-long!";

    /** 固定的支付宝测试 appId */
    private static final String DEFAULT_ALIPAY_APP_ID = "2021000000000001";

    /** 证书有效期（年） */
    private static final int CERT_VALIDITY_YEARS = 10;

    private final SandboxCallbackKeys callbackKeys;
    private final WeChatPlatformCertificateRepository certificateRepository;

    /**
     * 构造器注入。
     *
     * @param callbackKeys          沙盒回调密钥持有者
     * @param certificateRepository 微信平台证书仓储
     */
    public SandboxCallbackCertInitializer(
            SandboxCallbackKeys callbackKeys,
            WeChatPlatformCertificateRepository certificateRepository) {
        this.callbackKeys = callbackKeys;
        this.certificateRepository = certificateRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[SandboxCallback] 开始初始化回调模拟器密钥和证书");

        // 1. 生成微信平台测试密钥对
        KeyPair wechatKeyPair = generateRsaKeyPair();
        String serialNo = UUID.randomUUID().toString().replace("-", "");

        callbackKeys.setWechatPlatformKeyPair(wechatKeyPair);
        callbackKeys.setWechatPlatformSerialNo(serialNo);
        callbackKeys.setWechatApiV3Key(DEFAULT_API_V3_KEY);

        log.info("[SandboxCallback] 微信平台测试密钥对已生成, 序列号: {}", serialNo);

        // 2. 创建自签名 X.509 证书并注册到数据库
        registerWeChatPlatformCertificate(wechatKeyPair, serialNo);

        // 3. 生成支付宝商户测试密钥对
        KeyPair alipayKeyPair = generateRsaKeyPair();

        callbackKeys.setAlipayMerchantKeyPair(alipayKeyPair);
        callbackKeys.setAlipayAppId(DEFAULT_ALIPAY_APP_ID);

        log.info("[SandboxCallback] 支付宝商户测试密钥对已生成, appId: {}", DEFAULT_ALIPAY_APP_ID);
        log.info("[SandboxCallback] 支付宝公钥 (Base64): {}", callbackKeys.getAlipayMerchantPublicKeyBase64());
        log.info("[SandboxCallback] 回调模拟器初始化完成");
    }

    /**
     * 生成 RSA 2048 密钥对。
     *
     * @return 新生成的 RSA 密钥对
     */
    private KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(RSA_KEY_SIZE);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("生成 RSA 密钥对失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建自签名 X.509 证书并注册到数据库。
     *
     * <p>使用 {@code sun.security.x509} 内部 API 创建自签名证书。
     * 如果该 API 不可访问（Java 17 强封装），则使用备用方案：
     * 直接将公钥的 X.509 DER 编码以 Base64 格式存入 certificateContent 字段，
     * 并在 extractPublicKeyFromPem 中通过 CertificateFactory 解析。</p>
     *
     * <p>经验来源：2026-09-26-payment-connector-test-sandbox-rsa-adaptation
     * （RSA 密钥动态生成方案）</p>
     *
     * @param keyPair RSA 密钥对
     * @param serialNo 证书序列号
     */
    private void registerWeChatPlatformCertificate(KeyPair keyPair, String serialNo) {
        // 检查是否已存在相同序列号的证书
        Optional<WeChatPlatformCertificate> existing = certificateRepository.findBySerialNo(serialNo);
        if (existing.isPresent()) {
            log.info("[SandboxCallback] 平台证书 {} 已存在，跳过注册", serialNo);
            return;
        }

        // 尝试创建自签名 X.509 证书
        String pemContent = createSelfSignedCertificatePem(keyPair, serialNo);

        WeChatPlatformCertificate cert = new WeChatPlatformCertificate();
        cert.setSerialNo(serialNo);
        cert.setCertificateContent(pemContent);
        cert.setEffectiveTime(Instant.now());
        cert.setExpireTime(certificateExpireTime());
        cert.setFetchedAt(Instant.now());
        cert.setStatus(CertificateStatus.ACTIVE);

        certificateRepository.save(cert);
        log.info("[SandboxCallback] 微信平台证书已注册到数据库, 序列号: {}", serialNo);
    }

    /**
     * 创建自签名 X.509 证书的 PEM 内容。
     *
     * <p>优先使用 {@code sun.security.x509} API 创建真正的自签名 X.509 证书。
     * 如果该 API 不可访问，则回退到备用方案：构造包含公钥 DER 编码的简化 PEM。</p>
     *
     * @param keyPair  RSA 密钥对
     * @param serialNo 证书序列号
     * @return PEM 格式的证书内容
     */
    private String createSelfSignedCertificatePem(KeyPair keyPair, String serialNo) {
        // 尝试使用 sun.security.x509 创建自签名证书
        try {
            X509Certificate cert = createSelfSignedCertViaInternalApi(keyPair, serialNo);
            if (cert != null) {
                return certificateToPem(cert);
            }
        } catch (Throwable e) {
            log.warn("[SandboxCallback] sun.security.x509 API 不可访问 ({}), 使用备用方案", e.getMessage());
        }

        // 备用方案：构造包含公钥 DER 编码的 PEM
        // 注意：CertificateFactory.getInstance("X.509") 无法解析此格式，
        // 但 WeChatPlatformCertificateManager.extractPublicKeyFromPem 会抛出异常。
        // 在此场景下，回调验签将无法通过，模拟器会返回验签失败的结果。
        log.warn("[SandboxCallback] 使用备用 PEM 方案，回调验签可能失败。"
                + "建议添加 JVM 参数 --add-exports java.base/sun.security.x509=ALL-UNNAMED");
        return publicKeyToPem(keyPair.getPublic());
    }

    /**
     * 使用 sun.security.x509 内部 API 创建自签名 X.509 证书。
     *
     * @param keyPair  RSA 密钥对
     * @param serialNo 证书序列号
     * @return 自签名 X.509 证书，或 null（如果 API 不可访问）
     */
    private X509Certificate createSelfSignedCertViaInternalApi(KeyPair keyPair, String serialNo) {
        try {
            // 使用反射访问 sun.security.x509 类，避免编译期依赖
            Class<?> x500NameClass = Class.forName("sun.security.x509.X500Name");
            Class<?> certValidityClass = Class.forName("sun.security.x509.CertificateValidity");
            Class<?> x509CertInfoClass = Class.forName("sun.security.x509.X509CertInfo");
            Class<?> x509CertImplClass = Class.forName("sun.security.x509.X509CertImpl");
            Class<?> algorithmIdClass = Class.forName("sun.security.x509.AlgorithmId");
            Class<?> certSerialNumberClass = Class.forName("sun.security.x509.CertificateSerialNumber");
            Class<?> certAlgorithmIdClass = Class.forName("sun.security.x509.CertificateAlgorithmId");
            Class<?> certSubjectNameClass = Class.forName("sun.security.x509.CertificateSubjectName");
            Class<?> certIssuerNameClass = Class.forName("sun.security.x509.CertificateIssuerName");
            Class<?> certX509KeyClass = Class.forName("sun.security.x509.CertificateX509Key");

            // 构造 X500Name
            Object x500Name = x500NameClass.getConstructor(String.class)
                    .newInstance("CN=NexusSandbox,O=NexusChain,C=CN");

            // 构造 CertificateValidity
            java.util.Date notBefore = java.util.Date.from(Instant.now());
            java.util.Date notAfter = java.util.Date.from(certificateExpireTime());
            Object certValidity = certValidityClass.getConstructor(java.util.Date.class, java.util.Date.class)
                    .newInstance(notBefore, notAfter);

            // 构造 CertificateSerialNumber
            Object certSerialNumber = certSerialNumberClass.getConstructor(BigInteger.class)
                    .newInstance(new BigInteger(serialNo, 16));

            // 构造 AlgorithmId
            Object algorithmId = algorithmIdClass.getField("SHA256withRSA").get(null);

            // 构造 CertificateAlgorithmId
            Object certAlgorithmId = certAlgorithmIdClass.getConstructor(algorithmIdClass)
                    .newInstance(algorithmId);

            // 构造 X509CertInfo
            Object certInfo = x509CertInfoClass.getDeclaredConstructor().newInstance();

            // 获取 set 方法（X509CertInfo.set(String, Object)）
            java.lang.reflect.Method setMethod = x509CertInfoClass.getMethod("set", String.class, Object.class);

            // 设置证书字段
            java.lang.reflect.Field validField = x509CertInfoClass.getDeclaredField("VALIDITY");
            validField.setAccessible(true);
            setMethod.invoke(certInfo, validField.get(null), certValidity);

            java.lang.reflect.Field serialNumField = x509CertInfoClass.getDeclaredField("SERIAL_NUM");
            serialNumField.setAccessible(true);
            setMethod.invoke(certInfo, serialNumField.get(null), certSerialNumber);

            java.lang.reflect.Field algIdField = x509CertInfoClass.getDeclaredField("ALGORITHM_ID");
            algIdField.setAccessible(true);
            setMethod.invoke(certInfo, algIdField.get(null), certAlgorithmId);

            java.lang.reflect.Field subjectField = x509CertInfoClass.getDeclaredField("SUBJECT");
            subjectField.setAccessible(true);
            Object subjectName = certSubjectNameClass.getConstructor(x500NameClass).newInstance(x500Name);
            setMethod.invoke(certInfo, subjectField.get(null), subjectName);

            java.lang.reflect.Field issuerField = x509CertInfoClass.getDeclaredField("ISSUER");
            issuerField.setAccessible(true);
            Object issuerName = certIssuerNameClass.getConstructor(x500NameClass).newInstance(x500Name);
            setMethod.invoke(certInfo, issuerField.get(null), issuerName);

            java.lang.reflect.Field keyField = x509CertInfoClass.getDeclaredField("KEY");
            keyField.setAccessible(true);
            Object x509Key = certX509KeyClass.getConstructor(java.security.PublicKey.class)
                    .newInstance(keyPair.getPublic());
            setMethod.invoke(certInfo, keyField.get(null), x509Key);

            // 构造 X509CertImpl
            java.lang.reflect.Constructor<?> certImplCtor = x509CertImplClass.getDeclaredConstructor(x509CertInfoClass);
            certImplCtor.setAccessible(true);
            Object certImpl = certImplCtor.newInstance(certInfo);

            // 签名
            java.lang.reflect.Method signMethod = x509CertImplClass.getMethod("sign", PrivateKey.class, String.class);
            signMethod.invoke(certImpl, keyPair.getPrivate(), "SHA256withRSA");

            return (X509Certificate) certImpl;
        } catch (Throwable e) {
            log.debug("[SandboxCallback] 反射创建自签名证书失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 X.509 证书转换为 PEM 格式字符串。
     *
     * @param cert X.509 证书
     * @return PEM 格式字符串
     */
    private String certificateToPem(X509Certificate cert) {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(os, java.nio.charset.StandardCharsets.UTF_8));
            pw.println("-----BEGIN CERTIFICATE-----");
            String base64 = Base64.getEncoder().encodeToString(cert.getEncoded());
            for (int i = 0; i < base64.length(); i += 64) {
                pw.println(base64.substring(i, Math.min(i + 64, base64.length())));
            }
            pw.println("-----END CERTIFICATE-----");
            pw.flush();
            return os.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("证书转 PEM 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将公钥转换为 PEM 格式字符串（备用方案）。
     *
     * <p>注意：此格式不是真正的 X.509 证书 PEM，
     * {@link java.security.cert.CertificateFactory#getInstance(String)} 无法解析。
     * 仅在 sun.security.x509 API 不可访问时作为回退方案使用。</p>
     *
     * @param publicKey 公钥
     * @return PEM 格式字符串
     */
    private String publicKeyToPem(java.security.PublicKey publicKey) {
        String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length()));
            sb.append('\n');
        }
        sb.append("-----END CERTIFICATE-----\n");
        return sb.toString();
    }

    /**
     * 证书到期时间 = 当前时刻 + {@code CERT_VALIDITY_YEARS} 年。
     *
     * <p>不能用 {@code Instant.plus(n, ChronoUnit.YEARS)}：{@code Instant} 只支持
     * 时长型单位，年/月属日历型，会抛 {@link java.time.temporal.UnsupportedTemporalTypeException}
     * （此即本类原先在 sandbox profile 下启动即崩的原因）。须经 {@link ZonedDateTime}
     * 做日历运算再折回 {@code Instant}，并显式固定 UTC 偏移以免随系统时区浮动。</p>
     *
     * @return 到期时刻
     */
    private static Instant certificateExpireTime() {
        return ZonedDateTime.now(ZoneOffset.UTC)
                .plusYears(CERT_VALIDITY_YEARS)
                .toInstant();
    }
}