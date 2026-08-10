package org.nexus.compliance.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultDidService} 单元测试。
 */
class DefaultDidServiceTest {

    private DefaultDidService service;

    @BeforeEach
    void setUp() {
        service = new DefaultDidService();
    }

    @Test
    void createDid_shouldProduceValidDocument() {
        DidDocument doc = service.createDid();

        assertNotNull(doc.getId());
        assertTrue(doc.getId().startsWith("did:nexus:"));
        assertNotNull(doc.getPublicKeys());
        assertEquals(1, doc.getPublicKeys().size());
        assertNotNull(doc.getAuthentication());
    }

    @Test
    void resolveDid_existing_shouldReturnDocument() {
        DidDocument created = service.createDid();

        DidDocument resolved = service.resolveDid(created.getId());

        assertEquals(created.getId(), resolved.getId());
        assertEquals(created.getPublicKeys(), resolved.getPublicKeys());
    }

    @Test
    void resolveDid_unknown_shouldReturnEmptyDocument() {
        DidDocument resolved = service.resolveDid("did:nexus:unknown");
        assertNotNull(resolved);
        assertEquals(null, resolved.getId());
    }

    @Test
    void issueAndVerifyCredential_valid_shouldPass() {
        VerifiableCredential credential = service.issueCredential(
                "did:nexus:holder1", "kyc-level=ENHANCED",
                Instant.now().plus(30, ChronoUnit.DAYS));

        assertTrue(service.verifyCredential(credential));
    }

    @Test
    void verifyCredential_tamperedContent_shouldFail() {
        VerifiableCredential credential = service.issueCredential(
                "did:nexus:holder1", "kyc-level=ENHANCED",
                Instant.now().plus(30, ChronoUnit.DAYS));
        credential.setContent("kyc-level=INSTITUTIONAL"); // 篡改内容

        assertFalse(service.verifyCredential(credential));
    }

    @Test
    void verifyCredential_expired_shouldFail() {
        VerifiableCredential credential = service.issueCredential(
                "did:nexus:holder1", "kyc-level=BASIC",
                Instant.now().minus(1, ChronoUnit.DAYS)); // 已过期

        assertFalse(service.verifyCredential(credential));
    }

    @Test
    void verifyCredential_null_shouldFail() {
        assertFalse(service.verifyCredential(null));
    }
}
