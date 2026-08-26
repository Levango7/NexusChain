package org.nexus.signing.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 多签审批请求 Repository，对应 {@code signing_approval_request} 表（任务 #375）。
 *
 * <p>由 {@link JpaApprovalStore} 消费，实现 {@link ApprovalStore} 的
 * DB 持久化语义；命中唯一索引 {@code uk_request_id}。</p>
 */
@Repository
public interface SigningApprovalRequestRepository
        extends JpaRepository<SigningApprovalRequestEntity, Long> {

    /**
     * 按业务请求 ID 查询审批请求。
     */
    Optional<SigningApprovalRequestEntity> findByRequestId(String requestId);

    /**
     * 判断业务请求 ID 是否已存在。
     */
    boolean existsByRequestId(String requestId);
}