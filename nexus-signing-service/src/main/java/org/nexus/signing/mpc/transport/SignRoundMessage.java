package org.nexus.signing.mpc.transport;

/**
 * 分布式签名轮次消息（对应 Protobuf SignRound）。
 *
 * <p>GG18/GG20 签名共 7 轮（见 {@code MpcSigningSession.SIGN_ROUNDS}）：</p>
 * <ol>
 *   <li>轮次 1：每个参与者采样本地 k_i，广播 R_i = k_i * G。</li>
 *   <li>轮次 2：MtA 协议计算 k_i * x_j（点对点）。</li>
 *   <li>轮次 3：MtA 协议计算 k_i * k_j（点对点）。</li>
 *   <li>轮次 4：聚合 R = sum(R_i)，导出 r = R.x mod n（广播）。</li>
 *   <li>轮次 5：计算本地份额 s_i = k_i * m + r * x_i (mod n)（本地）。</li>
 *   <li>轮次 6：ZK 证明份额正确性（广播）。</li>
 *   <li>轮次 7：广播 s_i。</li>
 * </ol>
 *
 * <p>该类是 {@link MpcMessage} 中 {@code type=SIGN_ROUND} 的 payload
 * 结构化视图。当前为 Java POJO 模拟，未来可由 protobuf-generated 类替换。</p>
 */
public final class SignRoundMessage {

    /** 签名轮次号（1-7）。 */
    private final int round;

    /** 发送者参与者 ID。 */
    private final String fromParticipantId;

    /** 接收者参与者 ID（{@code null} 表示广播）。 */
    private final String toParticipantId;

    /** R_i = k_i * G（hex），轮次 1 广播。 */
    private final String rPointHex;

    /** MtA 加密份额（hex），轮次 2/3 点对点。 */
    private final String mtaShareHex;

    /** 聚合 R 点（hex），轮次 4 广播。 */
    private final String aggregateRHex;

    /** r = R.x mod n（hex），轮次 4 广播。 */
    private final String rScalarHex;

    /** ZK 证明（hex），轮次 6 广播。 */
    private final String zkProofHex;

    /** 签名份额 s_i（hex），轮次 7 广播。 */
    private final String sigShareHex;

    private SignRoundMessage(Builder b) {
        this.round = b.round;
        this.fromParticipantId = b.fromParticipantId;
        this.toParticipantId = b.toParticipantId;
        this.rPointHex = b.rPointHex;
        this.mtaShareHex = b.mtaShareHex;
        this.aggregateRHex = b.aggregateRHex;
        this.rScalarHex = b.rScalarHex;
        this.zkProofHex = b.zkProofHex;
        this.sigShareHex = b.sigShareHex;
    }

    public int getRound() { return round; }
    public String getFromParticipantId() { return fromParticipantId; }
    public String getToParticipantId() { return toParticipantId; }
    public String getRPointHex() { return rPointHex; }
    public String getMtaShareHex() { return mtaShareHex; }
    public String getAggregateRHex() { return aggregateRHex; }
    public String getRScalarHex() { return rScalarHex; }
    public String getZkProofHex() { return zkProofHex; }
    public String getSigShareHex() { return sigShareHex; }

    /**
     * 序列化为简单分隔符格式（payloadHex 内容）。
     * 未来切换 protobuf 时替换此方法。
     *
     * @return 编码字符串
     */
    public String toPayloadHex() {
        return join(round, fromParticipantId, toParticipantId,
                rPointHex, mtaShareHex, aggregateRHex, rScalarHex, zkProofHex, sigShareHex);
    }

    /**
     * 从 payloadHex 反序列化。
     *
     * @param payload payloadHex
     * @return 消息实例
     */
    public static SignRoundMessage fromPayloadHex(String payload) {
        String[] parts = payload.split("\\|", -1);
        Builder b = new Builder();
        b.round = Integer.parseInt(parts[0]);
        b.fromParticipantId = emptyToNull(parts[1]);
        b.toParticipantId = emptyToNull(parts[2]);
        b.rPointHex = emptyToNull(parts[3]);
        b.mtaShareHex = emptyToNull(parts[4]);
        b.aggregateRHex = emptyToNull(parts[5]);
        b.rScalarHex = emptyToNull(parts[6]);
        b.zkProofHex = emptyToNull(parts[7]);
        b.sigShareHex = emptyToNull(parts[8]);
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
        private int round;
        private String fromParticipantId;
        private String toParticipantId;
        private String rPointHex;
        private String mtaShareHex;
        private String aggregateRHex;
        private String rScalarHex;
        private String zkProofHex;
        private String sigShareHex;

        public Builder round(int round) { this.round = round; return this; }
        public Builder from(String id) { this.fromParticipantId = id; return this; }
        public Builder to(String id) { this.toParticipantId = id; return this; }
        public Builder rPoint(String hex) { this.rPointHex = hex; return this; }
        public Builder mtaShare(String hex) { this.mtaShareHex = hex; return this; }
        public Builder aggregateR(String hex) { this.aggregateRHex = hex; return this; }
        public Builder rScalar(String hex) { this.rScalarHex = hex; return this; }
        public Builder zkProof(String hex) { this.zkProofHex = hex; return this; }
        public Builder sigShare(String hex) { this.sigShareHex = hex; return this; }

        public SignRoundMessage build() { return new SignRoundMessage(this); }
    }
}