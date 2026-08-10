package org.nexus.signing.mpc.crypto;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * DKG（分布式密钥生成）请求 DTO。
 *
 * <p>纯 Java POJO，不依赖 gRPC 生成类，解耦编排层与传输层。
 * {@link GrpcMpcCryptoEngine} 负责将本 DTO 转换为 protobuf 生成类
 * {@code org.nexus.signing.mpc.crypto.grpc.DkgRequest} 并发起 gRPC 调用。</p>
 *
 * <p>对应审计报告 §4.1 方案 A：Rust multi-party-ecdsa 引擎 DKG 阶段。</p>
 *
 * <p>不可变值对象。</p>
 */
public final class DkgRequest {

    /** 全局唯一会话 ID（编排层生成，跨所有参与方一致）。 */
    private final String sessionId;
    /** 阈值 t（t-of-n）。 */
    private final int threshold;
    /** 总参与方数 n。 */
    private final int totalParties;
    /** 本节点索引（0-based）。 */
    private final int partyIndex;
    /** 椭圆曲线名称，如 "secp256k1"。 */
    private final String curve;
    /** 其他参与方 gRPC 端点（host:port 列表）。 */
    private final List<String> peerEndpoints;

    /**
     * 构造 DKG 请求。
     *
     * @param sessionId      全局会话 ID
     * @param threshold      阈值 t
     * @param totalParties   总参与方数 n
     * @param partyIndex     本节点索引
     * @param curve          曲线名称
     * @param peerEndpoints  其他参与方端点列表
     */
    public DkgRequest(String sessionId,
                      int threshold,
                      int totalParties,
                      int partyIndex,
                      String curve,
                      List<String> peerEndpoints) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.threshold = threshold;
        this.totalParties = totalParties;
        this.partyIndex = partyIndex;
        this.curve = Objects.requireNonNull(curve, "curve");
        this.peerEndpoints = List.copyOf(Objects.requireNonNull(peerEndpoints, "peerEndpoints"));
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getThreshold() {
        return threshold;
    }

    public int getTotalParties() {
        return totalParties;
    }

    public int getPartyIndex() {
        return partyIndex;
    }

    public String getCurve() {
        return curve;
    }

    /**
     * @return 其他参与方端点列表（不可变）
     */
    public List<String> getPeerEndpoints() {
        return peerEndpoints;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DkgRequest)) return false;
        DkgRequest that = (DkgRequest) o;
        return threshold == that.threshold
                && totalParties == that.totalParties
                && partyIndex == that.partyIndex
                && sessionId.equals(that.sessionId)
                && curve.equals(that.curve)
                && peerEndpoints.equals(that.peerEndpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, threshold, totalParties, partyIndex, curve, peerEndpoints);
    }

    @Override
    public String toString() {
        return "DkgRequest{sessionId='" + sessionId + "', threshold=" + threshold
                + ", totalParties=" + totalParties + ", partyIndex=" + partyIndex
                + ", curve='" + curve + "', peerEndpoints=" + peerEndpoints + '}';
    }
}