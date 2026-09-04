package org.nexus.signing.mpc.cggmp;

import java.util.Objects;

/**
 * CGGMP21 协议中转消息 DTO（G 批）。
 *
 * <p>与 protobuf {@code MpcCryptoProto.CgRelayMessage} 对应：</p>
 * <ul>
 *   <li>{@code sessionId} — 会话 ID（跨所有参与方一致）</li>
 *   <li>{@code senderIndex} — 0-based 发送方索引</li>
 *   <li>{@code receiverIndex} — 0-based 目标方索引（仅 isP2P=true 时有效）</li>
 *   <li>{@code payloadJson} — cggmp21 协议消息的 serde JSON</li>
 *   <li>{@code isP2P} — true=定向（receiverIndex 有效）；false=广播</li>
 * </ul>
 *
 * <p>F 批修正：CGGMP21 PartyIndex 0-based — 目标方 0 与"广播"哨兵 0
 * 冲突，需要 is_p2p 字段消歧。</p>
 */
public final class CgRelayMessageDto {

    private final String sessionId;
    private final int senderIndex;
    private final int receiverIndex;
    private final String payloadJson;
    private final boolean isP2P;

    public CgRelayMessageDto(
            String sessionId,
            int senderIndex,
            int receiverIndex,
            String payloadJson,
            boolean isP2P) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.senderIndex = senderIndex;
        this.receiverIndex = receiverIndex;
        this.payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
        this.isP2P = isP2P;
    }

    public String getSessionId() { return sessionId; }
    public int getSenderIndex() { return senderIndex; }
    public int getReceiverIndex() { return receiverIndex; }
    public String getPayloadJson() { return payloadJson; }
    public boolean isP2P() { return isP2P; }

    @Override
    public String toString() {
        return "CgRelayMessageDto{"
                + "sessionId='" + sessionId + '\''
                + ", senderIndex=" + senderIndex
                + ", receiverIndex=" + receiverIndex
                + ", isP2P=" + isP2P
                + ", payloadJson.len=" + (payloadJson == null ? 0 : payloadJson.length())
                + '}';
    }
}
