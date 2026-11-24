package org.nexus.core.validate;

import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.StateCommitment;
import org.nexus.core.account.Account;
import org.nexus.core.incubator.Incubator;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MerkleRuleStateCommitmentV2Test {

    private static final long ACTIVATION_HEIGHT = 100L;

    private final MerkleRule rule = new MerkleRule("validator", new StateCommitment(ACTIVATION_HEIGHT));

    @Test
    void historicalRootKeepsLegacyBalanceSemantics() {
        List<Account> original = List.of(account(1, 10, 100));
        Block block = block(ACTIVATION_HEIGHT - 1, Block.calculateMerkleState(original));
        List<Account> tampered = List.of(account(1, 10, Long.MAX_VALUE));

        Result result = rule.verifyStateRoots(block, tampered, List.of());

        assertTrue(result.isSuccess(), result.getMessage());
    }

    @Test
    void activatedRootRejectsBalanceTamperingWithSameAccountId() {
        List<Account> original = List.of(account(1, 10, 100));
        Block block = block(ACTIVATION_HEIGHT, rule.calculateStateRoot(original, ACTIVATION_HEIGHT));
        List<Account> tampered = List.of(account(1, 10, 101));
        assertArrayEquals(original.get(0).getId(), tampered.get(0).getId());

        Result result = rule.verifyStateRoots(block, tampered, List.of());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("merkle state validate fail"), result.getMessage());
    }

    @Test
    void activatedRootRejectsNonceTamperingWithSameAccountId() {
        Account original = account(1, 10, 100);
        Account tampered = new Account(ACTIVATION_HEIGHT, original.getPubkeyHash(), 11, 100, 0, 0, 0);
        Block block = block(ACTIVATION_HEIGHT, rule.calculateStateRoot(List.of(original), ACTIVATION_HEIGHT));
        assertArrayEquals(original.getId(), tampered.getId());

        Result result = rule.verifyStateRoots(block, List.of(tampered), List.of());

        assertFalse(result.isSuccess());
    }

    @Test
    void activatedRootAcceptsCanonicalAccountSetInAnyOrder() {
        List<Account> accounts = List.of(account(1, 1, 10), account(2, 2, 20));
        Block block = block(ACTIVATION_HEIGHT, rule.calculateStateRoot(accounts, ACTIVATION_HEIGHT));

        Result result = rule.verifyStateRoots(block,
                List.of(accounts.get(1), accounts.get(0)), List.of());

        assertTrue(result.isSuccess(), result.getMessage());
    }

    private static Block block(long height, byte[] stateRoot) {
        Block block = new Block();
        block.nHeight = height;
        block.hashMerkleState = stateRoot;
        block.hashMerkleIncubate = Block.calculateMerkleIncubate(List.of());
        return block;
    }

    private static Account account(int seed, long nonce, long balance) {
        byte[] hash = new byte[20];
        Arrays.fill(hash, (byte) seed);
        return new Account(ACTIVATION_HEIGHT, hash, nonce, balance, 0, 0, 0);
    }
}
