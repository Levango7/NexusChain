package org.nexus.gateway.security.threeds;

import org.nexus.gateway.security.audit.SecurityAuditService;
import org.nexus.gateway.security.audit.SecurityMetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 3DS 认证完成事件的**占位观察者**（#34）。
 *
 * <p>设计文档（Wave 12 §2.2.5）要求认证完成事件驱动风控联动，但全仓没有任何风控消费者：
 * {@link ChallengeTimeoutScheduler} 与 {@link ThreeDsService} 照常 {@code publishEvent}，
 * 而 Spring 在无监听者时是静默 no-op ⇒ "联动风控系统"这句话一直领先于代码，且从日志上看不出来。</p>
 *
 * <p>本观察者只把这件空转变可见 + 留下可查记录，不改资金与风控语义：</p>
 * <ol>
 *   <li>计数：每笔事件计入 {@code nexus_3ds_auth_total}——该计数器在
 *       {@link SecurityMetricsRecorder} 里注册后从来没人喂过，此前恒为 0；</li>
 *   <li>延迟：端到端耗时计入 {@code nexus_3ds_auth_latency_seconds}。口径是
 *       <b>采不到就跳过，绝不记 0</b>——{@code completed_at} 这一列在
 *       {@code ThreeDsService.updateAuthRecord} 里是"上游给了才写"的，所以"完成时刻缺失"
 *       是真实形态；把缺失写成 0 秒会让 p50/p95 假装存在（详见
 *       {@link #recordAuthLatency(ThreeDsAuthCompletedEvent)}）；</li>
 *   <li>落库：每笔事件经 {@link SecurityAuditService} 写一行 {@code audit_events}
 *       （{@code action=SECURITY_THREE_DS_AUTH}），使"这笔认证被观测到过"可查询——
 *       量级与 payments/refunds 既有审计同级，不新增写放大类别；</li>
 *   <li>告警：<b>每个 JVM 只打一条</b>带 {@value #MARKER} 标记的 WARN（事件量 = 认证量，
 *       逐条 WARN 等于按交易量刷屏），其余事件降级为 INFO 逐条留字段。</li>
 * </ol>
 *
 * <p>真正的风控动作（按 {@code transStatus}/{@code riskScore} 调整订单风险等级）需要先有裁定：
 * 谁有权改订单风险、改完之后资金路径上发生什么。在那之前宁可留一个<b>看得见</b>的空转，
 * 也不要一个<b>看不见</b>的空转。</p>
 *
 * <p>同步语义：{@code publishEvent} 在发布者线程内联调用本方法，而
 * {@link ChallengeTimeoutScheduler#checkChallengeTimeout()} 的扫描跑在 {@code @Transactional} 里
 * ⇒ 本方法抛异常会回滚整个 tick（已写下的 TIMEOUT 终态被撤销）。这里只做计数器递增与日志，
 * 两者都不抛，故不加 try/catch 兜底。</p>
 */
@Component
public class ThreeDsAuthCompletedObserver {

    /**
     * 结构化告警标记 — 供日志/监控精确匹配"3DS 事件没有风控消费者"这一事实。
     */
    public static final String MARKER = "THREE_DS_AUTH_NO_RISK_CONSUMER";

    private static final Logger log = LoggerFactory.getLogger(ThreeDsAuthCompletedObserver.class);

    private final SecurityMetricsRecorder metricsRecorder;
    private final SecurityAuditService securityAuditService;

    /** 每个 JVM 只告警一次；重启后重新出现一条，正好与"该缺口仍未闭合"共存。 */
    private final AtomicBoolean alarmed = new AtomicBoolean(false);

    public ThreeDsAuthCompletedObserver(SecurityMetricsRecorder metricsRecorder,
                                        SecurityAuditService securityAuditService) {
        this.metricsRecorder = metricsRecorder;
        this.securityAuditService = securityAuditService;
    }

    @EventListener
    public void onThreeDsAuthCompleted(ThreeDsAuthCompletedEvent event) {
        metricsRecorder.recordThreeDsAuth();
        recordAuthLatency(event);
        // 每一笔都落一条可查的审计（不是只发一次告警）：事件量 = 认证量，与 payments/refunds
        // 已有的审计写入同一量级。detail 只带状态/风险分/流程类型，不含卡号等敏感材料。
        securityAuditService.recordThreeDsAuth(event.getMerchantId(),
                event.getTransStatus() == null ? "UNKNOWN" : event.getTransStatus().name(),
                event.getPaymentOrderId(),
                "authStatus=" + event.getAuthStatus()
                        + ", riskScore=" + event.getRiskScore()
                        + ", frictionless=" + event.isFrictionlessFlow()
                        + ", challenge=" + event.isChallengeFlow());
        if (alarmed.compareAndSet(false, true)) {
            log.warn("{} 3DS 认证完成事件无风控消费者，仅计数不落风控动作: "
                            + "paymentOrderId={}, merchantId={}, tenantId={}, transStatus={}, authStatus={}, riskScore={}, frictionless={}, challenge={}",
                    MARKER, event.getPaymentOrderId(), event.getMerchantId(), event.getTenantId(),
                    event.getTransStatus(), event.getAuthStatus(), event.getRiskScore(),
                    event.isFrictionlessFlow(), event.isChallengeFlow());
            return;
        }
        log.info("3DS 认证完成事件已计数（未做风控动作）: paymentOrderId={}, merchantId={}, transStatus={}, authStatus={}",
                event.getPaymentOrderId(), event.getMerchantId(),
                event.getTransStatus(), event.getAuthStatus());
    }

    /**
     * 采样 3DS 端到端延迟（{@code nexus_3ds_auth_latency_seconds}）。
     *
     * <p>三种"采不到"一律<b>跳过</b>，不写任何数：</p>
     * <ul>
     *   <li>{@code startedAt} 或 {@code completedAt} 为 null——{@code completed_at} 在
     *       {@code ThreeDsService.updateAuthRecord} 里是"上游给了完成时刻才写"，缺失是真实形态。
     *       记 0 会把 p50/p95 拉向"3DS 秒过"这一假象，且 Prometheus 上看不出来是缺数据；</li>
     *   <li>{@code completedAt} 早于 {@code startedAt}（时钟回拨或脏数据）——负数延迟没有意义；</li>
     *   <li>两列相同：仍算一个合法样本（0 ms 可能是真的即时通过），故不排除。</li>
     * </ul>
     *
     * <p>缺样本会被 Prometheus 的 {@code _count} 反映出来：{@code nexus_3ds_auth_total}
     * 与延迟直方图的 {@code count} 之差就是"没采到延迟的认证笔数"，所以跳过不是丢信息，
     * 而是把信息放在正确的地方。</p>
     */
    private void recordAuthLatency(ThreeDsAuthCompletedEvent event) {
        LocalDateTime startedAt = event.getStartedAt();
        LocalDateTime completedAt = event.getCompletedAt();
        if (startedAt == null || completedAt == null) {
            return;
        }
        Duration span = Duration.between(startedAt, completedAt);
        if (span.isNegative()) {
            return;
        }
        metricsRecorder.getThreeDsAuthLatencyTimer().record(span);
    }
}
