package org.nexus.l2.zk;

import java.util.Collections;
import java.util.List;

/**
 * ZK 证明公共输入实体。
 *
 * <p>零知识证明验证时需公开的输入：包括公共状态（如状态转换前后的状态根、
 * 批次数据哈希、L1 落块高度等）。验证器结合 {@link ZkProof} 与本公共输入
 * 判断证明有效性。witness（私密输入）不在此暴露。</p>
 *
 * <h3>典型公共输入（Rollup 场景）</h3>
 * <ul>
 *   <li>preStateRoot：批次前状态根</li>
 *   <li>postStateRoot：批次后状态根</li>
 *   <li>batchDataHash：批次交易数据哈希</li>
 *   <li>l1BlockNumber：L1 落块高度</li>
 * </ul>
 *
 * @since 1.5
 */
public final class ZkPublicInput {

    /** 批次前状态根 */
    private final String preStateRoot;

    /** 批次后状态根 */
    private final String postStateRoot;

    /** 批次交易数据哈希 */
    private final String batchDataHash;

    /** L1 落块高度 */
    private final long l1BlockNumber;

    /** 附加公共输入字段（key=value 形式，用于电路扩展） */
    private final List<String> extraInputs;

    public ZkPublicInput(String preStateRoot, String postStateRoot, String batchDataHash,
                         long l1BlockNumber, List<String> extraInputs) {
        this.preStateRoot = preStateRoot;
        this.postStateRoot = postStateRoot;
        this.batchDataHash = batchDataHash;
        this.l1BlockNumber = l1BlockNumber;
        this.extraInputs = extraInputs == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(extraInputs);
    }

    public String getPreStateRoot() {
        return preStateRoot;
    }

    public String getPostStateRoot() {
        return postStateRoot;
    }

    public String getBatchDataHash() {
        return batchDataHash;
    }

    public long getL1BlockNumber() {
        return l1BlockNumber;
    }

    /**
     * 返回附加公共输入字段（只读）。
     *
     * @return 附加字段列表
     */
    public List<String> getExtraInputs() {
        return extraInputs;
    }

    @Override
    public String toString() {
        return "ZkPublicInput{preStateRoot='" + preStateRoot + '\''
                + ", postStateRoot='" + postStateRoot + '\''
                + ", batchDataHash='" + batchDataHash + '\''
                + ", l1BlockNumber=" + l1BlockNumber + '}';
    }
}