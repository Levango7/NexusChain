package org.nexus.gateway.settlement;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.settlement.clearing.ClearingEngine;
import org.nexus.settlement.clearing.ClearingOrder;
import org.nexus.settlement.clearing.ClearingOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结算"等待大额签名审批"推进器（2026-10-25，Board 裁决 20261025-0415 §六）。
 *
 * <p>大额结算在 {@link ClearingEngine} 的实现里收到 {@code AWAITING_APPROVAL}
 * 时，把清算单置 {@link ClearingOrder.OrderStatus#AWAITING} 并落库——资金未动、审批请求已建。
 * 审批通过后必须有人收口，而这条路径没有轮询调用方：
 * {@code SettlementEventCollector.drainStaging()} 只捞 {@code PENDING}，
 * 所以待审批的单不会（也不该）被它重新执行——重跑 {@code settle()} 会再执行一次
 * {@code bookSettlement}（无按 reference 去重）⇒ 重复贷账。</p>
 *
 * <p>因此本类按审批链接（幂等键 {@code execution:SETTLEMENT:<orderId>}，与
 * {@code TransactionRequest.requestId = orderId} 一致）推进一次，并把结果映射成业务状态：</p>
 * <ul>
 *   <li>{@code EXECUTED} → {@code SETTLED} + 回填 {@code settlementTxHash}；</li>
 *   <li>{@code FAILED}（审批被拒/过期，确定未动账）→ {@code FAILED}；</li>
 *   <li>结果不可核对 → {@code UNVERIFIED}（禁止重试，人工核对）；</li>
 *   <li>仍在审批/执行中 → 留在 {@code AWAITING}，下一轮再看。</li>
 * </ul>
 *
 * <p>收口只走 {@link ClearingEngine} 的状态迁移方法，<b>不重跑 settle()</b>，
 * 故不会出现"审批期间重复贷账"。多副本由 ShedLock 串行化；
 * 即便锁失效，审批链接自身的乐观锁 + 签名服务 APPROVED→EXECUTING 的 CAS
 * 保证同一审批只有一个执行者。</p>
 */
@Component
public class SettlementApprovalReconciler {

    private static final Logger log = LoggerFactory.getLogger(SettlementApprovalReconciler.class);

    /** 与 DefaultClearingEngine 构造 TransactionRequest 的 requestId 一致。 */
    static final String REQUEST_KEY_PREFIX = "execution:SETTLEMENT:";

    private final ClearingOrderRepository clearingOrderRepository;
    private final ClearingEngine clearingEngine;
    private final SigningApprovalExecutionService signingApprovalExecutionService;

    public SettlementApprovalReconciler(ClearingOrderRepository clearingOrderRepository,
                                        ClearingEngine clearingEngine,
                                        SigningApprovalExecutionService signingApprovalExecutionService) {
        this.clearingOrderRepository = clearingOrderRepository;
        this.clearingEngine = clearingEngine;
        this.signingApprovalExecutionService = signingApprovalExecutionService;
    }

    /**
     * 每 2 分钟推进一批待审批结算。
     *
     * <p>cron 可用 {@code nexus.gateway.settlement.approval-execution-cron} 覆盖。</p>
     */
    @Scheduled(cron = "${nexus.gateway.settlement.approval-execution-cron:0 */2 * * * *}")
    @SchedulerLock(name = "settlementApprovalExecution", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void advanceAwaitingApprovals() {
        List<ClearingOrder> awaiting =
                clearingOrderRepository.findTop100ByStatusOrderByOrderIdAsc(ClearingOrder.OrderStatus.AWAITING);
        if (awaiting.isEmpty()) {
            return;
        }

        int settled = 0;
        int failed = 0;
        int unverified = 0;
        int waiting = 0;
        for (ClearingOrder order : awaiting) {
            String requestKey = REQUEST_KEY_PREFIX + order.getOrderId();
            try {
                SigningOutcome outcome = signingApprovalExecutionService.refresh(requestKey);
                if (outcome.isExecuted()) {
                    if (clearingEngine.completeAfterSigningApproval(order.getOrderId(), outcome.txHash())) {
                        settled++;
                    } else {
                        unverified++;
                    }
                } else if (outcome.requiresManualVerification()) {
                    clearingEngine.markSigningApprovalUnverified(order.getOrderId(), outcome.error());
                    unverified++;
                } else if (outcome.isFailed()) {
                    clearingEngine.markSigningApprovalRejected(order.getOrderId(), outcome.error());
                    failed++;
                } else {
                    waiting++;
                }
            } catch (RuntimeException e) {
                // 状态未改写：下一轮再推进。单笔异常不得中断整批。
                log.error("推进待审批结算失败（不影响其他清算单）: orderId={}, requestKey={}, error={}",
                        order.getOrderId(), requestKey, e.getMessage(), e);
            }
        }
        log.info("待审批结算推进完成: candidates={}, settled={}, failed={}, unverified={}, waiting={}",
                awaiting.size(), settled, failed, unverified, waiting);
        if (unverified > 0) {
            log.error("有 {} 笔待审批结算结果不可核对，已转 UNVERIFIED，需人工核对链上交易（禁止自动重试）",
                    unverified);
        }
    }
}
