package org.nexus.gateway.security.threeds;

/**
 * 3DS 交易状态枚举（EMVCo 3DS 2.0 规范 transStatus 字段）。
 *
 * <p>对应 {@code three_ds_auth_records.trans_status} 字段，
 * 表示 ACS 返回的认证决策结果。</p>
 */
public enum TransStatus {
    /** Y — Frictionless 认证成功（Authenticated）。 */
    Y("Authenticated"),
    /** C — 需要 Challenge 验证（Challenge Required）。 */
    C("Challenge Required"),
    /** N — 未认证（Not Authenticated）。 */
    N("Not Authenticated"),
    /** R — 被拒绝（Rejected）。 */
    R("Rejected"),
    /** U — 无法完成认证（Unable）。 */
    U("Unable");

    private final String description;

    TransStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}