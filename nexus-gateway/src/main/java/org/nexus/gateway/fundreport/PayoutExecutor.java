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
     */
    String pay(Long merchantId, MerchantPayoutAccount account, BigDecimal amount, String reference);

    /** 执行器名称（用于配置选择与日志）。 */
    String name();
}
