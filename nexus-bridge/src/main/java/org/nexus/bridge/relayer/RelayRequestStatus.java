package org.nexus.bridge.relayer;

import java.math.BigDecimal;

/**
 * 中继请求状态枚举。
 *
 * @since 1.2
 */
public enum RelayRequestStatus {
    /** 待分配 relayer */
    PENDING,
    /** 已分配，中继中 */
    RELAYING,
    /** 已完成并验证 */
    COMPLETED,
    /** 失败 */
    FAILED
}