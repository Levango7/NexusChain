package org.nexus.gateway.refund;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款"等待大额签名审批"推进器（2026-10-25，Board #213）。
 *
 * <p>大额退款在 {@link DefaultRefundApprovalService#executeRefund} 中收到
 * {@code statusCode=2001} 时会把单据置 {@link RefundRequest.RefundStatus#AWAITING_APPROVAL}
 * 并返回——资金未动、审批请求已创建。审批通过后必须有人再执行一次；
 * 本推进器按固定周期重新认领这些单据并走同一条三段式执行路径
 * （{@link DefaultRefundApprovalService#executeRefund} 已支持
 * AWAITING_APPROVAL → EXECUTING 的认领）。</p>
 *
 * <p>各结果的含义：</p>
 * <ul>
 *   <li>{@code EXECUTED}：审批通过且链上已广播，订单迁移 REFUNDED；</li>
 *   <li>{@code AWAITING_APPROVAL}：仍在等待审批（或审批已通过但签名暂时失败），下轮再试；</li>
 *   <li>{@code FAILED}：审批被拒/过期/明确未动账；</li>
 *   <li>{@code EXECUTING}：结果未知——不自动重试，交由
 *       {@link RefundExecutionReconciler} 超时告警后人工核对。</li>
 * </ul>
 *
 * <p>多副本：ShedLock 串行化扫描；即便锁失效，仓储层的两个 CAS 认领
 * （APPROVED/AWAITING_APPROVAL → EXECUTING）保证同一笔只有一个执行者。</p>
 */
@Component
public class RefundApprovalExecutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(RefundApprovalExecutionReconciler.class);

    /** 刚写入 AWAITING_APPROVAL 的单据先让出一轮，避免与请求线程的认领竞争。 */
    private static final Duration SETTLE_BEFORE = Duration.ofSeconds(30);

    private static final int BATCH_LIMIT = 50;

    private final RefundRequestRepository refundRequestRepository;
    private final RefundApprovalService refundApprovalService;

    public RefundApprovalExecutionReconciler(RefundRequestRepository refundRequestRepository,
                                             RefundApprovalService refundApprovalService) {
        this.refundRequestRepository = refundRequestRepository;
        this.refundApprovalService = refundApprovalService;
    }

    /**
     * 每 2 分钟推进一批等待审批的退款。
     *
     * <p>cron 可用 {@code nexus.gateway.refund.approval-execution-cron} 覆盖。</p>
     */
    @Scheduled(cron = "${nexus.gateway.refund.approval-execution-cron:0 */2 * * * *}")
    @SchedulerLock(name = "refundApprovalExecution", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void advanceAwaitingApprovals() {
        LocalDateTime cutoff = LocalDateTime.now().minus(SETTLE_BEFORE);
        List<RefundRequest> awaiting = refundRequestRepository
                .findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        RefundRequest.RefundStatus.AWAITING_APPROVAL, cutoff);
        if (awaiting.isEmpty()) {
            return;
        }

        int executed = 0;
        int stillWaiting = 0;
        int failed = 0;
        int unknown = 0;
        for (RefundRequest refund : awaiting.subList(0, Math.min(awaiting.size(), BATCH_LIMIT))) {
            try {
                RefundRequest result = refundApprovalService.executeRefund(refund.getId());
                if (result == null) {
                    unknown++;
                    continue;
                }
                switch (result.getStatus()) {
                    case EXECUTED -> executed++;
                    case FAILED -> failed++;
                    case AWAITING_APPROVAL -> stillWaiting++;
                    case EXECUTING -> unknown++;
                    default -> log.debug("refund approval executor left status {}: refundId={}",
                            result.getStatus(), refund.getId());
                }
            } catch (RuntimeException e) {
                // 认领失败（被其他副本/请求线程抢先）或暂时性异常：下一轮再看。
                log.warn("推进待审批退款失败（不影响其他单据）: refundId={}, error={}",
                        refund.getId(), e.getMessage());
            }
        }
        log.info("待审批退款推进完成: candidates={}, executed={}, awaiting={}, failed={}, unknown={}",
                awaiting.size(), executed, stillWaiting, failed, unknown);
        if (unknown > 0) {
            log.error("有 {} 笔待审批退款执行结果未知，已留在 EXECUTING，需人工核对链上交易（禁止自动重试）",
                    unknown);
        }
    }
}
