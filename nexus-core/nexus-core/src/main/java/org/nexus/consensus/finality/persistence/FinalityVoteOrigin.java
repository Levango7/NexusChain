package org.nexus.consensus.finality.persistence;

/**
 * 待补写投票的来源（PLAN-018）。
 *
 * <p>区分两��语义完全不同的待写票：</p>
 * <ul>
 *   <li>{@link #LOCAL}：本节点自己签出来的票（发送侧）。写库失败时**本节点**负责重试，
 *       成功后还要补发一次 P2P 广播，让对端也能计到这一票。</li>
 *   <li>{@link #RECEIVED}：从对端收到的票（接收侧）。写库失败时**接收方自己**负责重试
 *       —— 不再依赖发送方重发（PLAN-016 遗留的缺口：发送方只在"自己写库失败"时才会重发，
 *       对方库故障时没有任何一方会重投，远端票静默蒸发）。</li>
 * </ul>
 *
 * <p>来源随行持久化在 outbox 表的 {@code origin} 列上，用于恢复后区分重试路径。</p>
 */
public enum FinalityVoteOrigin {

    /** 本节点签发的本地票。 */
    LOCAL("local"),

    /** 从对端收到的远端票。 */
    RECEIVED("received");

    private final String code;

    FinalityVoteOrigin(String code) {
        this.code = code;
    }

    /** 落库用的稳定短码。 */
    public String code() {
        return code;
    }

    /**
     * 由落库短码还原枚举；未知短码按 {@link #RECEIVED} 处理（远端票是"别人的事实"，
     * 宁可重写也不该被当成"本节点已签发"而在恢复后补发广播）。
     */
    public static FinalityVoteOrigin fromCode(String code) {
        if (code == null) {
            return RECEIVED;
        }
        for (FinalityVoteOrigin origin : values()) {
            if (origin.code.equalsIgnoreCase(code)) {
                return origin;
            }
        }
        return RECEIVED;
    }
}
