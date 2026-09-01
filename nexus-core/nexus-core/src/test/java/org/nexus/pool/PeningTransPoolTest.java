package org.nexus.pool;

import org.junit.jupiter.api.Test;
import org.nexus.core.account.Transaction;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PeningTransPool 纯内存逻辑单测（A 项覆盖率提升）。
 * 构造器 leveldb 未注入时走 catch 重建空 map（:49-52）——测试用默认构造安全。
 * 断言基于源码语义逐条核对（PeningTransPool.java:39-295）：
 * nonce 去重、TreeMap 有序、removeOne 空桶清除、状态过滤、PendingNonce 流转。
 */
class PeningTransPoolTest {

    private static Transaction txOf(long nonce, int type, byte[] from) {
        Transaction t = new Transaction();
        t.version = 1;
        t.type = type;
        t.nonce = nonce;
        t.from = from;
        t.to = new byte[20];
        t.signature = new byte[64];
        return t;
    }

    private static TransPool poolOf(Transaction tx, int state) {
        TransPool p = new TransPool();
        p.setTransaction(tx);
        p.setState(state);
        return p;
    }

    // from 公钥哈希的 hex（与 add 内部 :59 相同算法）
    private static String fromHash(byte[] from) {
        return org.apache.commons.codec.binary.Hex.encodeHexString(
                org.nexus.keystore.crypto.RipemdUtility.ripemd160(
                        org.nexus.keystore.crypto.SHA3Utility.keccak256(from)));
    }

    // ===== add + nonce 去重（:55） =====

    @Test
    void addDeduplicatesByNonce() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        from[0] = 1;
        Transaction a = txOf(1, 1, from);
        Transaction aDup = txOf(1, 1, from); // 同 nonce——:62 "Pending Can't cover"
        Transaction b = txOf(2, 1, from);
        pool.add(Arrays.asList(poolOf(a, 0), poolOf(aDup, 0), poolOf(b, 0)));
        List<TransPool> all = pool.getAllFrom(fromHash(from));
        assertEquals(2, all.size(), "同 from 同 nonce 只入池一次");
    }

    @Test
    void addSeparatesSenders() {
        PeningTransPool pool = new PeningTransPool();
        byte[] alice = new byte[32];
        alice[0] = 1;
        byte[] bob = new byte[32];
        bob[0] = 2;
        pool.add(Arrays.asList(poolOf(txOf(1, 1, alice), 0), poolOf(txOf(1, 1, bob), 0)));
        assertEquals(1, pool.getAllFrom(fromHash(alice)).size());
        assertEquals(1, pool.getAllFrom(fromHash(bob)).size());
        assertEquals(2, pool.size(), "size = 全体交易数");
    }

    // ===== getAllFrom 按 nonce 有序（TreeMap :61/68） =====

    @Test
    void getAllFromReturnsNonceAscending() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        pool.add(Arrays.asList(
                poolOf(txOf(5, 1, from), 0),
                poolOf(txOf(1, 1, from), 0),
                poolOf(txOf(3, 1, from), 0)));
        List<TransPool> list = pool.getAllFrom(fromHash(from));
        assertEquals(Arrays.asList(1L, 3L, 5L),
                list.stream().map(p -> p.getTransaction().nonce).toList(),
                "TreeMap 保证 nonce 升序");
    }

    // ===== 状态过滤查询（:110/168/182） =====

    @Test
    void stateFilteringQueries() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        String key = fromHash(from);
        pool.add(Arrays.asList(
                poolOf(txOf(1, 1, from), 0), // pending
                poolOf(txOf(2, 1, from), 1), // 打包中
                poolOf(txOf(3, 1, from), 2))); // 进 db
        // getAllFromState：排除 state==2
        assertEquals(2, pool.getAllFromState(key).size());
        // getAllstate（Unpacksize）：排除 2
        assertEquals(2, pool.Unpacksize());
        // getAllnostate：仅 state==0
        assertEquals(1, pool.getAllnostate().size());
        assertEquals(1, pool.getAllnostate().get(0).getTransaction().nonce);
    }

    // ===== getPoolTranHash（:123） =====

    @Test
    void getPoolTranHashFindsByExactHash() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        Transaction target = txOf(1, 1, from);
        Transaction other = txOf(2, 1, from);
        pool.add(Arrays.asList(poolOf(target, 0), poolOf(other, 0)));
        TransPool found = pool.getPoolTranHash(target.getHash());
        assertNotNull(found);
        assertEquals(1, found.getTransaction().nonce);
        assertNull(pool.getPoolTranHash(new byte[32]));
    }

    // ===== getAllPubhash（:135）——state==0 的 from 首条 =====

    @Test
    void getAllPubhashListsPendingSendersOnce() throws Exception {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        pool.add(Arrays.asList(
                poolOf(txOf(1, 1, from), 0),
                poolOf(txOf(2, 1, from), 0)));
        List<byte[]> hashes = pool.getAllPubhash();
        assertEquals(1, hashes.size(), "同一 from 多笔 pending 只列一次（:145 break）");
        assertEquals(fromHash(from), org.apache.commons.codec.binary.Hex.encodeHexString(hashes.get(0)));
    }

    // ===== getAllMap（:152）——只保留 state==0 =====

    @Test
    void getAllMapKeepsOnlyPendingStateZero() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        pool.add(Arrays.asList(
                poolOf(txOf(1, 1, from), 0),
                poolOf(txOf(2, 1, from), 2)));
        var map = pool.getAllMap();
        String key = fromHash(from);
        assertTrue(map.containsKey(key));
        assertEquals(1, map.get(key).size(), "state==2 的不进 map");
        assertTrue(map.get(key).containsKey(1L));
    }

    // ===== removeOne（:196）——删条目、空桶清除、nonce 重置 =====

    @Test
    void removeOneDeletesEntryAndClearsEmptyBucket() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        String key = fromHash(from);
        pool.add(Arrays.asList(poolOf(txOf(1, 1, from), 0)));
        assertEquals(1, pool.getAllFrom(key).size());

        pool.removeOne(key, 1);
        assertTrue(pool.getAllFrom(key).isEmpty(), "删除后桶空");
        assertEquals(0, pool.size(), "空桶应从 ptpool 整体清除");
        // 未命中 nonce 不影响他人
        pool.removeOne(key, 99);
        assertEquals(0, pool.size());
    }

    @Test
    void removeOneKeepsRemainingEntriesInBucket() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        String key = fromHash(from);
        pool.add(Arrays.asList(
                poolOf(txOf(1, 1, from), 0),
                poolOf(txOf(2, 1, from), 0)));
        pool.removeOne(key, 1);
        assertEquals(1, pool.getAllFrom(key).size());
        assertEquals(2, pool.getAllFrom(key).get(0).getTransaction().nonce);
    }

    @Test
    void removeMapIteratesAllEntries() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        String key = fromHash(from);
        pool.add(Arrays.asList(poolOf(txOf(1, 1, from), 0)));
        IdentityHashMap<String, Long> rm = new IdentityHashMap<>();
        rm.put(key, 1L);
        pool.remove(rm);
        assertEquals(0, pool.size());
    }

    // ===== PendingNonce 流转（:270/278/226/236/76/286） =====

    @Test
    void updateNoncePerType() {
        PeningTransPool pool = new PeningTransPool();
        // add(type=1 转账) → state=2
        byte[] from = new byte[32];
        pool.add(Arrays.asList(poolOf(txOf(7, 1, from), 0)));
        String key = fromHash(from);
        assertEquals(7, pool.findptnonce(key).getNonce());
        assertEquals(2, pool.findptnonce(key).getState(), "转账类 nonce 直接置 2");

        // add(非转账 type=9) → state=0
        byte[] from2 = new byte[32];
        from2[0] = 9;
        pool.add(Arrays.asList(poolOf(txOf(3, 9, from2), 0)));
        String key2 = fromHash(from2);
        assertEquals(3, pool.findptnonce(key2).getNonce());
        assertEquals(0, pool.findptnonce(key2).getState());
    }

    @Test
    void findptnonceDefaultsForUnknownKey() {
        PeningTransPool pool = new PeningTransPool();
        PendingNonce pn = pool.findptnonce("no-such-key");
        assertEquals(0, pn.getNonce());
        assertEquals(2, pn.getState());
    }

    @Test
    void nonceupdateMarksDbStateOnlyOnMatchingNonce() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        pool.add(Arrays.asList(poolOf(txOf(5, 9, from), 0)));
        String key = fromHash(from);
        // nonce 不匹配：不变
        pool.nonceupdate(key, 99);
        assertEquals(0, pool.findptnonce(key).getState());
        // nonce 匹配：置 2
        pool.nonceupdate(key, 5);
        assertEquals(2, pool.findptnonce(key).getState());
    }

    @Test
    void updatePtNonceBulkMarksStateTwo() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        pool.add(Arrays.asList(poolOf(txOf(1, 9, from), 0)));
        String key = fromHash(from);
        pool.updatePtNonce(Arrays.asList(key, "unknown-key"));
        assertEquals(2, pool.findptnonce(key).getState());
    }

    @Test
    void updatePtNoneOnlyOverwritesExistingKey() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        pool.add(Arrays.asList(poolOf(txOf(4, 9, from), 0)));
        String key = fromHash(from);
        pool.updatePtNone(key, new PendingNonce(4, 2));
        assertEquals(2, pool.findptnonce(key).getState());
        // 未知键不创建
        pool.updatePtNone("ghost", new PendingNonce(1, 2));
        assertEquals(0, pool.findptnonce("ghost").getNonce());
    }

    @Test
    void getPtnonceFiltersStateZero() {
        PeningTransPool pool = new PeningTransPool();
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        b[0] = 2;
        pool.add(Arrays.asList(
                poolOf(txOf(1, 9, a), 0),  // state=0
                poolOf(txOf(1, 1, b), 0))); // 转账 → state=2
        var map = pool.getPtnonce();
        assertEquals(1, map.size(), "只有 state==0 的 pending nonce 进表");
        assertTrue(map.containsKey(fromHash(a)));
    }

    // ===== updatePool（:248）——打包状态推进 + 进 db 联动 =====

    @Test
    void updatePoolMarksTransactionsAndClearsPendingNonceOnDb() {
        PeningTransPool pool = new PeningTransPool();
        byte[] from = new byte[32];
        pool.add(Arrays.asList(
                poolOf(txOf(1, 9, from), 0),  // 存证类（非 1/2/13）
                poolOf(txOf(2, 1, from), 0))); // 转账类
        String key = fromHash(from);

        // type=1 打包中
        pool.updatePool(
                Arrays.asList(txOf(1, 9, from), txOf(2, 1, from)),
                1, 100L);
        assertEquals(1, pool.getAllFrom(key).get(0).getState());
        assertEquals(100L, pool.getAllFrom(key).get(0).getHeight());

        // type=2 进 db：两笔都 setState(2)（:256 无条件）；:260 的 type 排除
        // 只影响 ptnonce 的 nonceupdate，不影响池内条目状态
        pool.updatePool(
                Arrays.asList(txOf(1, 9, from), txOf(2, 1, from)),
                2, 200L);
        assertEquals(0, pool.getAllFromState(key).size(), "state!=2 查询排除全部进 db 条目");
        assertEquals(0, pool.Unpacksize());
        // state==0 查询同样为空
        assertEquals(0, pool.getAllnostate().size());
    }

    @Test
    void updatePoolIgnoresUnknownFromAndNonce() {
        PeningTransPool pool = new PeningTransPool();
        byte[] stranger = new byte[32];
        stranger[0] = 7;
        // 未知 from / 未入池 nonce：不抛异常、无副作用
        assertDoesNotThrow(() ->
                pool.updatePool(Arrays.asList(txOf(1, 1, stranger)), 2, 1L));
        assertEquals(0, pool.size());
    }
}
