package org.nexus.bridge;

/**
 * 桥状态枚举，表示跨链桥的运行状态。
 *
 * <p>桥状态由验证者通过治理操作进行切换，不同状态下允许的操作不同：</p>
 * <ul>
 *   <li>{@link #ACTIVE} — 桥正常运行，所有操作可用</li>
 *   <li>{@link #PAUSED} — 桥暂停，仅允许 {@code BRIDGE_UNLOCK} 退回资产</li>
 *   <li>{@link #EMERGENCY_STOP} — 紧急停止，所有操作禁止</li>
 * </ul>
 *
 * @since 1.0.0
 */
public enum BridgeState {

    /**
     * 桥正常运行状态。
     *
     * <p>在此状态下，LOCK、MINT、BURN、UNLOCK 全部操作可用。
     * 这是桥的默认运行状态。</p>
     */
    ACTIVE,

    /**
     * 桥暂停状态。
     *
     * <p>在此状态下，仅允许 {@code BRIDGE_UNLOCK} 操作，以确保用户资产
     * 能够安全退回原链。LOCK 和 MINT 操作将被拒绝。</p>
     *
     * <p>任何验证者均可触发暂停，恢复到 ACTIVE 需要达到多签阈值。</p>
     */
    PAUSED,

    /**
     * 紧急停止状态。
     *
     * <p>在此状态下，所有跨链操作均被禁止。仅在桥检测到
     * 严重安全威胁（如验证者私钥泄露、合约漏洞）时进入此状态。</p>
     *
     * <p>进入 EMERGENCY_STOP 后需要通过 nexus-consortium 治理提案
     * 才能恢复到 ACTIVE。</p>
     */
    EMERGENCY_STOP
}
