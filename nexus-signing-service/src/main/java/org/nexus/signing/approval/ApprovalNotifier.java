package org.nexus.signing.approval;

/**
 * 审批人通知接口。
 *
 * <p>当审批请求创建时，通知审批人有待审批项。支持多种通知渠道：
 * <ul>
 *   <li>{@link LoggingApprovalNotifier}：日志记录（默认，运维通过日志监控）</li>
 *   <li>自定义实现：可集成钉钉/飞书/邮件/HTTP回调等</li>
 * </ul>
 *
 * <p>实现类通过 {@code nexus.approval.notifier.type} 配置选择，
 * 默认使用 {@code logging} 实现。
 *
 * @since 2.15.0
 */
@FunctionalInterface
public interface ApprovalNotifier {

    /**
     * 通知审批人：有新的审批请求待处理。
     *
     * @param request 审批请求（包含金额、发起人、审批人等上下文信息）
     */
    void notifyApprovalCreated(SigningApprovalRequest request);
}