package org.nexus.l2;

import java.util.Collections;
import java.util.List;

/**
 * Merkle 树成员证明。
 *
 * <p>包含目标叶子节点（key/value）、所在索引以及从叶子到根
 * 路径上每层的兄弟节点哈希与方向位（0=当前节点是左孩子，1=右孩子）。
 * 证明尺寸 O(log n)。</p>
 *
 * @since 1.2
 */
public class MerkleProof {

    /** 证明的目标 key */
    private final String key;

    /** 证明的目标 value */
    private final String value;

    /** 叶子节点在底层列表中的索引 */
    private final int index;

    /** 自底向上每层兄弟节点哈希 */
    private final List<String> siblings;

    /** 自底向上每层方向位：0 表示当前节点为左孩子，1 表示为右孩子 */
    private final List<Integer> directions;

    public MerkleProof(String key, String value, int index,
                       List<String> siblings, List<Integer> directions) {
        this.key = key;
        this.value = value;
        this.index = index;
        this.siblings = siblings;
        this.directions = directions;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public int getIndex() {
        return index;
    }

    public List<String> getSiblings() {
        return Collections.unmodifiableList(siblings);
    }

    public List<Integer> getDirections() {
        return Collections.unmodifiableList(directions);
    }

    public int size() {
        return siblings == null ? 0 : siblings.size();
    }
}