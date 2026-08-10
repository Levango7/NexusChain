package org.nexus.sdk.wallet;

/**
 * 钱包层级枚举（热/温/冷托管模型），跨服务共享 DTO。
 *
 * <p>原位于 {@code org.nexus.wallet.wallet.custody.WalletTier}（nexus-exchange-wallet），
 * 在 Phase 1 微服务化中迁移至 nexus-sdk 共享层（新包 {@code org.nexus.sdk.wallet}），
 * 供 nexus-wallet-service 对外暴露、nexus-signing-service 在 MPC 阈值判定中引用。</p>
 *
 * <ul>
 *   <li>{@link #HOT} — 在线热钱包，即时提现，仅持小部分资金。</li>
 *   <li>{@link #WARM} — 半在线温钱包，移动资金需有限审批。</li>
 *   <li>{@link #COLD} — 离线/气隙冷钱包，持大部分资金，需完整多签审批。</li>
 * </ul>
 */
public enum WalletTier {

    /** 在线热钱包，即时提现，仅持小部分资金。 */
    HOT,

    /** 半在线温钱包，移动资金需有限审批。 */
    WARM,

    /** 离线/气隙冷钱包，持大部分资金，需完整多签审批。 */
    COLD
}