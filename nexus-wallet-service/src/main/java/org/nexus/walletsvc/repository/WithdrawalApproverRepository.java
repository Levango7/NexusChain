package org.nexus.walletsvc.repository;

import org.nexus.walletsvc.entity.WithdrawalApproverEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 提现审批人 Repository，对应 {@code withdrawal_approvers} 表。
 *
 * <p>设计文档 §4.2.2 / §4.4.3：替代 SDK DTO {@link org.nexus.sdk.wallet.WithdrawalRequest#getApprovers()}
 * 的持久化形态，一对多关联到 {@code withdrawal_requests}。</p>
 */
@Repository
public interface WithdrawalApproverRepository extends JpaRepository<WithdrawalApproverEntity, Long> {

    /**
     * 查询某提现请求的全部审批人记录。
     *
     * <p>由 {@code DefaultWithdrawalApprovalService.getRequest()} 调用，
     * 配合 {@link org.nexus.walletsvc.entity.WithdrawalRequestMapper#toDto} 注入 DTO。</p>
     */
    List<WithdrawalApproverEntity> findByRequestId(String requestId);

    /**
     * 判断某审批人是否已对某提现请求审批过。
     *
     * <p>对应 {@code DefaultWithdrawalApprovalService.approve()} 的重复审批校验，
     * 数据库唯一约束 {@code uk_request_approver (request_id, approver_id)} 兜底。</p>
     */
    boolean existsByRequestIdAndApproverId(String requestId, String approverId);

    /**
     * 统计某提现请求的已审批人数。
     *
     * <p>对应 {@code DefaultWithdrawalApprovalService.approve()} 中
     * {@code approvedCount} 的计算（设计文档 §4.4.3）。</p>
     */
    long countByRequestId(String requestId);
}