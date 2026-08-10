package org.nexus.l2.zk;

import java.util.Arrays;

/**
 * ZK 证明实体。
 *
 * <p>零知识证明的产物，包含证明数据字节、所用电路 ID、可信设置版本与
 * 生成时间戳。证明本身不暴露任何 witness 信息，仅可由 {@link ZkVerifier}
 * 结合 public inputs 验证其有效性。</p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code proofData}：证明数据（编码后的多项式承诺/打开证明，具体格式由后端 ZK 库决定）</li>
 *   <li>{@code circuitId}：所用电路 ID（标识哪个电路生成的证明）</li>
 *   <li>{@code setupVersion}：可信设置版本（绑定 {@link TrustedSetup}）</li>
 *   <li>{@code createdAt}：生成时间戳（毫秒）</li>
 * </ul>
 *
 * <p>当前为骨架实现，真实 ZK 证明需接入 halo2、Plonk 等电路编译器与证明系统。</p>
 *
 * @since 1.5
 */
public final class ZkProof {

    /** 证明数据字节 */
    private final byte[] proofData;

    /** 所用电路 ID */
    private final String circuitId;

    /** 可信设置版本 */
    private final int setupVersion;

    /** 生成时间戳（毫秒） */
    private final long createdAt;

    public ZkProof(byte[] proofData, String circuitId, int setupVersion, long createdAt) {
        this.proofData = proofData == null ? new byte[0] : proofData.clone();
        this.circuitId = circuitId;
        this.setupVersion = setupVersion;
        this.createdAt = createdAt;
    }

    /**
     * 返回证明数据（防御性拷贝）。
     *
     * @return 证明数据副本
     */
    public byte[] getProofData() {
        return proofData.clone();
    }

    public String getCircuitId() {
        return circuitId;
    }

    public int getSetupVersion() {
        return setupVersion;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 返回证明数据长度（字节）。
     *
     * @return 证明大小
     */
    public int size() {
        return proofData.length;
    }

    @Override
    public String toString() {
        return "ZkProof{circuitId='" + circuitId + '\''
                + ", setupVersion=" + setupVersion
                + ", size=" + proofData.length
                + ", createdAt=" + createdAt + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZkProof)) {
            return false;
        }
        ZkProof other = (ZkProof) o;
        return setupVersion == other.setupVersion
                && Arrays.equals(proofData, other.proofData)
                && (circuitId == null ? other.circuitId == null : circuitId.equals(other.circuitId));
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(proofData);
        result = 31 * result + (circuitId == null ? 0 : circuitId.hashCode());
        result = 31 * result + setupVersion;
        return result;
    }
}