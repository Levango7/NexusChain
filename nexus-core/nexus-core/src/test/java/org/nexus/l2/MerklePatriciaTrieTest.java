package org.nexus.l2;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * {@link MerklePatriciaTrie} 单元测试。
 *
 * <p>覆盖 insert/get/remove/contains/size/getRoot/getProof/verifyProof/snapshot/copy
 * 的正常路径、边界条件与异常场景。重点验证 Merkle 证明的生成与验证自洽性
 * （单步欺诈证明的安全前提）。</p>
 *
 * @since 1.2
 */
public class MerklePatriciaTrieTest {

    private MerklePatriciaTrie trie;

    @Before
    public void setUp() {
        trie = new MerklePatriciaTrie();
    }

    // ==================== 基本操作 ====================

    @Test
    public void emptyTrie_hasEmptyRoot() {
        assertNotNull(trie.getRoot());
        assertEquals(MerklePatriciaTrie.EMPTY_ROOT, trie.getRoot());
        assertTrue(trie.isEmpty());
        assertEquals(0, trie.size());
    }

    @Test
    public void insertAndGet_singleEntry() {
        trie.insert("k1", "v1");
        assertEquals("v1", trie.get("k1"));
        assertEquals(1, trie.size());
        assertFalse(trie.isEmpty());
        assertNotEquals(MerklePatriciaTrie.EMPTY_ROOT, trie.getRoot());
    }

    @Test
    public void insert_nullValue_treatedAsEmptyString() {
        trie.insert("k1", null);
        assertEquals("", trie.get("k1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void insert_nullKey_throws() {
        trie.insert(null, "v");
    }

    @Test
    public void insert_overwriteExistingKey_updatesValueAndRoot() {
        trie.insert("k1", "v1");
        String root1 = trie.getRoot();
        trie.insert("k1", "v2");
        assertEquals("v2", trie.get("k1"));
        assertEquals(1, trie.size());
        assertNotEquals(root1, trie.getRoot());
    }

    @Test
    public void insertAll_batchInsert() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a", "1");
        entries.put("b", "2");
        entries.put("c", "3");
        trie.insertAll(entries);
        assertEquals("1", trie.get("a"));
        assertEquals("2", trie.get("b"));
        assertEquals("3", trie.get("c"));
        assertEquals(3, trie.size());
    }

    @Test
    public void insertAll_nullMap_noChange() {
        trie.insert("x", "y");
        trie.insertAll(null);
        assertEquals(1, trie.size());
        assertEquals("y", trie.get("x"));
    }

    @Test
    public void get_nonExistingKey_returnsNull() {
        trie.insert("k1", "v1");
        assertNull(trie.get("nonexistent"));
    }

    @Test
    public void remove_existingKey() {
        trie.insert("k1", "v1");
        trie.insert("k2", "v2");
        String rootBefore = trie.getRoot();
        trie.remove("k1");
        assertNull(trie.get("k1"));
        assertEquals("v2", trie.get("k2"));
        assertEquals(1, trie.size());
        assertNotEquals(rootBefore, trie.getRoot());
    }

    @Test
    public void remove_lastKey_backToEmptyRoot() {
        trie.insert("k1", "v1");
        trie.remove("k1");
        assertEquals(0, trie.size());
        assertEquals(MerklePatriciaTrie.EMPTY_ROOT, trie.getRoot());
        assertTrue(trie.isEmpty());
    }

    @Test
    public void remove_nonExistingKey_noOp() {
        trie.insert("k1", "v1");
        String root = trie.getRoot();
        trie.remove("nope");
        assertEquals(root, trie.getRoot());
        assertEquals(1, trie.size());
    }

    @Test
    public void contains_returnsCorrectMembership() {
        trie.insert("k1", "v1");
        assertTrue(trie.contains("k1"));
        assertFalse(trie.contains("k2"));
    }

    // ==================== 状态根确定性 ====================

    @Test
    public void rootIsDeterministic_forSameEntriesRegardlessOfInsertOrder() {
        MerklePatriciaTrie t1 = new MerklePatriciaTrie();
        MerklePatriciaTrie t2 = new MerklePatriciaTrie();
        t1.insert("a", "1");
        t1.insert("b", "2");
        t1.insert("c", "3");
        // 不同插入顺序
        t2.insert("c", "3");
        t2.insert("a", "1");
        t2.insert("b", "2");
        assertEquals(t1.getRoot(), t2.getRoot());
    }

    @Test
    public void differentValues_produceDifferentRoots() {
        MerklePatriciaTrie t1 = new MerklePatriciaTrie();
        MerklePatriciaTrie t2 = new MerklePatriciaTrie();
        t1.insert("k", "v1");
        t2.insert("k", "v2");
        assertNotEquals(t1.getRoot(), t2.getRoot());
    }

    // ==================== Merkle 证明 ====================

    @Test
    public void getProof_nonExistingKey_returnsNull() {
        trie.insert("k1", "v1");
        assertNull(trie.getProof("nope"));
    }

    @Test
    public void proof_verifiesAgainstCurrentRoot_singleEntry() {
        trie.insert("k1", "v1");
        MerkleProof proof = trie.getProof("k1");
        assertNotNull(proof);
        assertEquals("k1", proof.getKey());
        assertEquals("v1", proof.getValue());
        assertTrue(MerklePatriciaTrie.verifyProof(proof, trie.getRoot()));
    }

    @Test
    public void proof_verifiesAgainstCurrentRoot_multipleEntries() {
        trie.insert("a", "1");
        trie.insert("b", "2");
        trie.insert("c", "3");
        trie.insert("d", "4");
        String root = trie.getRoot();
        for (String key : new String[]{"a", "b", "c", "d"}) {
            MerkleProof proof = trie.getProof(key);
            assertNotNull("proof for " + key, proof);
            assertTrue("verify proof for " + key,
                    MerklePatriciaTrie.verifyProof(proof, root));
        }
    }

    @Test
    public void proof_sizeIsLogarithmic() {
        // 8 个叶子 → 证明深度 3
        for (int i = 0; i < 8; i++) {
            trie.insert(String.format("k%02d", i), "v" + i);
        }
        MerkleProof proof = trie.getProof("k04");
        assertNotNull(proof);
        assertEquals(3, proof.size());
        assertEquals(proof.getSiblings().size(), proof.getDirections().size());
    }

    @Test
    public void proof_failsAgainstWrongRoot() {
        trie.insert("a", "1");
        trie.insert("b", "2");
        MerkleProof proof = trie.getProof("a");
        // 用一个错误的根
        assertFalse(MerklePatriciaTrie.verifyProof(proof, "deadbeef"));
    }

    @Test
    public void proof_failsAfterValueModified() {
        trie.insert("a", "1");
        trie.insert("b", "2");
        MerkleProof proof = trie.getProof("a");
        String root = trie.getRoot();
        // 旧证明对旧根仍然有效（证明本身记录了旧 value 与旧 siblings）
        assertTrue(MerklePatriciaTrie.verifyProof(proof, root));
        // 修改 a 的值后新根不同于旧根
        trie.insert("a", "999");
        String newRoot = trie.getRoot();
        assertNotEquals(root, newRoot);
        // 旧证明（含旧 value "1"）对新根应失效
        assertFalse(MerklePatriciaTrie.verifyProof(proof, newRoot));
    }

    @Test
    public void verifyProof_nullProof_returnsFalse() {
        assertFalse(MerklePatriciaTrie.verifyProof(null, "root"));
    }

    @Test
    public void verifyProof_nullRoot_returnsFalse() {
        trie.insert("k", "v");
        MerkleProof proof = trie.getProof("k");
        assertFalse(MerklePatriciaTrie.verifyProof(proof, null));
    }

    // ==================== snapshot / copy ====================

    @Test
    public void snapshot_isImmutableView() {
        trie.insert("a", "1");
        trie.insert("b", "2");
        Map<String, String> snap = trie.snapshot();
        assertEquals(2, snap.size());
        assertEquals("1", snap.get("a"));
        try {
            snap.put("c", "3");
            fail("snapshot should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // 期望不可修改
        }
    }

    @Test
    public void copy_isIndependentOfOriginal() {
        trie.insert("a", "1");
        MerklePatriciaTrie copy = trie.copy();
        assertEquals(trie.getRoot(), copy.getRoot());
        // 修改原 trie 不影响 copy
        trie.insert("b", "2");
        assertNotEquals(trie.getRoot(), copy.getRoot());
        assertNull(copy.get("b"));
        assertEquals("1", copy.get("a"));
    }

    @Test
    public void constructor_fromMap_initializesEntries() {
        Map<String, String> initial = new HashMap<>();
        initial.put("x", "10");
        initial.put("y", "20");
        MerklePatriciaTrie t = new MerklePatriciaTrie(initial);
        assertEquals("10", t.get("x"));
        assertEquals("20", t.get("y"));
        assertEquals(2, t.size());
    }

    @Test
    public void constructor_fromNullMap_isEmpty() {
        MerklePatriciaTrie t = new MerklePatriciaTrie(null);
        assertTrue(t.isEmpty());
    }

    // ==================== 边界：奇数叶子 ====================

    @Test
    public void oddNumberOfLeaves_proofsStillValid() {
        // 5 个叶子（奇数，最后一层会补齐）
        for (int i = 0; i < 5; i++) {
            trie.insert("k" + i, "v" + i);
        }
        String root = trie.getRoot();
        for (int i = 0; i < 5; i++) {
            MerkleProof proof = trie.getProof("k" + i);
            assertNotNull(proof);
            assertTrue("proof for k" + i, MerklePatriciaTrie.verifyProof(proof, root));
        }
    }

    @Test
    public void singleLeaf_proofHasNoSiblings() {
        trie.insert("only", "val");
        MerkleProof proof = trie.getProof("only");
        assertNotNull(proof);
        assertEquals(0, proof.size());
        assertTrue(MerklePatriciaTrie.verifyProof(proof, trie.getRoot()));
    }
}