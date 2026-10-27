package org.nexus.gateway.fundreport;

import java.math.BigDecimal;

/**
 * 出款执行器（P0 修复 2026-10-11）。
 *
 * <p><b>为什么需要接口</b>：网关"自动提现"此前只扣商户账本余额、没有任何出款动作
 * （不调 wallet-service、不发链上交易）——"余额被扣但钱没离开平台"。
 * 出款执行（人工打款 / 银行代付 / 渠道转账）各环境差异极大，必须可插拔。</p>
 *
 * <h2>实现形态（按成熟度递增）</h2>
 * <ol>
 *   <li><b>人工打款（默认）</b>：只创建"应付台账 + 待打款任务"，运营线下用
 *       <b>持牌交付方对公账户</b>打款后回填流水号核销——沙箱与私有化早期的最稳形态；</li>
 *   <li>银行代付接口（持牌主体对公账户 + 代付 API）；</li>
 *   <li>渠道转账接口（企业支付宝/微信商家转账，需资质）。</li>
 * </ol>
 *
 * <p><b>红线</b>：实现方不得使用<b>平台方个人账户</b>作为出款通道——那属于无牌资金
 * 归集与再清算（"二清"）。出款去向统一由 {@link MerchantPayoutAccount} 约束。</p>
 */
public interface PayoutExecutor {

    /**
     * 执行一笔出款（内部调账）。
     *
     * @param merchantId 商户 ID
     * @param account    生效的出款账户（商户自己绑定的结算账户）
     * @param amount     出款金额
     * @param reference  幂等引用（调用方派生）
     * @return 出款凭据号（渠道退款单号 / 银行流水号 / 人工核销单号）
     * @throws PayoutException 出款失败（调用方据此补偿：退回商户余额）
     *
     * <p><b>⚠️ 契约（2026-10-21 明确，2026-10-27 升级为类型级）</b>：抛异常 ⇔
     * <b>确定未提交出款</b>。若调用已提交到渠道/链上但结果未知（超时、响应丢失、待确认），
     * <b>不得抛异常</b>——必须通过 {@link #payWithOutcome} 返回
     * {@link PayoutOutcome#unverified(String)}，让调用方走人工核对，
     * 而不是走"退回余额 + 清认领 + 下周期重试"。</p>
     *
     * <p>原因：{@code AutoWithdrawService} 在捕获异常时执行
     * <b>退回商户余额 + 清空认领</b>，规则下周期可重试。若出款其实已发生，
     * 该补偿会导致<b>二次打款</b>。当前唯一的实现 {@code ManualPayoutExecutor}
     * 是进程内台账、不触渠道，故契约"碰巧"成立；一旦接入银行代付/渠道转账，
     * 任何一次"已提交但响应丢失"都会造成真实资金损失。</p>
     */
    String pay(Long merchantId, MerchantPayoutAccount account, BigDecimal amount, String reference);

    /**
     * 出款（结果敏感形态，P0 修复 2026-10-27）：返回可区分「成功」与「结果未知」的出款结果。
     *
     * <p>调用方 {@code AutoWithdrawService} <b>只走本方法</b>；默认实现把 {@link #pay}
     * 的返回视为「确定成功」，把抛出的 {@link PayoutException} 原样放行——
     * 对进程内台账类实现（如 {@code ManualPayoutExecutor}）语义完全等价，
     * 既有实现无需改动。</p>
     *
     * <p><b>会触达外部渠道的实现必须覆写本方法</b>：把"请求已发出但应答不确定"
     * 返回为 {@link PayoutOutcome#unverified(String)}；调用方将保留出款认领
     * （fail-closed 拒绝重跑）、绝不退余额、绝不清认领，交人工核对。</p>
     */
    default PayoutOutcome payWithOutcome(Long merchantId, MerchantPayoutAccount account,
                                         BigDecimal amount, String reference) {
        return PayoutOutcome.executed(pay(merchantId, account, amount, reference));
    }

    /** 执行器名称（用于配置选择与日志）。 */
    String name();
}
