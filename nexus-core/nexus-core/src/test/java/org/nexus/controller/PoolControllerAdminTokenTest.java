package org.nexus.controller;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.account.Transaction;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.wallet.KeystoreAction;
import org.nexus.pool.PendingNonce;
import org.nexus.pool.PeningTransPool;
import org.nexus.pool.TransPool;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PoolControllerAdminTokenTest {
    private final byte[] tokenBytes = "pool-controller-test-only".getBytes(StandardCharsets.UTF_8);
    private final String token = Hex.encodeHexString(tokenBytes);
    private final String digest = Hex.encodeHexString(SHA3Utility.sha3256(tokenBytes));
    private final PeningTransPool pool = mock(PeningTransPool.class);

    private PoolController controller(String configuredDigest) {
        PoolController controller = new PoolController();
        controller.peningTransPool = pool;
        ReflectionTestUtils.setField(controller, "adminTokenSha3", configuredDigest);
        return controller;
    }

    private void assertDenied(String configuredDigest, String suppliedToken) {
        PoolController controller = controller(configuredDigest);
        assertEquals(5000, ((APIResult<?>) controller.deletePendpool(suppliedToken, "00")).getCode());
        assertEquals(5000, ((APIResult<?>) controller.updatePtNonce(suppliedToken, "unused", 7, 1)).getCode());
        verifyNoInteractions(pool);
    }

    @Test
    void rejectsMissingAndMalformedConfigurationWithoutPoolAccess() {
        for (String configured : new String[]{null, "", " ", "invalid", "0".repeat(63), "g".repeat(64)}) {
            assertDenied(configured, token);
        }
    }

    @Test
    void rejectsMissingMalformedAndWrongTokensWithoutPoolAccess() {
        for (String supplied : new String[]{null, "", " ", "not-hex", "a", "00"}) {
            assertDenied(digest, supplied);
        }
    }

    @Test
    void rejectsEmptyTokenEvenIfEmptyDigestConfigured() {
        assertDenied(Hex.encodeHexString(SHA3Utility.sha3256(new byte[0])), "");
    }

    @Test
    void deletesOnlyRequestedEntryWithValidConfiguredToken() {
        PoolController controller = controller(digest);
        Transaction transaction = new Transaction();
        transaction.from = new byte[32];
        transaction.nonce = 7;
        TransPool entry = new TransPool();
        entry.setTransaction(transaction);
        when(pool.getPoolTranHash(any(byte[].class))).thenReturn(entry);
        assertEquals(2000, ((APIResult<?>) controller.deletePendpool(token, "00")).getCode());
        verify(pool).getPoolTranHash(new byte[]{0});
        String from = Hex.encodeHexString(RipemdUtility.ripemd160(SHA3Utility.keccak256(transaction.from)));
        verify(pool).removeOne(from, 7);
        verifyNoMoreInteractions(pool);
    }

    @Test
    void updatesNonceWithValidTokenAndCaseInsensitiveVerifier() {
        PoolController controller = controller(" " + digest.toUpperCase(Locale.ROOT) + " ");
        byte[] account = new byte[20];
        try (MockedStatic<KeystoreAction> keystore = mockStatic(KeystoreAction.class)) {
            keystore.when(() -> KeystoreAction.addressToPubkeyHash("test-address")).thenReturn(account);
            assertEquals(2000, ((APIResult<?>) controller.updatePtNonce(token, "test-address", 9, 1)).getCode());
        }
        ArgumentCaptor<PendingNonce> nonce = ArgumentCaptor.forClass(PendingNonce.class);
        verify(pool).updatePtNone(eq(Hex.encodeHexString(account)), nonce.capture());
        assertEquals(9, nonce.getValue().getNonce());
        assertEquals(1, nonce.getValue().getState());
        verifyNoMoreInteractions(pool);
    }
}
