package org.nexus.gateway.alert;

import org.nexus.gateway.event.AccountLinkageFailedEvent;
import org.nexus.gateway.sla.SlaBreachEvent;
import org.nexus.gateway.sla.SlaMeasurement;
import org.nexus.gateway.sla.SlaTarget;
import org.nexus.gateway.subscription.DunningNotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 领域事件 → 告警出口的桥接（2026-11-07 生产就绪度审计修复）。
 *
 * <h3>这个类解决什么问题</h3>
 * <p>审计发现三个"告警/通知"领域事件<b>发布了但全仓零消费者</b>，其发布方注释却都写着
 * "监听器可据此发送…"、"使运维/补偿系统能自动感知并处理"：</p>
 * <ul>
 *   <li>{@link AccountLinkageFailedEvent}（{@code PaymentEventListener:135/:172} 发布）——
 *       支付确认→商户余额入账失败 / 退款→余额扣减失败。发布方是
 *       {@code @Async + @TransactionalEventListener(AFTER_COMMIT)}，即失败发生在事务提交后
 *       <b>无法回滚订单</b>：用户已付款、订单已确认、商户余额没加，此前只剩一条 {@code log.error}。</li>
 *   <li>{@link DunningNotificationEvent}（{@code DunningManager:86} 发布）——催收动作，
 *       此前<b>催收通知从未发出</b>。</li>
 *   <li>{@link SlaBreachEvent}（{@code SlaMonitorService:68} 发布）——SLA 未达标，
 *       此前<b>无接收方</b>。</li>
 * </ul>
 *
 * <h3>设计取舍</h3>
 * <ul>
 *   <li><b>复用既有出口，不改动 {@link AlertEngine}</b>：直接用同一个持久化实体
 *       {@link AlertEvent} + 同一组 {@link AlertNotifier} 扇出，因此这些告警会
 *       自动出现在 {@code AlertController} 的查询/解决接口里，也能被
 *       {@code AlertEscalationService} 的升级规则命中（按 {@code ruleName} 匹配）。
 *       本类<b>不</b>参与阈值规则评估，避免与 {@link AlertEngine} 的冷却/聚合语义纠缠。</li>
 *   <li><b>离散事实用计数语义</b>：{@link AlertEvent} 的 {@code currentValue}/{@code threshold}
 *       是非空数值（为阈值规则设计）。领域事件是"发生了一次"的离散事实，故统一取
 *       {@code currentValue = 1}、{@code threshold = 0}，让"发生过"在数值面上自洽。</li>
 *   <li><b>告警是旁路，绝不能打断资金流程</b>：{@code ClearingSettlementListener} 这类发布方
 *       是<b>同步</b>监听且在事务内，若本类抛异常会污染其事务。故每个处理方法整体 try/catch，
 *       内部失败只记日志（不静默：ERROR 级 + 明确前缀）。</li>
 *   <li><b>同步监听</b>：发布方该异步的已经异步了，这里再叠一层 {@code @Async} 只会增加线程池
 *       压力并让"是否已落库"变得不可判定。</li>
 * </ul>
 *
 * <p>{@code ruleName} 统一加 {@code domain-} 前缀，便于与 {@code alert_rules} 表里的阈值规则
 * 区分；运维若要为其中某类配升级规则，直接在 {@code alert_escalation_rules} 里按同名登记即可。</p>
 */
@Component
public class DomainAlertEventSink {

    private static final Logger log = LoggerFactory.getLogger(DomainAlertEventSink.class);

    /** 告警消息长度上限（{@link AlertEvent#setMessage} 的列为 512）。 */
    private static final int MESSAGE_MAX_LENGTH = 512;

    /** 账户联动失败（资金账务不一致）。 */
    public static final String RULE_ACCOUNT_LINKAGE_FAILED = "domain-account-linkage-failed";
    /** 订阅催收动作。 */
    public static final String RULE_DUNNING = "domain-dunning-notification";
    /** SLA 未达标。 */
    public static final String RULE_SLA_BREACH = "domain-sla-breach";

    private final AlertEventRepository eventRepository;
    private final List<AlertNotifier> notifiers;

    public DomainAlertEventSink(AlertEventRepository eventRepository, List<AlertNotifier> notifiers) {
        this.eventRepository = eventRepository;
        this.notifiers = notifiers;
    }

    /**
     * 支付确认→余额入账失败 / 退款→余额扣减失败。
     *
     * <p>严重度固定 CRITICAL：账务不一致必须人工核对，且此时订单已提交无法回滚。</p>
     */
    @EventListener
    public void onAccountLinkageFailed(AccountLinkageFailedEvent event) {
        emit(RULE_ACCOUNT_LINKAGE_FAILED,
                "account.linkage.failed",
                AlertRule.Severity.CRITICAL,
                String.format("账户联动失败[%s] merchantId=%s ref=%s amount=%s reason=%s",
                        event.getLinkageType(),
                        event.getMerchantId(),
                        event.getBusinessReference(),
                        event.getAmount(),
                        event.getFailureReason()),
                event);
    }

    /**
     * 订阅催收动作：挂起为 CRITICAL，其余（通知/重试）为 WARN。
     */
    @EventListener
    public void onDunningNotification(DunningNotificationEvent event) {
        AlertRule.Severity severity =
                event.getAction() == DunningNotificationEvent.Action.SUSPEND
                        ? AlertRule.Severity.CRITICAL
                        : AlertRule.Severity.WARN;
        emit(RULE_DUNNING,
                "subscription.dunning",
                severity,
                String.format("订阅催收动作[%s] subscriptionId=%s attempt=%d nextRetryAt=%s",
                        event.getAction(),
                        event.getSubscriptionId(),
                        event.getAttemptCount(),
                        event.getNextRetryAt()),
                event);
    }

    /**
     * SLA 未达标。
     */
    @EventListener
    public void onSlaBreach(SlaBreachEvent event) {
        SlaTarget slaTarget = event.getSlaTarget();
        SlaMeasurement slaMeasurement = event.getSlaMeasurement();

        String targetName = slaTarget == null ? "?" : slaTarget.getName();
        String metricName = slaTarget == null ? "sla.breach" : slaTarget.getMetricName();

        // 用局部原始类型 + 显式分支，不用"可空装箱 Double 参与三元再拆箱"的写法。
        // 原写法（`Double target = meas != null ? meas.getTargetValue() : (target != null ? target.getTargetValue() : null)`）
        // 触发 SpotBugs NP_NULL_ON_SOME_PATH（priority=2，CWE-476）——「Constant value null」
        // 被判定可能进入 double 上下文；本仓 SAST 门禁对 findings 零容忍（>0 即红）。
        // 语义不变：有测量值就用测量值，否则退到目标值，都没有则 "?"。
        String measuredText = "?";
        String targetText = "?";
        if (slaMeasurement != null) {
            measuredText = String.format("%.4f", slaMeasurement.getMeasuredValue());
            targetText = String.format("%.4f", slaMeasurement.getTargetValue());
        } else if (slaTarget != null) {
            targetText = String.format("%.4f", slaTarget.getTargetValue());
        }

        emit(RULE_SLA_BREACH,
                metricName,
                AlertRule.Severity.WARN,
                String.format("SLA 未达标[%s] measured=%s target=%s",
                        targetName,
                        measuredText,
                        targetText),
                event);
    }

    /**
     * 落库 + 扇出到全部 {@link AlertNotifier}。
     *
     * <p>整体 try/catch：告警是旁路，任何失败都不得向发布方（可能是资金事务内）传播。</p>
     *
     * @param ruleName  告警规则名（将被 {@code AlertEscalationRule} 按同名匹配）
     * @param metricName 指标名
     * @param severity  严重度
     * @param message   告警文案（超长按 {@value #MESSAGE_MAX_LENGTH} 截断）
     * @param source    源事件（仅用于失败日志定位）
     */
    private void emit(String ruleName, String metricName, AlertRule.Severity severity,
                      String message, Object source) {
        try {
            AlertEvent alert = new AlertEvent();
            alert.setRuleName(ruleName);
            alert.setMetricName(metricName);
            // 离散事实用计数语义：发生 1 次 > 阈值 0（见类注释「设计取舍」）
            alert.setCurrentValue(1.0d);
            alert.setThreshold(0.0d);
            alert.setSeverity(severity);
            alert.setMessage(truncate(message));
            alert.setTimestamp(LocalDateTime.now());

            eventRepository.save(alert);

            for (AlertNotifier notifier : notifiers) {
                try {
                    notifier.notify(alert);
                } catch (Exception e) {
                    // 单个渠道失败不影响其它渠道，也不影响发布方
                    log.error("Sink notifier '{}' failed for alert '{}': {}",
                            notifier.channel(), ruleName, e.getMessage(), e);
                }
            }

            log.info("Domain alert raised: rule={} severity={} message={}", ruleName, severity, alert.getMessage());
        } catch (Exception e) {
            log.error("Failed to raise domain alert '{}' from {}: {}",
                    ruleName, source == null ? "?" : source.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, MESSAGE_MAX_LENGTH);
    }
}
