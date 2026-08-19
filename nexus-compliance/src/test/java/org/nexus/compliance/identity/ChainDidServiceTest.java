package org.nexus.compliance.identity;

import org.junit.jupiter.api.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 链上 DID 服务测试。
 *
 * <p>验证 ChainDidService 的链上 DID 注册、解析、吊销、更新和可验证凭证功能。
 * 使用 InMemoryChainDidStore 模拟链上存储。
 *
 * @since 2.12.0
 */
@DisplayName("链上DID服务：注册+解析+吊销+更新+凭证")
class ChainDidServiceTest {

    private InMemoryChainDidStore store;
    private ChainDidService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryChainDidStore();
        service = new ChainDidService(store);
    }

    @Test
    @Order(1)
    @DisplayName("1. 创建DID→链上注册→链上解析→文档一致")
    void createDid_registeredAndResolvable() {
        DidDocument created = service.createDid();
        assertNotNull(created.getId());
        assertTrue(store.exists(created.getId()), "DID 应注册到链上");

        DidDocument resolved = service.resolveDid(created.getId());
        assertNotNull(resolved, "链上解析应返回文档");
        assertEquals(created.getId(), resolved.getId(), "解析的DID应一致");
        assertEquals(created.getPublicKeys(), resolved.getPublicKeys(), "公钥应一致");
    }

    @Test
    @Order(2)
    @DisplayName("2. 链上解析不存在的DID→返回null")
    void resolveNonExistent_returnsNull() {
        DidDocument resolved = service.resolveDid("did:nexus:nonexistent");
        assertNull(resolved, "不存在的DID应返回null");
    }

    @Test
    @Order(3)
    @DisplayName("3. DID吊销→链上标记→凭证验证失败")
    void revokeDid_credentialFails() {
        // 先签发凭证（用authority DID）
        VerifiableCredential credential = service.issueCredential(
                "holder-1", "test content", Instant.now().plus(1, ChronoUnit.DAYS));
        String authorityId = service.authorityDidId();
        assertEquals(authorityId, credential.getIssuer(), "issuer应为authority DID");
        assertFalse(store.isRevoked(authorityId), "authority DID不应被吊销");

        // 吊销前：验证通过
        assertTrue(service.verifyCredential(credential), "authority凭证应验证通过");

        // 吊销authority DID
        service.revokeDid(authorityId);
        assertTrue(store.isRevoked(authorityId), "authority DID应被吊销");

        // 吊销后：凭证验证失败
        assertFalse(service.verifyCredential(credential), "吊销后凭证应验证失败");
    }

    @Test
    @Order(4)
    @DisplayName("4. DID更新→链上更新→解析返回新文档")
    void updateDid_resolvesNewDocument() {
        DidDocument did = service.createDid();
        String didId = did.getId();

        // 更新DID文档
        DidDocument updated = new DidDocument();
        updated.setId(didId);
        updated.setPublicKeys(List.of("newPublicKey"));
        updated.setAuthentication(List.of(didId + "#key-2"));
        updated.setServiceEndpoints(List.of("https://new.service.endpoint"));
        service.updateDid(didId, updated);

        // 解析返回新文档
        DidDocument resolved = service.resolveDid(didId);
        assertNotNull(resolved);
        assertEquals("newPublicKey", resolved.getPublicKeys().get(0), "应返回更新后的公钥");
        assertEquals("https://new.service.endpoint", resolved.getServiceEndpoints().get(0),
                "应返回更新后的服务端点");
    }

    @Test
    @Order(5)
    @DisplayName("5. 可验证凭证→链上发行→链上验证")
    void credential_issueAndVerify() {
        String holder = service.createDid().getId();
        String content = "KYC verified: holder is eligible";
        Instant expiration = Instant.now().plus(30, ChronoUnit.DAYS);

        VerifiableCredential credential = service.issueCredential(holder, content, expiration);
        assertNotNull(credential.getIssuer());
        assertNotNull(credential.getSignature());
        assertEquals(content, credential.getContent());

        assertTrue(service.verifyCredential(credential), "凭证应验证通过");
    }

    @Test
    @Order(6)
    @DisplayName("6. 过期凭证→验证失败")
    void expiredCredential_fails() {
        String holder = service.createDid().getId();
        Instant pastExpiration = Instant.now().minus(1, ChronoUnit.DAYS);

        VerifiableCredential credential = service.issueCredential(holder, "expired", pastExpiration);
        assertFalse(service.verifyCredential(credential), "过期凭证应验证失败");
    }

    @Test
    @Order(7)
    @DisplayName("7. 多DID独立管理→互不干扰")
    void multipleDids_independent() {
        DidDocument did1 = service.createDid();
        DidDocument did2 = service.createDid();
        DidDocument did3 = service.createDid();

        assertNotEquals(did1.getId(), did2.getId(), "DID应各不相同");
        assertNotEquals(did2.getId(), did3.getId(), "DID应各不相同");
        assertNotEquals(did1.getId(), did3.getId(), "DID应各不相同");

        // 各自独立解析
        assertEquals(did1.getId(), service.resolveDid(did1.getId()).getId());
        assertEquals(did2.getId(), service.resolveDid(did2.getId()).getId());
        assertEquals(did3.getId(), service.resolveDid(did3.getId()).getId());

        // 吊销一个不影响其他
        service.revokeDid(did1.getId());
        assertTrue(store.isRevoked(did1.getId()), "did1应被吊销");
        assertFalse(store.isRevoked(did2.getId()), "did2不应被吊销");
        assertFalse(store.isRevoked(did3.getId()), "did3不应被吊销");
    }
}