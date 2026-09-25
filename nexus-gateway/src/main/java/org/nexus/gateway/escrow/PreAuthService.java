package org.nexus.gateway.escrow;

import java.math.BigDecimal;

/**
 * 预授权服务接口。
 *
 * <p>预授权完整流程：</p>
 * <ol>
 *   <li>{@link #createPreAuth} — 创建预授权，冻结金额（AUTHORIZED）</li>
 *   <li>{@link #capturePreAuth} — 扣款（AUTHORIZED→CAPTURED），支持部分扣款</li>
 *   <li>{@link #voidPreAuth} — 撤销预授权（AUTHORIZED→VOIDED），释放全部冻结金额</li>
 * </ol>
 */
public interface PreAuthService {

    /**
     * 创建预授权 — 冻结指定金额。
     *
     * <p>状态流转：→ AUTHORIZED。调用 AccountService.freeze() 冻结金额。</p>
     *
     * @param merchantId 商户 ID
     * @param orderId 关联订单 ID（可选）
     * @param freezeAmount 冻结金额
     * @param autoReleaseDays 超时自动释放天数（默认 3）
     * @return 创建的预授权交易
     */
    PreAuthTransaction createPreAuth(Long merchantId, Long orderId, BigDecimal freezeAmount,
                                      Integer autoReleaseDays);

    /**
     * 扣款 — 将冻结金额转为实际扣款转入商户余额。
     *
     * <p>状态流转：AUTHORIZED → CAPTURED。调用 AccountService.unfreeze() 解冻金额，
     * 再调用 AccountService.deposit() 将扣款金额转入商户余额。
     * 剩余金额（freezeAmount - captureAmount）释放回原账户。
     * 校验 captureAmount ≤ freezeAmount。</p>
     *
     * @param preauthNo 预授权编号
     * @param captureAmount 扣款金额（必须 ≤ freezeAmount）
     * @return 更新后的预授权交易
     */
    PreAuthTransaction capturePreAuth(String preauthNo, BigDecimal captureAmount);

    /**
     * 撤销预授权 — 释放全部冻结金额。
     *
     * <p>状态流转：AUTHORIZED → VOIDED。调用 AccountService.unfreeze() 释放全部冻结金额。</p>
     *
     * @param preauthNo 预授权编号
     * @return 更新后的预授权交易
     */
    PreAuthTransaction voidPreAuth(String preauthNo);

    /**
     * 查询预授权状态。
     *
     * @param orderId 关联订单 ID
     * @return 预授权交易（不存在返回 null）
     */
    PreAuthTransaction getPreAuthStatus(Long orderId);
}