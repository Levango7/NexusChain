package org.nexus.compliance.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 链上 DID 服务（增强版）。
 *
 * <p>相比 {@link DefaultDidService}（进程内注册表），本服务将 DID 文档
 * 注册到链上存储（{@link ChainDidStore}），实现真正的链上 DID 注册与解析。
 *
 * <p>能力：
 * <ul>
 *   <li>{@link #createDid}：生成 Ed25519 密钥对，构造 DID 文档并注册到链上</li>
 *   <li>{@link #resolveDid}：从链上存储解析 DID 文档</li>
 *   <li>{@link #revokeDid}：链上吊销 DID</li>
 *   <li>{@link #updateDid}：链上更新 DID 文档</li>
 *   <li>{@link #issueCredential}：以权威 DID 签发可验证凭证</li>
 *   <li>{@link #verifyCredential}：从链上解析发行者公钥并验签</li>
 * </ul>
 *
 * @since 2.12.0
 */
@Service
public class ChainDidService implements DidService {

    private static final Logger log = LoggerFactory.getLogger(ChainDidService.class);
    private static final String DID_METHOD = "did:nexus:";

    private final ChainDidStore store;
    private final Map<String, PrivateKey> privateKeys = new ConcurrentHashMap<>();
    private volatile String authorityDidId;

    public ChainDidService(ChainDidStore store) {
        this.store = store;
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

            // 注册到链上
            store.register(document.getId(), document);
            // 私钥保留在本地（不出节点）
            privateKeys.put(document.getId(), keyPair.getPrivate());

            log.info("Chain DID created and registered: {}", document.getId());
            return document;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create chain DID", e);
        }
    }

    @Override
    public DidDocument resolveDid(String did) {
        if (did == null || did.isBlank()) {
            return new DidDocument();
        }
        // 从链上解析
        return store.resolve(did);
    }

    @Override
    public boolean verifyCredential(VerifiableCredential credential) {
        if (credential == null || credential.getIssuer() == null
                || credential.getSignature() == null || credential.getContent() == null) {
            return false;
        }
        if (credential.getExpirationDate() != null
                && credential.getExpirationDate().isBefore(Instant.now())) {
            return false;
        }
        // 检查发行者DID是否被吊销
        if (store.isRevoked(credential.getIssuer())) {
            log.warn("Credential issuer DID revoked: {}", credential.getIssuer());
            return false;
        }
        // 从链上解析发行者公钥
        DidDocument issuerDoc = store.resolve(credential.getIssuer());
        if (issuerDoc == null || issuerDoc.getPublicKeys() == null || issuerDoc.getPublicKeys().isEmpty()) {
            return false;
        }
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(issuerDoc.getPublicKeys().get(0));
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(credential.getContent().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(credential.getSignature()));
        } catch (Exception e) {
            log.debug("Chain credential verification failed: issuer={}", credential.getIssuer(), e);
            return false;
        }
    }

    /** 链上吊销 DID */
    public void revokeDid(String did) {
        store.revoke(did);
        privateKeys.remove(did);
        log.info("Chain DID revoked: {}", did);
    }

    /** 链上更新 DID 文档 */
    public void updateDid(String did, DidDocument document) {
        document.setId(did);
        store.update(did, document);
        log.info("Chain DID updated: {}", did);
    }

    /** 签发可验证凭证 */
    public VerifiableCredential issueCredential(String holder, String content, Instant expirationDate) {
        String issuerDid = ensureAuthorityDid();
        PrivateKey privateKey = privateKeys.get(issuerDid);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(content.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = Base64.getEncoder().encodeToString(signer.sign());

            VerifiableCredential credential = new VerifiableCredential();
            credential.setIssuer(issuerDid);
            credential.setHolder(holder);
            credential.setContent(content);
            credential.setSignature(signatureB64);
            credential.setExpirationDate(expirationDate);
            return credential;
        } catch (Exception e) {
            throw new IllegalStateException("=Failed to issue credential", e);
        }
    }

    public String authorityDidId() {
        return ensureAuthorityDid();
    }

    private String ensureAuthorityDid() {
        if (authorityDidId == null) {
            synchronized (this) {
                if (authorityDidId == null) {
                    DidDocument authority = createDid();
                    authorityDidId = authority.getId();
                }
            }
        }
        return authorityDidId;
    }
}