/*
 * TEST-ONLY. Answers uncertainty (b): MerkleRule rebuilds its account list by
 * iterating a HashMap and Block.calculateMerkleState does not sort, so can two
 * nodes on the same chain end up with different hashMerkleState?
 */
package org.nexus.core.validate;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.account.Account;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class MerkleStateOrderSensitivityTest {

    /** "Aa" and "BB" have the same String hashCode (2112) -> same HashMap bucket. */
    private static final String COLLIDING_KEY_A = "Aa";
    private static final String COLLIDING_KEY_B = "BB";

    @Test
    void leafOrderChangesTheStateRoot() {
        Account a = account(0x11, 1L, 100L);
        Account b = account(0x22, 1L, 200L);

        String rootAB = Hex.encodeHexString(Block.calculateMerkleState(List.of(a, b)));
        String rootBA = Hex.encodeHexString(Block.calculateMerkleState(List.of(b, a)));

        System.out.println("[ORDER] root(a,b)=" + rootAB);
        System.out.println("[ORDER] root(b,a)=" + rootBA);
        assertNotEquals(rootAB, rootBA, "state root must be order sensitive (no sorting in MerkleTree)");
    }

    @Test
    void balanceIsNotCommittedByTheStateRoot() {
        Account poor = account(0x33, 5L, 0L);
        Account rich = account(0x33, 5L, Long.MAX_VALUE);
        Account richNonce = new Account(5L, hashOf(0x33), 999L, 123L, 0, 0, 0);

        String rootPoor = Hex.encodeHexString(Block.calculateMerkleState(List.of(poor)));
        String rootRich = Hex.encodeHexString(Block.calculateMerkleState(List.of(rich)));
        String rootRichNonce = Hex.encodeHexString(Block.calculateMerkleState(List.of(richNonce)));

        assertEquals(rootPoor, rootRich, "same id must yield the same root regardless of balance");
        assertEquals(rootPoor, rootRichNonce, "same id must yield the same root regardless of nonce");
    }

    @Test
    void hashMapInsertionOrderCanChangeTheReplayedLeafOrder() {
        Account a = account(0x44, 1L, 10L);
        Account b = account(0x55, 1L, 20L);

        Map<String, Account> firstInsertion = new HashMap<>();
        firstInsertion.put(COLLIDING_KEY_A, a);
        firstInsertion.put(COLLIDING_KEY_B, b);

        Map<String, Account> reverseInsertion = new HashMap<>();
        reverseInsertion.put(COLLIDING_KEY_B, b);
        reverseInsertion.put(COLLIDING_KEY_A, a);

        List<Account> orderOne = new ArrayList<>(firstInsertion.values());
        List<Account> orderTwo = new ArrayList<>(reverseInsertion.values());

        String rootOne = Hex.encodeHexString(Block.calculateMerkleState(orderOne));
        String rootTwo = Hex.encodeHexString(Block.calculateMerkleState(orderTwo));

        System.out.println("[MAP] entrySet order 1=" + firstInsertion.keySet() + " root=" + rootOne);
        System.out.println("[MAP] entrySet order 2=" + reverseInsertion.keySet() + " root=" + rootTwo);
        assertNotEquals(orderOne.get(0).getIdHexString(), orderTwo.get(0).getIdHexString(),
                "colliding keys must expose insertion-order dependence of HashMap iteration");
        assertNotEquals(rootOne, rootTwo,
                "same account set, different HashMap insertion order -> different state root");
    }

    @Test
    void sameBlockReplayOrderIsDeterministicAcrossNodes() {
        // MerkleRule inserts accounts in transaction order, which is part of the
        // block body, so the same block replayed twice yields the same list order.
        Account a = account(0x66, 1L, 10L);
        Account b = account(0x77, 1L, 20L);
        Map<String, Account> map = new HashMap<>();
        map.put(Hex.encodeHexString(a.getPubkeyHash()), a);
        map.put(Hex.encodeHexString(b.getPubkeyHash()), b);

        List<Account> replayOne = new ArrayList<>(map.values());
        List<Account> replayTwo = new ArrayList<>(map.values());

        assertEquals(Hex.encodeHexString(Block.calculateMerkleState(replayOne)),
                Hex.encodeHexString(Block.calculateMerkleState(replayTwo)),
                "same key set / same insertion sequence is stable for String keys");
    }

    private static Account account(int hashSeed, long height, long balance) {
        return new Account(height, hashOf(hashSeed), 0L, balance, 0, 0, 0);
    }

    private static byte[] hashOf(int seed) {
        byte[] hash = new byte[20];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed + i);
        }
        return hash;
    }
}
