package org.nexus.signing.mpc.transport;

/**
 * 分布式密钥生成（DKG）轮次消息（对应 Protobuf KeyGenRound）。
 *
 * <p>GG18/GG20 DKG 通常包含 4 轮：</p>
 * <ol>
 *   <li>轮次 1：每个参与者广播 Paillier 公钥与 NTL commitments。</li>
 *   <li>轮次 2：参与者之间点对点交换 Paillier 加密份额。</li>
 *   <li>轮次 3：广播 ZK 证明（Paillier 一致性、Range proof）。</li>
 *   <li>轮次 4：聚合得到联合公钥 X = sum(X_i)。</li>
 * </ol>
 *
 * <p>该类是 {@link MpcMessage} 中 {@code type=KEY_GEN_ROUND} 的 payload
 * 结构化视图，payloadHex 编码该类的字段。当前为 Java POJO 模拟，
 * 未来可由 protobuf-generated 类替换。</p>
 */
public final class KeyGenRoundMessage {

    /** DKG 轮次号（1-4）。 */
    private final int round;

    /** 发送者参与者 ID。 */
    private final String fromParticipantId;

    /** 接收者参与者 ID（{@code null} 表示广播）。 */
    private final String toParticipantId;

    /** Paillier 公钥 N（hex），轮次 1 广播。 */
    private final String paillierNHex;

    /** Paillier 公钥 g（hex），轮次 1 广播。 */
    private final String paillierGHex;

    /** 加密份额（hex），轮次 2 点对点。 */
    private final String encryptedShareHex;

    /** ZK 证明（hex），轮次 3 广播。 */
    private final String zkProofHex;

    /** 公钥份额 X_i（hex），轮次 4 广播。 */
    private final String publicKeyShareHex;

    private KeyGenRoundMessage(Builder b) {
        this.round = b.round;
        this.fromParticipantId = b.fromParticipantId;
        this.toParticipantId = b.toParticipantId;
        this.paillierNHex = b.paillierNHex;
        this.paillierGHex = b.paillierGHex;
        this.encryptedShareHex = b.encryptedShareHex;
        this.zkProofHex = b.zkProofHex;
        this.publicKeyShareHex = b.publicKeyShareHex;
    }

    public int getRound() { return round; }
    public String getFromParticipantId() { return fromParticipantId; }
    public String getToParticipantId() { return toParticipantId; }
    public String getPaillierNHex() { return paillierNHex; }
    public String getPaillierGHex() { return paillierGHex; }
    public String getEncryptedShareHex() { return encryptedShareHex; }
    public String getZkProofHex() { return zkProofHex; }
    public String getPublicKeyShareHex() { return publicKeyShareHex; }

    /**
     * 序列化为简单分隔符格式（payloadHex 内容）。
     * 格式：{@code round|from|to|paillierN|paillierG|encShare|zkProof|pubKeyShare}
     * 字段为 null 时用空字符串。未来切换 protobuf 时替换此方法。
     *
     * @return 编码字符串
     */
    public String toPayloadHex() {
        return join(round, fromParticipantId, toParticipantId,
                paillierNHex, paillierGHex, encryptedShareHex, zkProofHex, publicKeyShareHex);
    }

    /**
     * 从 payloadHex 反序列化。
     *
     * @param payload payloadHex
     * @return 消息实例
     */
    public static KeyGenRoundMessage fromPayloadHex(String payload) {
        String[] parts = payload.split("\\|", -1);
        Builder b = new Builder();
        b.round = Integer.parseInt(parts[0]);
        b.fromParticipantId = emptyToNull(parts[1]);
        b.toParticipantId = emptyToNull(parts[2]);
        b.paillierNHex = emptyToNull(parts[3]);
        b.paillierGHex = emptyToNull(parts[4]);
        b.encryptedShareHex = emptyToNull(parts[5]);
        b.zkProofHex = emptyToNull(parts[6]);
        b.publicKeyShareHex = emptyToNull(parts[7]);
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
        private String paillierNHex;
        private String paillierGHex;
        private String encryptedShareHex;
        private String zkProofHex;
        private String publicKeyShareHex;

        public Builder round(int round) { this.round = round; return this; }
        public Builder from(String id) { this.fromParticipantId = id; return this; }
        public Builder to(String id) { this.toParticipantId = id; return this; }
        public Builder paillierN(String hex) { this.paillierNHex = hex; return this; }
        public Builder paillierG(String hex) { this.paillierGHex = hex; return this; }
        public Builder encryptedShare(String hex) { this.encryptedShareHex = hex; return this; }
        public Builder zkProof(String hex) { this.zkProofHex = hex; return this; }
        public Builder publicKeyShare(String hex) { this.publicKeyShareHex = hex; return this; }

        public KeyGenRoundMessage build() { return new KeyGenRoundMessage(this); }
    }
}