package org.nexus.walletsvc.repository;

import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.entity.WithdrawalRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 提现审批请求 Repository，对应 {@code withdrawal_requests} 表。
 *
 * <p>设计文档 §4.2.2 / §4.4.3：替代
 * {@code DefaultWithdrawalApprovalService.requests} 内存存储。</p>
 *
 * <p>状态查询使用 {@link WithdrawalRequest.WithdrawalStatus} 枚举参数，类型安全；
 * Spring Data JPA 自动将 {@code @Enumerated(STRING)} Entity 字段与枚举参数匹配。</p>
 */
@Repository
public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequestEntity, Long> {

    /**
     * 按业务请求 ID 查询（{@code WD-<uuid>}）。
     */
    Optional<WithdrawalRequestEntity> findByRequestId(String requestId);

    /**
     * 按状态查询所有请求，命中索引 {@code idx_status (status)}。
     */
    List<WithdrawalRequestEntity> findByStatus(WithdrawalRequest.WithdrawalStatus status);

    /**
     * 按状态查询并按创建时间倒序排列（最新优先）。
     *
     * <p>适用于管理后台待审批列表展示。</p>
     */
    List<WithdrawalRequestEntity> findByStatusOrderByCreatedAtDesc(WithdrawalRequest.WithdrawalStatus status);
}