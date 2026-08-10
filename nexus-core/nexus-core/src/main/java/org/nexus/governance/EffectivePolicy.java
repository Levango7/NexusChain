package org.nexus.governance;

/**
 * 参数变更生效策略枚举。
 *
 * <p>描述一次参数变更在通过治理流程后于何时生效：</p>
 * <ul>
 *   <li>{@link #IMMEDIATE} — 立即生效（仅限 LOW 敏感度且无状态破坏的参数）</li>
 *   <li>{@link #NEXT_PROPOSAL} — 下一个提案周期生效</li>
 *   <li>{@link #NEXT_BATCH} — 下一批 L2 批次生效</li>
 *   <li>{@link #NEXT_BLOCK} — 下一区块生效</li>
 *   <li>{@link #NEXT_EPOCH} — 下一个共识 Epoch 生效</li>
 * </ul>
 *
 * @since 1.3
 */
public enum EffectivePolicy {
    /** 立即生效 */
    IMMEDIATE,
    /** 下一提案周期生效 */
    NEXT_PROPOSAL,
    /** 下一 L2 批次生效 */
    NEXT_BATCH,
    /** 下一区块生效 */
    NEXT_BLOCK,
    /** 下一 Epoch 生效 */
    NEXT_EPOCH
}