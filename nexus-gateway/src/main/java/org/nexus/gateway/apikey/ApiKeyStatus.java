package org.nexus.gateway.apikey;

/**
 * API Key 状态枚举。
 *
 * <p>生命周期流转：</p>
 * <ul>
 *   <li>{@code ACTIVE} → {@code ROTATED}：通过 rotateApiKey 轮换，旧 Key 标记 ROTATED</li>
 *   <li>{@code ACTIVE} → {@code REVOKED}：通过 revokeApiKey 手动撤销</li>
 *   <li>{@code ACTIVE} → {@code EXPIRED}：超过 expireAt 自动过期（验证时判定）</li>
 *   <li>{@code ROTATED} / {@code REVOKED} / {@code EXPIRED}：终态，不可恢复</li>
 * </ul>
 */
public enum ApiKeyStatus {

    /** 正常状态：可验证、可使用。 */
    ACTIVE,

    /** 已过期：超过 expireAt，验证时自动判定。 */
    EXPIRED,

    /** 已撤销：手动撤销，不可恢复。 */
    REVOKED,

    /** 已轮换：被新 Key 替代，不可恢复。 */
    ROTATED
}