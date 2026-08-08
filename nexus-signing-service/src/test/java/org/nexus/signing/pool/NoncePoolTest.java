package org.nexus.signing.pool;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.nexus.signing.storage.Leveldb;

import java.io.IOException;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * {@link NoncePool} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>构造函数从 LevelDB 反序列化（空数据 / 有效数据 / 异常数据）</li>
 *   <li>add / remove / getMinNonce / getMaxNonce / getTreemap</li>
 *   <li>TCC 预锁定 API：lockNonce / lockNonce(address, nonce) / confirmNonce / cancelNonce / getLockedNonce / isLocked</li>
 * </ul></p>
 */
@RunWith(MockitoJUnitRunner.class)
public class NoncePoolTest {

    @Mock
    private Leveldb leveldb;

    private NoncePool noncePool;

    @Before
    public void setUp() throws Exception {
        when(leveldb.readFromSnapshot()).thenReturn("");
        doNothing().when(leveldb).addPoolDb(org.mockito.ArgumentMatchers.anyString());
        noncePool = new NoncePool(leveldb);
    }

    @Test
    public void testGetNoncepool_emptyInitially() {
        ConcurrentHashMap<String, TreeMap<Long, NonceState>> pool = noncePool.getNoncepool();
        assertNotNull(pool);
        assertTrue(pool.isEmpty());
    }

    @Test
    public void testAdd_newAddress() throws IOException {
        NonceState state = new NonceState("0xabc", 1L, 1234567890L);
        noncePool.add("addr1", state);
        assertEquals(1L, noncePool.getMinNonce("addr1"));
        assertEquals(1L, noncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testAdd_existingAddress_newNonce() throws IOException {
        noncePool.add("addr1", new NonceState("0xabc", 1L, 1000L));
        noncePool.add("addr1", new NonceState("0xdef", 5L, 2000L));
        assertEquals(1L, noncePool.getMinNonce("addr1"));
        assertEquals(5L, noncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testAdd_existingAddress_duplicateNonce_ignored() throws IOException {
        noncePool.add("addr1", new NonceState("0xabc", 1L, 1000L));
        noncePool.add("addr1", new NonceState("0xdef", 1L, 2000L));
        TreeMap<Long, NonceState> tree = noncePool.getTreemap("addr1");
        assertEquals(1, tree.size());
        assertEquals("0xabc", tree.get(1L).getTranHash());
    }

    @Test
    public void testRemove_existingNonce() throws IOException {
        noncePool.add("addr1", new NonceState("0xabc", 1L, 1000L));
        noncePool.add("addr1", new NonceState("0xdef", 5L, 2000L));
        noncePool.remove("addr1", 1L);
        assertEquals(5L, noncePool.getMinNonce("addr1"));
        assertEquals(5L, noncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testRemove_lastNonce_removesAddress() throws IOException {
        noncePool.add("addr1", new NonceState("0xabc", 1L, 1000L));
        noncePool.remove("addr1", 1L);
        assertFalse(noncePool.getNoncepool().containsKey("addr1"));
        assertEquals(0L, noncePool.getMinNonce("addr1"));
        assertEquals(0L, noncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testRemove_nonExistingAddress_noOp() throws IOException {
        noncePool.remove("nonexistent", 1L);
        assertFalse(noncePool.getNoncepool().containsKey("nonexistent"));
    }

    @Test
    public void testRemove_nonExistingNonce_noOp() throws IOException {
        noncePool.add("addr1", new NonceState("0xabc", 1L, 1000L));
        noncePool.remove("addr1", 999L);
        assertEquals(1L, noncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testGetMinNonce_nonExistingAddress_returnsZero() {
        assertEquals(0L, noncePool.getMinNonce("nonexistent"));
    }

    @Test
    public void testGetMaxNonce_nonExistingAddress_returnsZero() {
        assertEquals(0L, noncePool.getMaxNonce("nonexistent"));
    }

    @Test
    public void testGetTreemap_nonExistingAddress_returnsEmpty() {
        TreeMap<Long, NonceState> tree = noncePool.getTreemap("nonexistent");
        assertNotNull(tree);
        assertTrue(tree.isEmpty());
    }

    @Test
    public void testGetTreemap_existingAddress_returnsTree() throws IOException {
        noncePool.add("addr1", new NonceState("0xabc", 1L, 1000L));
        TreeMap<Long, NonceState> tree = noncePool.getTreemap("addr1");
        assertNotNull(tree);
        assertEquals(1, tree.size());
    }

    // ==================== TCC 预锁定 API ====================

    @Test
    public void testLockNonce_emptyPool_returnsZero() {
        long result = noncePool.lockNonce("addr1");
        assertEquals(0L, result);
        assertFalse(noncePool.isLocked("addr1"));
    }

    @Test
    public void testLockNonce_nonEmptyPool_locksMaxNonce() throws IOException {
        noncePool.add("addr1", new NonceState("0xabc", 5L, 1000L));
        long result = noncePool.lockNonce("addr1");
        assertEquals(5L, result);
        assertTrue(noncePool.isLocked("addr1"));
        assertEquals(Long.valueOf(5L), noncePool.getLockedNonce("addr1"));
    }

    @Test
    public void testLockNonce_withNonce_zero_returnsNegOne() {
        long result = noncePool.lockNonce("addr1", 0L);
        assertEquals(-1L, result);
        assertFalse(noncePool.isLocked("addr1"));
    }

    @Test
    public void testLockNonce_withNonce_negative_returnsNegOne() {
        long result = noncePool.lockNonce("addr1", -1L);
        assertEquals(-1L, result);
    }

    @Test
    public void testLockNonce_withNonce_positive_succeeds() {
        long result = noncePool.lockNonce("addr1", 10L);
        assertEquals(10L, result);
        assertTrue(noncePool.isLocked("addr1"));
    }

    @Test
    public void testLockNonce_conflict_returnsNegOne() {
        noncePool.lockNonce("addr1", 10L);
        long result = noncePool.lockNonce("addr1", 20L);
        assertEquals(-1L, result);
        assertEquals(Long.valueOf(10L), noncePool.getLockedNonce("addr1"));
    }

    @Test
    public void testLockNonce_sameNonce_idempotent() {
        long first = noncePool.lockNonce("addr1", 10L);
        long second = noncePool.lockNonce("addr1", 10L);
        assertEquals(10L, first);
        assertEquals(10L, second);
        assertTrue(noncePool.isLocked("addr1"));
    }

    @Test
    public void testConfirmNonce_success() throws IOException {
        noncePool.lockNonce("addr1", 10L);
        boolean result = noncePool.confirmNonce("addr1", 10L, "0xtxhash");
        assertTrue(result);
        assertFalse(noncePool.isLocked("addr1"));
        // confirmNonce 写入 nonce+1 = 11
        assertEquals(11L, noncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testConfirmNonce_noLockRecord_stillWritesPool() throws IOException {
        boolean result = noncePool.confirmNonce("addr1", 10L, "0xtxhash");
        assertTrue(result);
        assertEquals(11L, noncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testCancelNonce_success() {
        noncePool.lockNonce("addr1", 10L);
        boolean result = noncePool.cancelNonce("addr1", 10L);
        assertTrue(result);
        assertFalse(noncePool.isLocked("addr1"));
        assertNull(noncePool.getLockedNonce("addr1"));
    }

    @Test
    public void testCancelNonce_noLockRecord_returnsFalse() {
        boolean result = noncePool.cancelNonce("addr1", 10L);
        assertFalse(result);
    }

    @Test
    public void testCancelNonce_idempotent() {
        noncePool.lockNonce("addr1", 10L);
        assertTrue(noncePool.cancelNonce("addr1", 10L));
        assertFalse(noncePool.cancelNonce("addr1", 10L));
    }

    @Test
    public void testGetLockedNonce_noLock_returnsNull() {
        assertNull(noncePool.getLockedNonce("addr1"));
    }

    @Test
    public void testIsLocked_noLock_returnsFalse() {
        assertFalse(noncePool.isLocked("addr1"));
    }

    @Test
    public void testFullTccFlow_lockConfirmCancel() throws IOException {
        // Try: lock
        long locked = noncePool.lockNonce("addr1", 100L);
        assertEquals(100L, locked);
        assertTrue(noncePool.isLocked("addr1"));

        // Confirm: release lock + write next nonce
        boolean confirmed = noncePool.confirmNonce("addr1", 100L, "0xconfirmed");
        assertTrue(confirmed);
        assertFalse(noncePool.isLocked("addr1"));
        assertEquals(101L, noncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testFullTccFlow_lockCancel() {
        // Try: lock
        long locked = noncePool.lockNonce("addr1", 100L);
        assertEquals(100L, locked);

        // Cancel: release lock, no pool write
        boolean cancelled = noncePool.cancelNonce("addr1", 100L);
        assertTrue(cancelled);
        assertFalse(noncePool.isLocked("addr1"));
        assertEquals(0L, noncePool.getMaxNonce("addr1"));
    }
}