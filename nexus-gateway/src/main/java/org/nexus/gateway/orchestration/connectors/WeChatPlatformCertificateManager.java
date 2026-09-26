package org.nexus.gateway.orchestration.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 微信支付平台证书管理器 — 负责平台证书的获取、缓存、轮换与验签支持。
 *
 * <p>核心职责：</p>
 * <ol>
 *   <li>调用微信 GET /v3/certificates 接口获取平台证书列表</li>
 *   <li>将证书持久化到数据库（通过 Repository）</li>
 *   <li>检查证书有效期，过期前 1 小时自动触发更新</li>
 *   <li>根据证书序列号提供平台证书公钥，用于回调验签</li>
 * </ol>
 *
 * <p>签名方式：RSA-SHA256（使用商户私钥），Authorization 头格式为
 * WECHATPAY2-SHA256-RSA2048。</p>
 *
 * <p>证书解密：使用 APIv3 密钥做 AES-256-GCM 解密 encrypt_certificate 字段，
 * 解密后得到 PEM 格式的 X.509 证书。</p>
 *
 * <p>dry-run 保护：sandbox=true 或 merchantPrivateKey 为空时，
 * fetchPlatformCertificates() 直接返回 0，不发起真实 API 调用。</p>
 *
 * <p>ObjectMapper 通过构造器注入（经验来源：2026-09-26-spring-boot-objectmapper-constructor-injection），
 * 避免手动 new ObjectMapper() 导致的配置丢失和模块注册问题。</p>
 */
@Component
public class WeChatPlatformCertificateManager {

    private static final Logger log = LoggerFactory.getLogger(WeChatPlatformCertificateManager.class);
    private static final String CERTIFICATES_API_PATH = "/v3/certificates";

    private final WeChatPlatformCertificateRepository certificateRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${nexus.connectors.wechat.api-base-url:https://api.mch.weixin.qq.com}")
    private String apiBase;

    @Value("${nexus.connectors.wechat.merchant-private-key:}")
    private String merchantPrivateKey;

    @Value("${nexus.connectors.wechat.mch-id:}")
    private String mchId;

    @Value("${nexus.connectors.wechat.cert-serial-no:}")
    private String certSerialNo;

    @Value("${nexus.connectors.wechat.api-v3-key:}")
    private String apiV3Key;

    @Value("${nexus.connectors.wechat.sandbox:true}")
    private boolean sandbox;

    /**
     * 构造器注入 — ObjectMapper 通过 Spring 容器管理，确保 JavaTimeModule 等模块正确注册。
     *
     * <p>经验来源：2026-09-26-spring-boot-objectmapper-constructor-injection —
     * 不使用 new ObjectMapper()，而是通过构造器注入 Spring 自动配置的 ObjectMapper Bean。</p>
     *
     * @param certificateRepository 证书仓储
     * @param restTemplate          HTTP 客户端
     * @param objectMapper          JSON 序列化/反序列化器（Spring 管理）
     */
    @Autowired
    public WeChatPlatformCertificateManager(
            WeChatPlatformCertificateRepository certificateRepository,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.certificateRepository = certificateRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取有效的微信平台证书公钥。
     *
     * <p>查找流程：</p>
     * <ol>
     *   <li>先从数据库查找指定序列号的 ACTIVE 状态且未过期（距过期 > 1 小时）的证书</li>
     *   <li>如果没有有效证书，调用 fetchPlatformCertificates() 获取新证书</li>
     *   <li>获取后再查一次数据库</li>
     * </ol>
     *
     * @param serialNo 证书序列号（从 Wechatpay-Serial 头获取）
     * @return 平台证书公钥（X.509 Base64 编码），无可用证书时返回 null
     */
    public String getPlatformPublicKey(String serialNo) {
        // 1. 先从数据库查找
        Optional<WeChatPlatformCertificate> certOpt = certificateRepository.findBySerialNo(serialNo);
        if (certOpt.isPresent()) {
            WeChatPlatformCertificate cert = certOpt.get();
            if (cert.getStatus() == CertificateStatus.ACTIVE
                    && cert.getExpireTime().isAfter(Instant.now().plus(1, ChronoUnit.HOURS))) {
                return extractPublicKeyFromPem(cert.getCertificateContent());
            }
        }

        // 2. 没有有效证书，尝试获取新证书
        log.info("[WeChatCert] 序列号 {} 无有效证书，触发平台证书获取", serialNo);
        fetchPlatformCertificates();

        // 3. 再查一次
        certOpt = certificateRepository.findBySerialNo(serialNo);
        if (certOpt.isPresent()) {
            WeChatPlatformCertificate cert = certOpt.get();
            if (cert.getStatus() == CertificateStatus.ACTIVE) {
                return extractPublicKeyFromPem(cert.getCertificateContent());
            }
        }

        log.warn("[WeChatCert] 序列号 {} 仍无可用平台证书", serialNo);
        return null;
    }

    /**
     * 获取任意一张有效的平台证书公钥（不指定序列号）。
     *
     * <p>用于发起请求时不知道平台证书序列号的场景。
     * 优先返回距过期时间最远的证书。</p>
     *
     * @return 平台证书公钥（X.509 Base64 编码），无可用证书时返回 null
     */
    public String getAnyValidPlatformPublicKey() {
        // 1. 查找 ACTIVE 且距过期超过 1 小时的证书
        List<WeChatPlatformCertificate> certs = certificateRepository.findByStatusAndExpireTimeAfter(
                CertificateStatus.ACTIVE, Instant.now().plus(1, ChronoUnit.HOURS));
        if (!certs.isEmpty()) {
            return extractPublicKeyFromPem(certs.get(0).getCertificateContent());
        }

        // 2. 没有有效证书，尝试获取新证书
        log.info("[WeChatCert] 无有效平台证书，触发平台证书获取");
        fetchPlatformCertificates();

        // 3. 再查一次
        certs = certificateRepository.findByStatusAndExpireTimeAfter(
                CertificateStatus.ACTIVE, Instant.now().plus(1, ChronoUnit.HOURS));
        if (!certs.isEmpty()) {
            return extractPublicKeyFromPem(certs.get(0).getCertificateContent());
        }

        log.warn("[WeChatCert] 仍无可用平台证书");
        return null;
    }

    /**
     * 从微信 API 获取平台证书并缓存到数据库。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>构造 RSA-SHA256 签名的 GET /v3/certificates 请求</li>
     *   <li>解析返回的证书列表 JSON</li>
     *   <li>对每个证书：使用 APIv3 密钥 AES-256-GCM 解密 encrypt_certificate 字段</li>
     *   <li>将解密后的证书内容（PEM 格式）缓存到数据库</li>
     *   <li>更新不在新列表中的旧证书状态为 EXPIRED</li>
     * </ol>
     *
     * <p>dry-run 保护：sandbox=true 或 merchantPrivateKey 为空时直接返回 0。</p>
     *
     * @return 获取到的证书数量，失败返回 0
     */
    public int fetchPlatformCertificates() {
        // dry-run 模式保护
        if (sandbox || merchantPrivateKey == null || merchantPrivateKey.isBlank()) {
            log.info("[WeChatCert] dry-run 模式（sandbox={}，privateKey={}），跳过平台证书获取",
                    sandbox, merchantPrivateKey == null ? "null" : (merchantPrivateKey.isBlank() ? "empty" : "set"));
            return 0;
        }

        try {
            // 构造签名请求
            String method = "GET";
            String url = CERTIFICATES_API_PATH;
            String body = "";
            String authorization = buildAuthorization(method, url, body);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");
            headers.set("Authorization", authorization);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> resp = restTemplate.exchange(
                    apiBase + CERTIFICATES_API_PATH, HttpMethod.GET, entity, String.class);

            if (resp.getBody() == null) {
                log.warn("[WeChatCert] 平台证书 API 返回空响应");
                return 0;
            }

            // 解析返回的 JSON
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode dataArray = root.get("data");

            if (dataArray == null || !dataArray.isArray() || dataArray.isEmpty()) {
                log.warn("[WeChatCert] 平台证书 API 返回空数据列表");
                return 0;
            }

            // 收集新获取的证书序列号集合，用于后续旧证书状态更新
            Set<String> newSerialNos = new HashSet<>();
            int count = 0;

            for (JsonNode certNode : dataArray) {
                String serialNo = certNode.get("serial_no").asText();
                String effectiveTimeStr = certNode.get("effective_time").asText();
                String expireTimeStr = certNode.get("expire_time").asText();

                JsonNode encryptCert = certNode.get("encrypt_certificate");
                String nonce = encryptCert.get("nonce").asText();
                String associatedData = encryptCert.has("associated_data")
                        ? encryptCert.get("associated_data").asText() : "";
                String ciphertext = encryptCert.get("ciphertext").asText();

                // 使用 APIv3 密钥 AES-256-GCM 解密证书
                String pemContent = WeChatPaySignatureUtil.decryptResource(
                        ciphertext, nonce, associatedData, apiV3Key);

                // 解析时间（微信 API 返回 RFC 3339 格式，如 2021-01-01T00:00:00+08:00）
                Instant effectiveTime = OffsetDateTime.parse(effectiveTimeStr).toInstant();
                Instant expireTime = OffsetDateTime.parse(expireTimeStr).toInstant();

                // 保存到数据库（存在则更新，不存在则新建）
                Optional<WeChatPlatformCertificate> existing = certificateRepository.findBySerialNo(serialNo);
                WeChatPlatformCertificate cert;
                if (existing.isPresent()) {
                    cert = existing.get();
                    cert.setCertificateContent(pemContent);
                    cert.setEffectiveTime(effectiveTime);
                    cert.setExpireTime(expireTime);
                    cert.setFetchedAt(Instant.now());
                    cert.setStatus(CertificateStatus.ACTIVE);
                } else {
                    cert = new WeChatPlatformCertificate();
                    cert.setSerialNo(serialNo);
                    cert.setCertificateContent(pemContent);
                    cert.setEffectiveTime(effectiveTime);
                    cert.setExpireTime(expireTime);
                    cert.setFetchedAt(Instant.now());
                    cert.setStatus(CertificateStatus.ACTIVE);
                }
                certificateRepository.save(cert);

                newSerialNos.add(serialNo);
                count++;
            }

            // 将不在新列表中的旧 ACTIVE 证书标记为 EXPIRED
            List<WeChatPlatformCertificate> activeCerts = certificateRepository
                    .findByStatusOrderByExpireTimeDesc(CertificateStatus.ACTIVE);
            for (WeChatPlatformCertificate activeCert : activeCerts) {
                if (!newSerialNos.contains(activeCert.getSerialNo())) {
                    activeCert.setStatus(CertificateStatus.EXPIRED);
                    certificateRepository.save(activeCert);
                    log.info("[WeChatCert] 旧证书 {} 标记为 EXPIRED", activeCert.getSerialNo());
                }
            }

            log.info("[WeChatCert] 成功获取 {} 张平台证书", count);
            return count;
        } catch (Exception e) {
            log.error("[WeChatCert] 获取平台证书失败: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 检查是否需要更新证书（距过期不足 1 小时或无有效证书）。
     *
     * @return true 需要更新
     */
    public boolean needsRefresh() {
        List<WeChatPlatformCertificate> certs = certificateRepository.findByStatusAndExpireTimeAfter(
                CertificateStatus.ACTIVE, Instant.now().plus(1, ChronoUnit.HOURS));
        return certs.isEmpty();
    }

    /**
     * 从 PEM 格式证书内容中提取公钥（X.509 Base64 编码）。
     *
     * <p>PEM 格式：-----BEGIN CERTIFICATE-----\n<Base64>\n-----END CERTIFICATE-----</p>
     * <p>流程：解析 PEM → 提取 X.509 公钥 → Base64 编码</p>
     *
     * @param pemContent PEM 格式证书内容
     * @return X.509 Base64 编码的公钥
     * @throws RuntimeException 解析失败时抛出
     */
    private String extractPublicKeyFromPem(String pemContent) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(pemContent.getBytes(StandardCharsets.UTF_8)));
            return Base64.getEncoder().encodeToString(cert.getPublicKey().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("从 PEM 提取公钥失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造微信支付 V3 Authorization 头（用于获取证书的请求）。
     *
     * <p>使用商户私钥做 RSA-SHA256 签名。
     * 格式：WECHATPAY2-SHA256-RSA2048 mchid="...",nonce_str="...",timestamp="...",
     * serial_no="...",signature="..."</p>
     *
     * @param method HTTP 方法
     * @param url    请求 URL（不含域名）
     * @param body   请求体（GET 请求为空字符串）
     * @return 完整的 Authorization 头值
     */
    private String buildAuthorization(String method, String url, String body) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = UUID.randomUUID().toString().replace("-", "");

        String signature = WeChatPaySignatureUtil.generateRsaSignature(
                method, url, timestamp, nonceStr, body, merchantPrivateKey);

        return String.format(
                "WECHATPAY2-SHA256-RSA2048 mchid=\"%s\",nonce_str=\"%s\",timestamp=\"%s\",serial_no=\"%s\",signature=\"%s\"",
                mchId, nonceStr, timestamp, certSerialNo, signature);
    }
}