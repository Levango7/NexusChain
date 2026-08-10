package org.nexus.signing.mpc.transport;

/**
 * 聚合轮次消息（对应 Protobuf AggregateRound）。
 *
 * <p>在所有签名轮次完成后，每个参与者广播自己的签名份额 s_i，
 * 由聚合者（{@code MpcSignatureAggregator}）收集 t 个份额并组合为
 * 最终 ECDSA 签名 {@code (r, s)}。</p>
 *
 * <p>该类是 {@link MpcMessage} 中 {@code type=AGGREGATE_ROUND} 的 payload
 * 结构化视图。当前为 Java POJO 模拟，未来可由 protobuf-generated 类替换。</p>
 */
public final class AggregateRoundMessage {

    /** 发送者参与者 ID。 */
    private final String fromParticipantId;

    /** 签名份额 s_i（hex）。 */
    private final String sigShareHex;

    /** 聚合者 ID（{@code null} 表示广播给所有参与者）。 */
    private final String aggregatorId;

    /** 是否附带 ZK 证明（用于可验证聚合）。 */
    private final boolean withZkProof;

    /** ZK 证明（hex），可选。 */
    private final String zkProofHex;

    private AggregateRoundMessage(Builder b) {
        this.fromParticipantId = b.fromParticipantId;
        this.sigShareHex = b.sigShareHex;
        this.aggregatorId = b.aggregatorId;
        this.withZkProof = b.withZkProof;
        this.zkProofHex = b.zkProofHex;
    }

    public String getFromParticipantId() { return fromParticipantId; }
    public String getSigShareHex() { return sigShareHex; }
    public String getAggregatorId() { return aggregatorId; }
    public boolean isWithZkProof() { return withZkProof; }
    public String getZkProofHex() { return zkProofHex; }

    /**
     * 序列化为简单分隔符格式（payloadHex 内容）。
     * 未来切换 protobuf 时替换此方法。
     *
     * @return 编码字符串
     */
    public String toPayloadHex() {
        return join(fromParticipantId, sigShareHex, aggregatorId,
                withZkProof ? "1" : "0", zkProofHex);
    }

    /**
     * 从 payloadHex 反序列化。
     *
     * @param payload payloadHex
     * @return 消息实例
     */
    public static AggregateRoundMessage fromPayloadHex(String payload) {
        String[] parts = payload.split("\\|", -1);
        Builder b = new Builder();
        b.fromParticipantId = emptyToNull(parts[0]);
        b.sigShareHex = emptyToNull(parts[1]);
        b.aggregatorId = emptyToNull(parts[2]);
        b.withZkProof = "1".equals(parts[3]);
        b.zkProofHex = emptyToNull(parts[4]);
        return b.build();
    }

    private static String join(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(fields[i] == null ? "" : fields[i].toString());
        }
        return sb.toString();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Builder。 */
    public static final class Builder {
        private String fromParticipantId;
        private String sigShareHex;
        private String aggregatorId;
        private boolean withZkProof;
        private String zkProofHex;

        public Builder from(String id) { this.fromParticipantId = id; return this; }
        public Builder sigShare(String hex) { this.sigShareHex = hex; return this; }
        public Builder aggregator(String id) { this.aggregatorId = id; return this; }
        public Builder withZkProof(boolean v) { this.withZkProof = v; return this; }
        public Builder zkProof(String hex) { this.zkProofHex = hex; return this; }

        public AggregateRoundMessage build() { return new AggregateRoundMessage(this); }
    }
}