package org.nexus.gateway.fundtransfer;

/**
 * 调拨金额类型枚举。
 *
 * <p>定义资金调拨时金额的计算方式：</p>
 * <ul>
 *   <li>{@link #FIXED} — 固定金额：每次调拨固定数额的资金</li>
 *   <li>{@link #PERCENTAGE} — 百分比：按账户余额的百分比调拨</li>
 *   <li>{@link #ALL} — 全部调拨：将转出账户的全部余额调拨到转入账户</li>
 * </ul>
 */
public enum TransferAmountType {
    /** 固定金额 — 每次调拨固定数额 */
    FIXED,
    /** 百分比 — 按账户余额的百分比调拨 */
    PERCENTAGE,
    /** 全部调拨 — 将全部余额调拨 */
    ALL
}