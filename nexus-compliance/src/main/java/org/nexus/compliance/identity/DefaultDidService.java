package org.nexus.compliance.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 DID 服务实现（Ed25519）。
 * <p>
 * 能力：
 * <ul>
 *   <li>{@link #createDid}：生成 Ed25519 密钥对，构造符合 W3C 最小结构的 DID 文档并登记</li>
 *   <li>{@link #resolveDid}：从进程内注册表解析 DID 文档（TODO(v2.0.0): 替换为链上解析 — tracked in v2.0.0 roadmap）</li>
 *   <li>{@link #issueCredential}：以本服务权威 DID 签发可验证凭证（对内容做 Ed25519 签名）</li>
 *   <li>{@link #verifyCredential}：解析发行者 DID 文档取公钥，验证签名与有效期</li>
 * </ul>
 * </p>
 */
@Service
public class DefaultDidService implements DidService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDidService.class);

    /** DID 方法名 */
    private static final String DID_METHOD = "did:nexus:";

    /** DID 注册表（did → 文档与密钥） */
    private final Map<String, DidRecord> registry = new ConcurrentHashMap<>();

    /** 本服务权威 DID（凭证发行者），懒加载生成 */
    private volatile DidRecord authorityDid;

    public DefaultDidService() {
        // Ed25519 自 JDK 15 起内置，无需第三方加密库
    }

    @Override
    public DidDocument createDid() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = generator.generateKeyPair();
            String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

            DidDocument document = new DidDocument();
            document.setId(DID_METHOD + publicKeyB64.substring(0, 32));
            document.setPublicKeys(List.of(publicKeyB64));
            document.setAuthentication(List.of(document.getId() + "#key-1"));
            document.setServiceEndpoints(List.of());

            registry.put(document.getId(), new DidRecord(document, keyPair.getPrivate()));
            log.info("DID created: {}", document.getId());
            return document;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create DID", e);
        }
    }

    @Override
    public DidDocument resolveDid(String did) {
        if (did == null || did.isBlank()) {
            return new DidDocument();
        }
        DidRecord record = registry.get(did);
        return record != null ? record.document : new DidDocument();
    }

    @Override
    public boolean verifyCredential(VerifiableCredential credential) {
        if (credential == null || credential.getIssuer() == null
                || credential.getSignature() == null || credential.getContent() == null) {
            return false;
        }
        // 有效期校验
        if (credential.getExpirationDate() != null
                && credential.getExpirationDate().isBefore(Instant.now())) {
            log.debug("Credential expired: issuer={}", credential.getIssuer());
            return false;
        }
        // 解析发行者公钥
        DidRecord issuerRecord = registry.get(credential.getIssuer());
        if (issuerRecord == null || issuerRecord.document.getPublicKeys() == null
                || issuerRecord.document.getPublicKeys().isEmpty()) {
            return false;
        }
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(
                    issuerRecord.document.getPublicKeys().get(0));
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(credential.getContent().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(credential.getSignature()));
        } catch (Exception e) {
            log.debug("Credential verification failed: issuer={}", credential.getIssuer(), e);
            return false;
        }
    }

    /**
     * 以本服务权威 DID 签发可验证凭证。
     *
     * @param holder         持有者 DID
     * @param content        凭证内容
     * @param expirationDate 有效期截止时间
     * @return 已签名的凭证
     */
    public VerifiableCredential issueCredential(String holder, String content, Instant expirationDate) {
        DidRecord authority = ensureAuthorityDid();
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(authority.privateKey);
            signer.update(content.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = Base64.getEncoder().encodeToString(signer.sign());

            VerifiableCredential credential = new VerifiableCredential();
            credential.setIssuer(authority.document.getId());
            credential.setHolder(holder);
            credential.setContent(content);
            credential.setSignature(signatureB64);
            credential.setExpirationDate(expirationDate);
            return credential;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue credential", e);
        }
    }

    /**
     * 获取权威发行者 DID 标识（懒加载）。
     *
     * @return 权威 DID
     */
    public String authorityDidId() {
        return ensureAuthorityDid().document.getId();
    }

    private DidRecord ensureAuthorityDid() {
        DidRecord local = authorityDid;
        if (local == null) {
            synchronized (this) {
                if (authorityDid == null) {
                    try {
                        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
                        KeyPair keyPair = generator.generateKeyPair();
                        String publicKeyB64 = Base64.getEncoder()
                                .encodeToString(keyPair.getPublic().getEncoded());
                        DidDocument document = new DidDocument();
                        document.setId(DID_METHOD + "authority");
                        document.setPublicKeys(List.of(publicKeyB64));
                        document.setAuthentication(List.of(document.getId() + "#key-1"));
                        document.setServiceEndpoints(List.of());
                        DidRecord record = new DidRecord(document, keyPair.getPrivate());
                        // 登记进注册表，使 verifyCredential 可解析发行者公钥
                        registry.put(document.getId(), record);
                        authorityDid = record;
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to initialize authority DID", e);
                    }
                }
                local = authorityDid;
            }
        }
        return local;
    }

    /** DID 注册记录：文档 + 私钥 */
    private static final class DidRecord {
        final DidDocument document;
        final PrivateKey privateKey;

        DidRecord(DidDocument document, PrivateKey privateKey) {
            this.document = document;
            this.privateKey = privateKey;
        }
    }
}
