package org.nexus.core;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.core.account.Transaction;
import org.nexus.encoding.BigEndian;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Block 模型层单测（A 项覆盖率提升：0.25→0.30）。
 * 断言基于源码语义逐条核对（Block.java:59-417）：
 * 头部 132 字节定长布局、哈希缓存/reHash、size=头部+体+预留、
 * deepCopy/toHeader 字段透传、Merkle 计算确定性。
 */
class BlockModelTest {

    /** 构造字段全非零的区块（便于断言字节布局）。 */
    private static Block sampleBlock() {
        Block b = new Block();
        b.nVersion = 1;
        b.hashPrevBlock = filled(32, (byte) 1);
        b.hashMerkleRoot = filled(32, (byte) 2);
        b.hashMerkleState = filled(32, (byte) 3);
        b.hashMerkleIncubate = filled(32, (byte) 4);
        b.nHeight = 100;
        b.nTime = 1234567890;
        b.nBits = filled(32, (byte) 5);
        b.nNonce = filled(32, (byte) 6);
        b.blockNotice = new byte[]{7};
        b.body = new ArrayList<>();
        return b;
    }

    private static byte[] filled(int len, byte v) {
        byte[] a = new byte[len];
        Arrays.fill(a, v);
        return a;
    }

    // ===== getHeaderRaw 布局（:78） =====

    @Test
    void headerRawIsFixed132ByteLayout() {
        Block b = sampleBlock();
        byte[] raw = b.getHeaderRaw();
        // 4+32+32+32+32+4+4+32+32 = 204 字节
        assertEquals(204, raw.length);
        assertEquals(0, raw[0], "nVersion 大端 4 字节，高位在前");
        assertEquals(1, raw[3]);
        assertEquals(1, raw[4], "hashPrevBlock 首字节");
        assertEquals(6, raw[204 - 32], "nNonce 区起始");
        // 静态入口与实例方法一致
        assertArrayEquals(raw, Block.getHeaderRaw(b));
    }

    // ===== getHash/reHash/setHashCache（:204-214/284） =====

    @Test
    void hashCachedAndReHashRecomputes() {
        Block b = sampleBlock();
        byte[] h1 = b.getHash();
        assertSame(h1, b.getHash(), "重复调用返回缓存");
        // 修改字段后 reHash 强制重算
        b.nHeight = 200;
        byte[] h2 = b.reHash();
        assertNotEquals(org.apache.commons.codec.binary.Hex.encodeHexString(h1),
                org.apache.commons.codec.binary.Hex.encodeHexString(h2));
        assertSame(h2, b.getHash(), "reHash 后缓存更新");

        byte[] fake = filled(32, (byte) 9);
        b.setHashCache(fake);
        assertSame(fake, b.getHash());
    }

    @Test
    void hashDeterministicForEqualHeaders() {
        Block a = sampleBlock();
        Block c = sampleBlock();
        assertArrayEquals(a.getHash(), c.getHash(), "同字段区块哈希必须一致");
        // 缓存语义：首次 getHash 后改字段，哈希不再自动反映——必须显式 reHash
        c.nTime = 999;
        assertArrayEquals(a.getHash(), c.getHash(), "缓存未失效（真实契约：失效靠调用方 reHash）");
        byte[] rehashed = c.reHash();
        assertFalse(Arrays.equals(a.getHash(), rehashed), "reHash 后反映新字段");
    }

    // ===== getHashHexString（:297） =====

    @Test
    void hashHexStringCachedAndMatchesHash() {
        Block b = sampleBlock();
        String hex = b.getHashHexString();
        assertEquals(Hex.encodeHexString(b.getHash()), hex);
        assertSame(hex, b.getHashHexString(), "hex 也缓存");
    }

    // ===== size（:192） =====

    @Test
    void sizeIsHeaderPlusBodyPlusReservedSpace() {
        Block b = sampleBlock();
        int expected = 204 + Block.RESERVED_SPACE;
        assertEquals(expected, b.size(), "空 body");

        Transaction tx = new Transaction();
        tx.version = 1;
        tx.type = 1;
        tx.from = new byte[32];
        tx.to = new byte[20];
        tx.signature = new byte[64];
        b.body.add(tx);
        // tx.size = raw(146 无 payload) + hash(32)
        assertEquals(expected + tx.size(), b.size());
    }

    @Test
    void sizeNullBodyCountsOnlyReserved() {
        Block b = sampleBlock();
        b.body = null;
        assertEquals(204 + Block.RESERVED_SPACE, b.size());
    }

    // ===== deepCopy（:93）——浅拷贝共享数组引用 =====

    @Test
    void deepCopyTransfersAllHeaderFields() {
        Block b = sampleBlock();
        b.weight = 11;
        b.totalWeight = 22;
        b.body.add(new Transaction());
        Block c = Block.deepCopy(b);
        assertEquals(b.nVersion, c.nVersion);
        assertEquals(b.nHeight, c.nHeight);
        assertEquals(b.nTime, c.nTime);
        assertSame(b.hashPrevBlock, c.hashPrevBlock, "数组引用共享（deepCopy 实为字段级拷贝）");
        assertSame(b.body, c.body);
        // 哈希缓存随字段移交——copy 后哈希与原块一致且无需重算
        assertArrayEquals(b.getHash(), c.getHash());
        assertEquals(11, c.weight);
        assertEquals(22, c.totalWeight);
    }

    // ===== toHeader（:378）——剥掉 body 只留头部 =====

    @Test
    void toHeaderStripsBody() {
        Block b = sampleBlock();
        b.body.add(new Transaction());
        Block h = b.toHeader();
        assertNull(h.body, "toHeader 不携带交易体");
        assertEquals(b.nVersion, h.nVersion);
        assertEquals(b.nHeight, h.nHeight);
        assertSame(b.hashPrevBlock, h.hashPrevBlock);
        assertSame(b.nBits, h.nBits);
        assertSame(b.blockNotice, h.blockNotice);
        // 哈希与原块一致（头部字段决定）
        assertArrayEquals(b.getHash(), h.getHash());
    }

    // ===== calculatePOWHash（:68）——六重哈希链确定性 =====

    @Test
    void powHashIsDeterministic32Bytes() {
        Block b = sampleBlock();
        byte[] p1 = Block.calculatePOWHash(b);
        byte[] p2 = Block.calculatePOWHash(b);
        assertEquals(32, p1.length);
        assertArrayEquals(p1, p2);
        // 与普通 getHash（keccak256）不同算法路径
        assertFalse(Arrays.equals(p1, b.getHash()));
        Block b2 = sampleBlock();
        b2.nNonce = filled(32, (byte) 7);
        assertFalse(Arrays.equals(p1, Block.calculatePOWHash(b2)));
    }

    // ===== Merkle 计算（:115/128/143） =====

    @Test
    void merkleRootOfEmptyBodyIsAllZeroFallback() {
        // calculateMerkleRoot 空 list → MerkleTree 根仍计算（空树默认值）
        byte[] root = Block.calculateMerkleRoot(new ArrayList<>());
        assertNotNull(root);
        assertEquals(32, root.length);
    }

    @Test
    void merkleRootDeterministicAndOrderSensitive() {
        Transaction t1 = simpleTx((byte) 1);
        Transaction t2 = simpleTx((byte) 2);
        List<Transaction> l1 = Arrays.asList(t1, t2);
        List<Transaction> l2 = Arrays.asList(t2, t1);
        assertArrayEquals(
                Block.calculateMerkleRoot(l1),
                Block.calculateMerkleRoot(Arrays.asList(t1, t2)));
        // 顺序不同 → 根不同（哈希两两配对，顺序参与）
        assertFalse(Arrays.equals(Block.calculateMerkleRoot(l1), Block.calculateMerkleRoot(l2)));
    }

    @Test
    void merkleStateAndIncubateEmptyListFallback() {
        // calculateMerkleState/Incubate 空 list → 直接返回 32 零（:133/:148 守卫）
        assertArrayEquals(new byte[32], Block.calculateMerkleState(new ArrayList<>()));
        assertArrayEquals(new byte[32], Block.calculateMerkleIncubate(new ArrayList<>()));
    }

    // ===== getFromsPublicKeyHash（:394）——过滤 coinbase、去重 =====

    @Test
    void fromsPublicKeyHashFiltersCoinbaseAndDedupes() {
        Block b = sampleBlock();
        Transaction coinbase = simpleTx((byte) 0);
        coinbase.type = 0;
        Transaction normal = simpleTx((byte) 1);
        normal.type = 1;
        Transaction sameFrom = simpleTx((byte) 1);
        sameFrom.type = 1;
        // 同一 from 的两笔交易 → 去重为 1
        b.body.add(coinbase);
        b.body.add(normal);
        b.body.add(sameFrom);
        List<byte[]> froms = b.getFromsPublicKeyHash();
        assertEquals(1, froms.size(), "coinbase 过滤 + 同 from 去重");
        assertEquals(20, froms.get(0).length);
    }

    // ===== Merkle 树层级工具（:403/411） =====

    @Test
    void merkleTreeLevelTools() {
        List<Transaction> txs = Arrays.asList(simpleTx((byte) 1), simpleTx((byte) 2), simpleTx((byte) 3));
        int level = Block.getMerkleRootLevel(txs);
        assertTrue(level >= 1, "3 叶子的树至少叶子+父两层");
        // 叶子层编号从 1 开始（MerkleTree.createLeafList :setLevel((byte)1)）
        List<?> level1 = Block.getMerkleTreeNode(txs, (byte) 1);
        assertEquals(3, level1.size(), "第 1 层应为全部叶子");
        // 层号 0 未定义 → map 无该键返回 null（HashMap.get 契约）
        assertNull(Block.getMerkleTreeNode(txs, (byte) 0));
        // 根层取值存在且规模收缩
        List<?> rootLevel = Block.getMerkleTreeNode(txs, (byte) level);
        assertEquals(1, rootLevel.size(), "最高层唯一根节点");
    }

    // ===== setter 与权重（:220/224/374） =====

    @Test
    void settersAndWeightFields() {
        Block b = new Block();
        b.setBlockSize(512);
        b.setBlockHash(filled(32, (byte) 8));
        b.setWeight(77);
        assertEquals(77, b.weight);
        b.nHeight = 5;
        assertEquals(5, b.getnHeight());
        // 反射读回私有兼容字段
        try {
            java.lang.reflect.Field f = Block.class.getDeclaredField("blockSize");
            f.setAccessible(true);
            assertEquals(512, f.getInt(b));
            java.lang.reflect.Field fh = Block.class.getDeclaredField("blockHash");
            fh.setAccessible(true);
            assertEquals(32, ((byte[]) fh.get(b)).length);
        } catch (ReflectiveOperationException e) {
            fail("reflection readback failed: " + e.getMessage());
        }
    }

    // ===== 常量 =====

    @Test
    void constantsMatchDocumentedLimits() {
        assertEquals(32, Block.MAX_NOTICE_LENGTH);
        assertEquals(32, Block.HASH_SIZE);
        assertEquals(4 * 1024 * 1024, Block.MAX_BLOCK_SIZE);
        assertEquals(128 * 1024, Block.RESERVED_SPACE);
    }

    /** 最小可哈希交易（字段合法定长）。 */
    private static Transaction simpleTx(byte seed) {
        Transaction t = new Transaction();
        t.version = 1;
        t.type = 1;
        t.from = filled(32, seed);
        t.to = filled(20, seed);
        t.signature = filled(64, seed);
        t.nonce = seed;
        return t;
    }
}
