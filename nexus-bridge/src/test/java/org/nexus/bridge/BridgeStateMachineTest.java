package org.nexus.bridge;

import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.model.BridgeTransaction.BridgeTxStatus;
import org.nexus.bridge.repository.BridgeTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BridgeStateMachineTest {

    @Mock
    private BridgeTransactionRepository txRepository;

    private BridgeConfig config;
    private BridgeServiceImpl bridgeService;

    @BeforeEach
    void setUp() {
        config = new BridgeConfig();
        config.setSignatureThreshold(2);
        config.setMaxAmountPerTx(50_000_000_000L);
        config.setDailyLimit(100_000_000_000L);
        config.setLargeAmountThreshold(10_000_000_000L);
        config.setTimelockPeriodSeconds(3600);
        config.setValidatorPublicKeys(Arrays.asList("v1", "v2", "v3"));
        bridgeService = new BridgeServiceImpl(config, txRepository);
    }

    @Test
    @DisplayName("LOCK small amount -> immediate LOCKED status")
    void lock_smallAmount_immediateLocked() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LockRequest req = new LockRequest("nexus", "ethereum", 1_000_000L, "user1", "0xTarget", "0xTxHash1");
        BridgeTransaction tx = bridgeService.lock(req);
        assertEquals(BridgeTxStatus.LOCKED, tx.getStatus());
        assertNull(tx.getTimelockExpiresAt());
    }

    @Test
    @DisplayName("LOCK large amount -> LOCK_PENDING with timelock")
    void lock_largeAmount_pendingWithTimelock() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LockRequest req = new LockRequest("nexus", "ethereum", 20_000_000_000L, "user1", "0xTarget", "0xTxHash2");
        BridgeTransaction tx = bridgeService.lock(req);
        assertEquals(BridgeTxStatus.LOCK_PENDING, tx.getStatus());
        assertNotNull(tx.getTimelockExpiresAt());
    }

    @Test
    @DisplayName("LOCK exceeding max amount -> BridgeException")
    void lock_exceedsMax_throws() {
        LockRequest req = new LockRequest("nexus", "ethereum", 999_000_000_000L, "user1", "0xTarget", "0xTxHash3");
        assertThrows(BridgeException.class, () -> bridgeService.lock(req));
    }

    @Test
    @DisplayName("MINT with insufficient signatures -> BridgeException")
    void mint_insufficientSigs_throws() {
        BridgeTransaction lockTx = new BridgeTransaction();
        lockTx.setTxId("lock-1");
        lockTx.setStatus(BridgeTxStatus.LOCKED);
        when(txRepository.findById("lock-1")).thenReturn(Optional.of(lockTx));

        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1"); // only 1 sig, threshold is 2
        MintRequest req = new MintRequest("lock-1", sigs, "v1", "ethereum");
        assertThrows(BridgeException.class, () -> bridgeService.mint(req));
    }

    @Test
    @DisplayName("MINT with sufficient signatures -> MINTED")
    void mint_sufficientSigs_minted() {
        BridgeTransaction lockTx = new BridgeTransaction();
        lockTx.setTxId("lock-2");
        lockTx.setStatus(BridgeTxStatus.LOCKED);
        when(txRepository.findById("lock-2")).thenReturn(Optional.of(lockTx));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        MintRequest req = new MintRequest("lock-2", sigs, "v1", "ethereum");
        BridgeTransaction result = bridgeService.mint(req);
        assertEquals(BridgeTxStatus.MINTED, result.getStatus());
    }

    @Test
    @DisplayName("PAUSE -> only UNLOCK allowed, LOCK rejected")
    void pause_blocksLock() {
        bridgeService.pause("v1");
        LockRequest req = new LockRequest("nexus", "ethereum", 1_000_000L, "user1", "0xTarget", "0xTxHash4");
        assertThrows(BridgeException.class, () -> bridgeService.lock(req));
    }

    @Test
    @DisplayName("Daily limit exceeded -> BridgeException")
    void lock_dailyLimitExceeded_throws() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Use up most of the daily limit (each under 50B max per tx)
        LockRequest first = new LockRequest("nexus", "ethereum", 40_000_000_000L, "u", "t", "h1");
        bridgeService.lock(first);
        LockRequest second = new LockRequest("nexus", "ethereum", 40_000_000_000L, "u", "t", "h2");
        bridgeService.lock(second);
        // 40B + 40B = 80B used. This 30B would make 110B > 100B daily limit
        LockRequest over = new LockRequest("nexus", "ethereum", 30_000_000_000L, "u", "t", "h3");
        assertThrows(BridgeException.class, () -> bridgeService.lock(over));
    }
}