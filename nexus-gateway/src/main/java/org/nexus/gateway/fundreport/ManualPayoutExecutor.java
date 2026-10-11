package org.nexus.gateway.fundreport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 人工打款执行器（PayoutExecutor 的默认实现，P0 修复 2026-10-11）。
 *
 * <p><b>形态</b>：不发起任何资金转移，只把"应付 + 待打款"登记成台账任务，
 * 由运营线下用<b>持牌交付方对公账户</b>打款，回填流水号后核销。
 * 这是沙箱与私有化早期最稳的出款形态（不形成无牌资金归集）。</p>
 *
 * <p><b>幂等</b>：同一 {@code reference} 重复调用返回同一凭据号（不重复登记任务）。</p>
 *
 * <p><b>为什么默认是它</b>：真正把"只扣账不出款"的缺口补成"可见、可核销、可追责"。
 * 替换为代付/渠道执行器只需提供新的 {@link PayoutExecutor} bean（按 {@link #name()} 选择）。</p>
 */
@Component
@ConditionalOnMissingBean(name = "payoutExecutor")
public class ManualPayoutExecutor implements PayoutExecutor {

    private static final Logger log = LoggerFactory.getLogger(ManualPayoutExecutor.class);

    /** 已登记的待打款任务（reference → 凭据号）；进程内注册表，重启可由台账重放。 */
    private final java.util.concurrent.ConcurrentHashMap<String, String> tasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String name() {
        return "manual";
    }

    @Override
    public synchronized String pay(Long merchantId, MerchantPayoutAccount account,
                                    BigDecimal amount, String reference) {
        String existing = tasks.get(reference);
        if (existing != null) {
            log.info("[ManualPayout] 幂等命中，返回既有凭据号: reference={}, ticket={}", reference, existing);
            return existing;
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayoutException("出款金额非法: amount=" + amount);
        }
        if (account == null || !account.isActive()) {
            throw new PayoutException("出款账户缺失或未生效（fail-closed，不出款）");
        }

        String ticket = "MANUAL_PAYOUT_" + reference;
        tasks.put(reference, ticket);
        log.info("[ManualPayout] 登记待打款任务: merchantId={}, accountType={}, amount={}, "
                        + "reference={}, ticket={} —— 请运营线下打款后回填流水号核销",
                merchantId, account.getAccountType(), amount, reference, ticket);
        return ticket;
    }
}
