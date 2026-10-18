package org.nexus.gateway.refund;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款执行卡单扫描器 — 发现超时停留在 {@link RefundRequest.RefundStatus#EXECUTING}
 * 的退款单并发出人工核查告警（批次⑤ P0 加固，2026-10-18）。
 *
 * <p><b>为什么需要它</b>：{@link DefaultRefundApprovalService#executeRefund} 已改为
 * "认领事务提交 → 链上出款 → 结果落库" 三段式。若链上出款成功后进程崩溃、或结果落库
 * 失败，退款单会停留在 EXECUTING——此时链上是否已出款<b>未知</b>。如果没有本扫描，
 * 这笔单会静默卡死。</p>
 *
 * <p><b>处置策略（只告警，不自动改）</b>：扫描命中后打 {@link RefundExecutionAlert#MANUAL_VERIFICATION_REQUIRED}
 * 标记的 WARN + 写审计，由人工核对链上交易后处置。理由见 {@link RefundExecutionAlert}
 * 的类注释：自动改 FAILED / EXECUTED / 自动重试三条路都可能直接错。</p>
 *
 * <p>重复执行安全（多副本）：纯查询 + 日志 + 审计，逐条收敛为同一终值，无动账；
 * ShedLock 仅用于避免多副本同时刷同一批告警。</p>
 */
@Component
public class RefundExecutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(RefundExecutionReconciler.class);

    /**
     * 判定"卡在 EXECUTING"的阈值：正常链上调用在秒级~分钟级返回，
     * 超过该时长仍未落最终结果即视为需要人工核对的异常状态。
     */
    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final RefundRequestRepository refundRequestRepository;

    /** 审计（旁路，setter 注入保持与 DefaultRefundApprovalService 同款形态）。 */
    private org.nexus.gateway.audit.AuditLogService auditLogService;

    public RefundExecutionReconciler(RefundRequestRepository refundRequestRepository) {
        this.refundRequestRepository = refundRequestRepository;
    }

    @Autowired(required = false)
    public void setAuditLogService(org.nexus.gateway.audit.AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 每 15 分钟扫描一次超时未决的退款执行单。
     *
     * <p>cron 可用 {@code nexus.gateway.refund.stuck-scan-cron} 覆盖（如运维临时停用改为
     * 远期表达式）；表达式非法时由调度框架在启动期报错（fail-fast，不是静默停扫）。</p>
     */
    @Scheduled(cron = "${nexus.gateway.refund.stuck-scan-cron:0 */15 * * * *}")
    @SchedulerLock(name = "refundStaleExecutingScan", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void scanStaleExecuting() {
        LocalDateTime cutoff = LocalDateTime.now().minus(STALE_AFTER);
        List<RefundRequest> stuck = refundRequestRepository.findByStatusAndUpdatedAtBefore(
                RefundRequest.RefundStatus.EXECUTING, cutoff);
        if (stuck.isEmpty()) {
            return;
        }
        for (RefundRequest refund : stuck) {
            log.warn("{} refundId={}, refundNo={}, orderId={}, merchantId={}, amount={}, "
                            + "stuckSince={} — 链上是否已出款未知；请人工核对链上交易后手动处置"
                            + "（处置前不得重试 executeRefund）",
                    RefundExecutionAlert.MANUAL_VERIFICATION_REQUIRED,
                    refund.getId(), refund.getRefundNo(), refund.getOrderId(),
                    refund.getMerchantId(), refund.getAmount(), refund.getUpdatedAt());
            audit(refund);
        }
        log.warn("{} 扫描完成：共 {} 笔退款停留在 EXECUTING 超 {} 分钟，已逐笔告警",
                RefundExecutionAlert.MANUAL_VERIFICATION_REQUIRED,
                stuck.size(), STALE_AFTER.toMinutes());
    }

    /** 写审计（旁路：任何异常都不得阻断扫描）。 */
    private void audit(RefundRequest refund) {
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.recordRefund(String.valueOf(refund.getOrderId()),
                    "REFUND_EXECUTION_STUCK",
                    "refundId=" + refund.getId() + ", refundNo=" + refund.getRefundNo()
                            + ", stuckSince=" + refund.getUpdatedAt());
        } catch (RuntimeException e) {
            log.warn("Refund stuck-scan audit write failed (ignored): refundId={}, error={}",
                    refund.getId(), e.getMessage());
        }
    }
}
