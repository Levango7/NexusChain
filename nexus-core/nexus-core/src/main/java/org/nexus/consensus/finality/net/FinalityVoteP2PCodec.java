package org.nexus.consensus.finality.net;

/**
 * 最终性投票 P2P 载荷标识与编解码（ADR-030 M_net，真实 gRPC 通道复用版）。
 *
 * <p>设计约束：P2P `NexusChainOuterClass` 生成代码为手工维护（1.6 万行），
 * 无 protoc 工具链环境下新增消息枚举风险过高。本方案复用现有 {@code Proposal}
 * 消息通道的 {@code payload} bytes 字段（当前未被消费，见 SyncManager.onProposal），
 * 用魔数前缀区分投票消息与正常块提案：</p>
 *
 * <pre>
 *   payload[0] == MAGIC  →  FinalityVoteCodec.decode(payload)
 *   payload[0] != MAGIC || payload.length < 2  →  正常块提案处理（透传原逻辑）
 * </pre>
 *
 * <p>后续 protoc 就位后可替换为独立枚举，此处冻结语义约定。</p>
 */
public final class FinalityVoteP2PCodec {

    /** 投票消息魔数前缀（0x5A = 'Z' for "Zero-confirmation finality"） */
    public static final byte MAGIC = FinalityVoteCodec.MAGIC;

    private FinalityVoteP2PCodec() {}

    /**
     * 判断 P2P 载荷是否为最终性投票（魔数探测）。
     *
     * @param payload Proposal payload bytes（可 null）
     * @return true 表示该载荷应路由至 FinalityVoteBroadcaster 而非正常块处理
     */
    public static boolean isVotePayload(byte[] payload) {
        return payload != null && payload.length > 0 && payload[0] == MAGIC;
    }

    /**
     * 从 P2P Proposal 载荷提取并解码投票。
     *
     * @param payload Proposal payload bytes
     * @return 投票对象；非投票载荷或畸形数据返回 null
     */
    public static org.nexus.consensus.finality.Vote decode(byte[] payload) {
        if (!isVotePayload(payload)) return null;
        return FinalityVoteCodec.decode(payload);
    }
}
