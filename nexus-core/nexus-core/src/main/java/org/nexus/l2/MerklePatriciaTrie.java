package org.nexus.l2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 简化版 Merkle Patricia Trie。
 *
 * <p>提供基于 Merkle 树的键值存储，支持 insert / get / remove / proof / verify。
 * 内部按 key 字典序维护叶子列表，自底向上构建二叉 Merkle 树。
 * 证明尺寸 O(log n)，用于 L2 状态根计算与单步欺诈证明。</p>
 *
 * <p>本实现并非以太坊完整 MPT（无 nibble 路径 / extension / branch 节点编码），
 * 但满足欺诈证明所需的可验证性与 O(log n) 证明尺寸约束。</p>
 *
 * @since 1.2
 */
public class MerklePatriciaTrie {

    /** 空树根哈希 */
    public static final String EMPTY_ROOT = hashHex("EMPTY_TRIE");

    /** 键值存储（按 key 字典序） */
    private final TreeMap<String, String> store = new TreeMap<>();

    /** 缓存的 Merkle 根 */
    private volatile String cachedRoot = EMPTY_ROOT;

    public MerklePatriciaTrie() {
    }

    /**
     * 从已有键值集合构造。
     */
    public MerklePatriciaTrie(Map<String, String> initial) {
        if (initial != null) {
            for (Map.Entry<String, String> e : initial.entrySet()) {
                if (e.getKey() != null) {
                    store.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }
        }
        recomputeRoot();
    }

    /**
     * 插入或更新键值对。
     *
     * @param key   键（非 null）
     * @param value 值（null 视为空串）
     */
    public void insert(String key, String value) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        store.put(key, value == null ? "" : value);
        recomputeRoot();
    }

    /**
     * 批量插入。
     */
    public void insertAll(Map<String, String> entries) {
        if (entries != null) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                if (e.getKey() != null) {
                    store.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }
        }
        recomputeRoot();
    }

    /**
     * 查询键值；不存在返回 null。
     */
    public String get(String key) {
        return store.get(key);
    }

    /**
     * 删除键。
     */
    public void remove(String key) {
        store.remove(key);
        recomputeRoot();
    }

    public boolean contains(String key) {
        return store.containsKey(key);
    }

    public int size() {
        return store.size();
    }

    public boolean isEmpty() {
        return store.isEmpty();
    }

    /**
     * 获取当前 Merkle 根。
     */
    public String getRoot() {
        return cachedRoot;
    }

    /**
     * 生成 key 在 Merkle 树中的成员证明。
     *
     * @param key 已存在的键
     * @return Merkle 证明；key 不存在返回 null
     */
    public MerkleProof getProof(String key) {
        if (!store.containsKey(key)) {
            return null;
        }
        List<String> keys = new ArrayList<>(store.keySet());
        List<String> leafHashes = new ArrayList<>(keys.size());
        for (String k : keys) {
            leafHashes.add(leafHash(k, store.get(k)));
        }
        int index = keys.indexOf(key);
        List<String> siblings = new ArrayList<>();
        List<Integer> directions = new ArrayList<>();
        List<String> layer = leafHashes;
        int n = layer.size();
        int idx = index;
        while (n > 1) {
            List<String> next = new ArrayList<>((n + 1) / 2);
            for (int i = 0; i < n; i += 2) {
                String left = layer.get(i);
                String right = (i + 1 < n) ? layer.get(i + 1) : left;
                next.add(pairHash(left, right));
                if (i == idx || i + 1 == idx) {
                    if (i == idx) {
                        siblings.add(right);
                        directions.add(0);
                    } else {
                        siblings.add(left);
                        directions.add(1);
                    }
                    idx = i / 2;
                }
            }
            layer = next;
            n = layer.size();
        }
        return new MerkleProof(key, store.get(key), index, siblings, directions);
    }

    /**
     * 验证 Merkle 证明是否对应指定根。
     *
     * @param proof        Merkle 证明
     * @param expectedRoot 期望的 Merkle 根
     * @return 验证通过返回 true
     */
    public static boolean verifyProof(MerkleProof proof, String expectedRoot) {
        if (proof == null || expectedRoot == null) {
            return false;
        }
        List<String> siblings = proof.getSiblings();
        List<Integer> directions = proof.getDirections();
        if (siblings.size() != directions.size()) {
            return false;
        }
        String computed = leafHash(proof.getKey(), proof.getValue());
        for (int i = 0; i < siblings.size(); i++) {
            String sibling = siblings.get(i);
            int dir = directions.get(i);
            if (dir == 0) {
                computed = pairHash(computed, sibling);
            } else {
                computed = pairHash(sibling, computed);
            }
        }
        return computed.equals(expectedRoot);
    }

    /**
     * 导出所有键值的不可变快照。
     */
    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(store));
    }

    /**
     * 复制 trie。
     */
    public MerklePatriciaTrie copy() {
        return new MerklePatriciaTrie(store);
    }

    private void recomputeRoot() {
        if (store.isEmpty()) {
            cachedRoot = EMPTY_ROOT;
            return;
        }
        List<String> layer = new ArrayList<>(store.size());
        for (Map.Entry<String, String> e : store.entrySet()) {
            layer.add(leafHash(e.getKey(), e.getValue()));
        }
        while (layer.size() > 1) {
            List<String> next = new ArrayList<>((layer.size() + 1) / 2);
            for (int i = 0; i < layer.size(); i += 2) {
                String left = layer.get(i);
                String right = (i + 1 < layer.size()) ? layer.get(i + 1) : left;
                next.add(pairHash(left, right));
            }
            layer = next;
        }
        cachedRoot = layer.get(0);
    }

    private static String leafHash(String key, String value) {
        return hashHex("L|" + key + "|" + value);
    }

    private static String pairHash(String left, String right) {
        return hashHex("P|" + left + "|" + right);
    }

    private static String hashHex(String data) {
        MessageDigest md = newDigest();
        return bytesToHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}