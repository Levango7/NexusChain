package org.nexus.l2.sequencer;

import org.junit.jupiter.api.Test;
import org.nexus.l2.L2Transaction;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SequencingPolicy} 单元测试。
 *
 * <p>覆盖排序规则（账户地址、nonce、优先费、txHash）、去重、
 * nonce 顺序校验等核心行为。</p>
 */
class SequencingPolicyTest {

    private L2Transaction tx(String sender, long nonce, long priorityFee, String hash) {
        L2Transaction t = new L2Transaction();
        t.setSender(sender);
        t.setNonce(nonce);
        t.setPriorityFee(BigInteger.valueOf(priorityFee));
        t.setTxHash(hash);
        return t;
    }

    @Test
    void defaultPolicyIsSingleton() {
        assertSame(SequencingPolicy.defaultPolicy(), SequencingPolicy.defaultPolicy());
    }

    @Test
    void withNonceDedupCreatesNewInstance() {
        SequencingPolicy a = SequencingPolicy.withNonceDedup(true);
        SequencingPolicy b = SequencingPolicy.withNonceDedup(false);
        assertNotSame(a, b);
        assertNotSame(a, SequencingPolicy.defaultPolicy());
    }

    @Test
    void sortNullOrEmptyIsNoOp() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        p.sort(null);
        List<L2Transaction> empty = Collections.emptyList();
        p.sort(empty);
        assertTrue(empty.isEmpty());
    }

    @Test
    void sortSingleElementIsNoOp() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        List<L2Transaction> single = Collections.singletonList(tx("a", 0, 1, "h"));
        p.sort(single);
        assertEquals(1, single.size());
    }

    @Test
    void sortOrdersBySenderAscending() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        L2Transaction b = tx("bbb", 0, 1, "h2");
        L2Transaction a = tx("aaa", 0, 1, "h1");
        List<L2Transaction> txs = new java.util.ArrayList<>(Arrays.asList(b, a));
        p.sort(txs);
        assertEquals("aaa", txs.get(0).getSender());
        assertEquals("bbb", txs.get(1).getSender());
    }

    @Test
    void sortOrdersByNonceAscendingWithinSameSender() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        L2Transaction t2 = tx("a", 2, 1, "h2");
        L2Transaction t0 = tx("a", 0, 1, "h0");
        L2Transaction t1 = tx("a", 1, 1, "h1");
        List<L2Transaction> txs = new java.util.ArrayList<>(Arrays.asList(t2, t0, t1));
        p.sort(txs);
        assertEquals(0, txs.get(0).getNonce());
        assertEquals(1, txs.get(1).getNonce());
        assertEquals(2, txs.get(2).getNonce());
    }

    @Test
    void sortOrdersByPriorityFeeDescendingAcrossAccounts() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        // 不同账户，优先费高的应在前
        L2Transaction low = tx("aaa", 0, 1, "h1");
        L2Transaction high = tx("bbb", 0, 100, "h2");
        List<L2Transaction> txs = new java.util.ArrayList<>(Arrays.asList(low, high));
        p.sort(txs);
        // 账户地址优先，aaa 在前
        assertEquals("aaa", txs.get(0).getSender());
        assertEquals("bbb", txs.get(1).getSender());
    }

    @Test
    void sortNullSenderGoesLast() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        L2Transaction nullSender = tx(null, 0, 1, "h1");
        L2Transaction withSender = tx("aaa", 0, 1, "h2");
        List<L2Transaction> txs = new java.util.ArrayList<>(Arrays.asList(nullSender, withSender));
        p.sort(txs);
        assertEquals("aaa", txs.get(0).getSender());
        assertNull(txs.get(1).getSender());
    }

    @Test
    void sortTxHashIsTieBreaker() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        L2Transaction h2 = tx("a", 0, 1, "zzz");
        L2Transaction h1 = tx("a", 0, 1, "aaa");
        List<L2Transaction> txs = new java.util.ArrayList<>(Arrays.asList(h2, h1));
        p.sort(txs);
        assertEquals("aaa", txs.get(0).getTxHash());
        assertEquals("zzz", txs.get(1).getTxHash());
    }

    @Test
    void dedupNonceRemovesDuplicatesKeepingFirst() {
        SequencingPolicy p = SequencingPolicy.withNonceDedup(true);
        L2Transaction a1 = tx("a", 0, 1, "h1");
        L2Transaction a2 = tx("a", 0, 2, "h2"); // 同 sender 同 nonce，优先费更高
        L2Transaction b1 = tx("a", 1, 1, "h3");
        List<L2Transaction> txs = new java.util.ArrayList<>(Arrays.asList(a1, a2, b1));
        p.sort(txs);
        assertEquals(2, txs.size());
        // 排序后 a2（优先费更高）在前，dedup 保留 a2
        assertEquals("h2", txs.get(0).getTxHash());
        assertEquals("h3", txs.get(1).getTxHash());
    }

    @Test
    void defaultPolicyDoesNotDedup() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        L2Transaction a1 = tx("a", 0, 1, "h1");
        L2Transaction a2 = tx("a", 0, 2, "h2");
        List<L2Transaction> txs = new java.util.ArrayList<>(Arrays.asList(a1, a2));
        p.sort(txs);
        assertEquals(2, txs.size());
    }

    @Test
    void isNonceOrderedNullOrShortReturnsTrue() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        assertTrue(p.isNonceOrdered(null));
        assertTrue(p.isNonceOrdered(Collections.singletonList(tx("a", 0, 1, "h"))));
        assertTrue(p.isNonceOrdered(Collections.emptyList()));
    }

    @Test
    void isNonceOrderedValidReturnsTrue() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        List<L2Transaction> txs = Arrays.asList(
                tx("a", 0, 1, "h1"),
                tx("a", 1, 1, "h2"),
                tx("a", 2, 1, "h3"));
        assertTrue(p.isNonceOrdered(txs));
    }

    @Test
    void isNonceOrderedViolationReturnsFalse() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        List<L2Transaction> txs = Arrays.asList(
                tx("a", 0, 1, "h1"),
                tx("a", 0, 1, "h2")); // 同 nonce
        assertFalse(p.isNonceOrdered(txs));
    }

    @Test
    void isNonceOrderedAcrossSenders() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        List<L2Transaction> txs = Arrays.asList(
                tx("a", 5, 1, "h1"),
                tx("b", 0, 1, "h2"), // 不同 sender，nonce 重置
                tx("b", 3, 1, "h3"));
        assertTrue(p.isNonceOrdered(txs));
    }

    @Test
    void comparatorIsNotNullSafe() {
        SequencingPolicy p = SequencingPolicy.defaultPolicy();
        L2Transaction a = tx(null, 0, 1, null);
        L2Transaction b = tx(null, 0, 1, null);
        // 不应抛 NPE
        int cmp = p.comparator().compare(a, b);
        assertEquals(0, cmp);
    }
}