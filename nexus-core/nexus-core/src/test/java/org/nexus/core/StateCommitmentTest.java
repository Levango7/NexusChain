package org.nexus.core;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.core.account.Account;
import org.nexus.keystore.crypto.SHA3Utility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StateCommitmentTest {

    private static final long ACTIVATION_HEIGHT = 100L;

    @Test
    void legacyRootIsByteIdenticalBeforeActivation() {
        StateCommitment commitment = new StateCommitment(ACTIVATION_HEIGHT);
        List<Account> accounts = accounts();

        assertArrayEquals(Block.calculateMerkleState(accounts),
                commitment.calculateMerkleState(accounts, ACTIVATION_HEIGHT - 1));
        assertFalse(commitment.isV2Active(ACTIVATION_HEIGHT - 1));
        assertTrue(commitment.isV2Active(ACTIVATION_HEIGHT));
        assertNotEquals(Hex.encodeHexString(Block.calculateMerkleState(accounts)),
                Hex.encodeHexString(commitment.calculateMerkleState(accounts, ACTIVATION_HEIGHT)));
    }

    @Test
    void disabledCommitmentAlwaysUsesLegacyAlgorithm() {
        StateCommitment commitment = StateCommitment.disabled();
        List<Account> accounts = accounts();

        assertArrayEquals(Block.calculateMerkleState(accounts),
                commitment.calculateMerkleState(accounts, Long.MAX_VALUE));
        assertFalse(commitment.isV2Active(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new StateCommitment(-1L));
    }

    @Test
    void v2CommitsEveryConsensusField() {
        Account base = account(7, 11, 12, 13, 14, 15, 16);
        byte[] baseRoot = StateCommitment.calculateV2(List.of(base));

        assertNotEquals(Hex.encodeHexString(baseRoot), Hex.encodeHexString(
                StateCommitment.calculateV2(List.of(account(7, 11, 99, 13, 14, 15, 16)))));
        assertNotEquals(Hex.encodeHexString(baseRoot), Hex.encodeHexString(
                StateCommitment.calculateV2(List.of(account(7, 11, 12, 99, 14, 15, 16)))));
        assertNotEquals(Hex.encodeHexString(baseRoot), Hex.encodeHexString(
                StateCommitment.calculateV2(List.of(account(7, 11, 12, 13, 99, 15, 16)))));
        assertNotEquals(Hex.encodeHexString(baseRoot), Hex.encodeHexString(
                StateCommitment.calculateV2(List.of(account(7, 11, 12, 13, 14, 99, 16)))));
        assertNotEquals(Hex.encodeHexString(baseRoot), Hex.encodeHexString(
                StateCommitment.calculateV2(List.of(account(7, 11, 12, 13, 14, 15, 99)))));
        assertNotEquals(Hex.encodeHexString(baseRoot), Hex.encodeHexString(
                StateCommitment.calculateV2(List.of(account(8, 11, 12, 13, 14, 15, 16)))));
    }

    @Test
    void v2RootIsIndependentOfInputOrder() {
        List<Account> forward = accounts();
        List<Account> reverse = new ArrayList<>(forward);
        java.util.Collections.reverse(reverse);

        assertArrayEquals(StateCommitment.calculateV2(forward), StateCommitment.calculateV2(reverse));
    }

    @Test
    void v2RejectsDuplicateAccountsInsteadOfDoubleCounting() {
        Account account = accounts().get(0);

        assertThrows(IllegalArgumentException.class,
                () -> StateCommitment.calculateV2(List.of(account, account.copy())));
    }

    @Test
    void v2EncodingIsVersionedAndUnambiguous() {
        Account account = new Account(0x01020304L, new byte[]{(byte) 0xaa, (byte) 0xbb},
                1, 2, 3, 4, 5);

        assertEquals("02" + "0002" + "aabb" + "01020304"
                        + "0000000000000001" + "0000000000000002" + "0000000000000003"
                        + "0000000000000004" + "0000000000000005",
                Hex.encodeHexString(StateCommitment.encodeAccountV2(account)));
        assertEquals("e32d8988040cd4a67c8a69d34630ac18f2444cce5c87e0176c73ed2c32962af3",
                Hex.encodeHexString(StateCommitment.calculateV2(List.of(accounts().get(0)))),
                "complete V2 root vector for seed=1,height=1,nonce=2,balance=3,cost=4,mortgage=5,vote=6");
    }

    @Test
    void v2EmptyRootIsVersionedAndNotLegacyZeroRoot() {
        assertArrayEquals(SHA3Utility.keccak256(new byte[]{StateCommitment.VERSION}),
                StateCommitment.calculateV2(List.of()));
        assertFalse(Arrays.equals(new byte[Block.HASH_SIZE], StateCommitment.calculateV2(List.of())));
    }

    @Test
    void v2RejectsInvalidInputsInsteadOfReturningZeroRoot() {
        assertThrows(IllegalArgumentException.class, () -> StateCommitment.calculateV2(null));
        assertThrows(IllegalArgumentException.class,
                () -> StateCommitment.calculateV2(java.util.Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class,
                () -> StateCommitment.calculateV2(List.of(new Account(-1, new byte[20], 0, 0, 0, 0, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> StateCommitment.calculateV2(List.of(new Account(0x1_0000_0000L, new byte[20], 0, 0, 0, 0, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> StateCommitment.calculateV2(List.of(new Account(1, null, 0, 0, 0, 0, 0))));
    }

    private static List<Account> accounts() {
        return List.of(
                account(1, 1, 2, 3, 4, 5, 6),
                account(2, 2, 3, 4, 5, 6, 7),
                account(3, 3, 4, 5, 6, 7, 8));
    }

    private static Account account(int seed, long height, long nonce, long balance,
                                   long incubateCost, long mortgage, long vote) {
        byte[] hash = new byte[20];
        Arrays.fill(hash, (byte) seed);
        return new Account(height, hash, nonce, balance, incubateCost, mortgage, vote);
    }
}
