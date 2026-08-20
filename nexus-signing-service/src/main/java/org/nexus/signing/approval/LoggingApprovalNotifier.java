package org.nexus.signing.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志审批通知器（默认实现）。
 *
 * <p>审批请求创建时记录 WARN 级别日志，运维通过日志监控发现待审批项。
 * 不依赖外部消息系统，适合最小化部署场景。
 *
 * <p>通过 {@code nexus.approval.notifier.type=logging} 激活（默认值）。
 *
 * @since 2.15.0
 */
public class LoggingApprovalNotifier implements ApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingApprovalNotifier.class);

    @Override
    public void notifyApprovalCreated(SigningApprovalRequest request) {
        log.warn("========== 待审批通知 ==========");
        log.warn("审批ID: {}", request.getRequestId());
        log.warn("发起人: {}", request.getInitiator());
        log.warn("金额: {} {}", request.getAmount(), request.getCurrency());
        log.warn("转出: {}", request.getFromPubkey());
        log.warn("转入: {}", request.getToPubkeyHash());
        log.warn("所需审批人数: {}", request.getRequiredApprovers());
        log.warn("截止时间: {}", request.getDeadline());
        log.warn("当前审批数: {} / {}", request.getApprovals().size(), request.getRequiredApprovers());
        log.warn("================================");
    }
}