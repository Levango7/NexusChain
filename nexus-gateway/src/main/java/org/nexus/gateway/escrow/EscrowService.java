package org.nexus.gateway.escrow;

import java.math.BigDecimal;

/**
 * 担保交易服务接口。
 *
 * <p>担保交易完整流程：</p>
 * <ol>
 *   <li>{@link #createEscrow} — 创建担保交易（CREATED）</li>
 *   <li>{@link #fundEscrow} — 买家付款，资金冻结到担保账户（CREATED→FUNDED）</li>
 *   <li>{@link #confirmEscrow} — 确认收货，资金释放给商户（FUNDED→CONFIRMED→RELEASED）</li>
 *   <li>{@link #refundEscrow} — 退款，资金退回买家（FUNDED→REFUNDED）</li>
 * </ol>
 */
public interface EscrowService {

    /**
     * 创建担保交易。
     *
     * @param merchantId 商户 ID
     * @param orderId 关联订单 ID
     * @param amount 担保金额
     * @param buyerAddress 买家钱包地址
     * @param autoConfirmDays 超时自动确认天数（默认 7）
     * @return 创建的担保交易
     */
    EscrowTransaction createEscrow(Long merchantId, Long orderId, BigDecimal amount,
                                    String buyerAddress, Integer autoConfirmDays);

    /**
     * 买家付款 — 资金冻结到担保账户。
     *
     * <p>状态流转：CREATED → FUNDED。调用 AccountService.freeze() 冻结买家资金。</p>
     *
     * @param escrowNo 担保交易编号
     * @param payerAddress 付款人钱包地址
     * @return 更新后的担保交易
     */
    EscrowTransaction fundEscrow(String escrowNo, String payerAddress);

    /**
     * 确认收货 — 资金释放给商户。
     *
     * <p>状态流转：FUNDED → CONFIRMED → RELEASED。调用 AccountService.unfreeze() 解冻资金，
     * 再调用 AccountService.deposit() 将资金转入商户余额账户。</p>
     *
     * @param escrowNo 担保交易编号
     * @return 更新后的担保交易
     */
    EscrowTransaction confirmEscrow(String escrowNo);

    /**
     * 退款 — 资金退回买家。
     *
     * <p>状态流转：FUNDED → REFUNDED。调用 AccountService.unfreeze() 解冻资金退回买家。</p>
     *
     * @param escrowNo 担保交易编号
     * @return 更新后的担保交易
     */
    EscrowTransaction refundEscrow(String escrowNo);

    /**
     * 查询担保交易状态。
     *
     * @param orderId 关联订单 ID
     * @return 担保交易（不存在返回 null）
     */
    EscrowTransaction getEscrowStatus(Long orderId);
}