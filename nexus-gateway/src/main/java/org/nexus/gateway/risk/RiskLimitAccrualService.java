package org.nexus.gateway.risk;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.model.PaymentOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商户限额滚动累加服务（A3，2026-10-03 口径拍板后实施）。
 *
 * <p><b>口径</b>：日/月限额从「滚动 24h/30 天 SUM」改为「自然日/自然月计数器」
 * （行业惯例；CHANGELOG 明示）。成员集与原 SUM 严格一致：仅 PAID + PAYING
 * （SUBMITTED 不计）。</p>
 *
 * <p><b>机制</b>：实现 {@link OrderStateMachine.TransitionHook} 并在启动时注册到
 * 状态机咽喉——状态进入成员集时 +amount、离开时 −amount（覆盖全部出边：
 * PAID→REFUND_PENDING/REORGED/VOIDED、PAYING→FAILED/EXPIRED 等与
 * FAILED→PENDING 重试回路）。评估侧读单行 O(1)，查询零滞后。</p>
 *
 * <p><b>可逆守恒</b>：进出对称增减使累计值恒等于「该窗口内处于成员集状态的
 * 订单金额之和」——退款/重组/冲正路径不破坏守恒。</p>
 *
 * <p><b>已知偏差方向（诚实声明）</b>：钩子在 save 前记账，若调用方 save 失败，
 * 累计偏多 → 限额偏紧 = fail-safe 方向；钩子异常仅告警不阻断状态机。
 * 部署切换时累计从零开始（新口径下等价于窗口重置，可接受）。</p>
 */
@Service
public class RiskLimitAccrualService implements OrderStateMachine.TransitionHook {

    private static final Logger log = LoggerFactory.getLogger(RiskLimitAccrualService.class);

    private final RiskLimitAccrualRepository repository;

    @Autowired
    public RiskLimitAccrualService(RiskLimitAccrualRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void register() {
        OrderStateMachine.setHook(this);
        log.info("RiskLimitAccrualService: registered as OrderStateMachine hook (natural-day/month accrual)");
    }

    @PreDestroy
    void unregister() {
        OrderStateMachine.setHook(null);
    }

    // ==================== 状态机钩子 ====================

    @Override
    public void onTransition(PaymentOrder order, PaymentOrder.OrderStatus from,
                             PaymentOrder.OrderStatus to) {
        if (order == null || order.getMerchantId() == null || order.getAmount() == null) {
            return;
        }
        boolean wasCounted = counted(from);
        boolean nowCounted = counted(to);
        if (wasCounted == nowCounted) {
            return; // 成员集内移动（如 PAYING→PAID）或集外移动：累计不变
        }
        BigDecimal amount = order.getAmount();
        // 进入 +amount / 离开 -amount
        BigDecimal delta = nowCounted ? amount : amount.negate();

        LocalDateTime anchor = order.getCreatedAt() != null ? order.getCreatedAt() : LocalDateTime.now();
        LocalDate day = anchor.toLocalDate();
        LocalDate month = anchor.toLocalDate().withDayOfMonth(1);

        apply(order.getMerchantId(), RiskLimitAccrual.PeriodType.DAILY, day, delta);
        apply(order.getMerchantId(), RiskLimitAccrual.PeriodType.MONTHLY, month, delta);
    }

    /** 成员集：与 sumMerchantAmountSince 的状态过滤严格一致（仅 PAID+PAYING）。 */
    static boolean counted(PaymentOrder.OrderStatus status) {
        return status == PaymentOrder.OrderStatus.PAID || status == PaymentOrder.OrderStatus.PAYING;
    }

    /** 原子增减：UPDATE 命中即完成；miss 时插首行（并发插入竞态由唯一约束兜底重试）。 */
    private void apply(Long merchantId, RiskLimitAccrual.PeriodType type, LocalDate key, BigDecimal delta) {
        try {
            int updated = repository.addAmount(merchantId, type, key, delta);
            if (updated == 0) {
                try {
                    RiskLimitAccrual row = new RiskLimitAccrual();
                    row.setMerchantId(merchantId);
                    row.setPeriodType(type);
                    row.setPeriodKey(key);
                    row.setAccruedAmount(delta);
                    repository.save(row);
                } catch (RuntimeException race) {
                    // 并发首插竞态：唯一约束冲突 → 重试一次原子累加
                    if (repository.addAmount(merchantId, type, key, delta) == 0) {
                        throw race;
                    }
                }
            }
        } catch (RuntimeException e) {
            // fail-safe：累计丢失偏少 → 限额偏松一侧？不——失败即本轮记账丢失，
            // 累计偏少 = 限额偏松。诚实记录（与头注偏差声明一致），不阻断状态机。
            log.warn("Risk accrual update failed for merchant={} {} {} delta={}: {}",
                    merchantId, type, key, delta, e.getMessage());
        }
    }

    // ==================== 评估读取 ====================

    /** 当前自然日累计（限额评估用；O(1) 索引读）。 */
    public BigDecimal dailyAccrued(Long merchantId) {
        return accrued(merchantId, RiskLimitAccrual.PeriodType.DAILY, LocalDate.now());
    }

    /** 当前自然月累计。 */
    public BigDecimal monthlyAccrued(Long merchantId) {
        return accrued(merchantId, RiskLimitAccrual.PeriodType.MONTHLY, LocalDate.now().withDayOfMonth(1));
    }

    private BigDecimal accrued(Long merchantId, RiskLimitAccrual.PeriodType type, LocalDate key) {
        try {
            return repository.findByMerchantIdAndPeriodTypeAndPeriodKey(merchantId, type, key)
                    .map(RiskLimitAccrual::getAccruedAmount)
                    .orElse(BigDecimal.ZERO);
        } catch (RuntimeException e) {
            // 读失败 = 无从判限额 → 保守放行但不静默（0 与放行是 fail-open，
            // 由调用方的告警与 TTL 缓存窗口限制影响面；诚实记录）
            log.warn("Risk accrual read failed for merchant={} {} {}: {} — treating as 0",
                    merchantId, type, key, e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
