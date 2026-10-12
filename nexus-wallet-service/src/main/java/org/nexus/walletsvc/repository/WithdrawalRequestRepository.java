package org.nexus.walletsvc.repository;

import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.entity.WithdrawalRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 原子状态流转（2026-10-12 新增，修「并发重复广播」缺陷）。
     *
     * <p>只在当前状态等于 {@code from} 时改为 {@code to}，返回受影响行数。
     * 条件 UPDATE 由数据库行级锁保证互斥：并发/重复调用只有一个能拿到 1 行，
     * 其余拿到 0 行 —— 调用方据此拒绝执行（而不是各自去调 signing 广播）。</p>
     *
     * <p>{@code clearAutomatically + flushAutomatically}：批量 UPDATE 绕过持久化上下文，
     * 清空后让后续 {@code save()} 走 merge，避免读到陈旧状态。</p>
     *
     * <p><b>为什么这里必须有 {@code @Transactional}</b>：{@code @Modifying} 批量操作要求活动事务，
     * 而调用它的 {@code executeApprovedWithdrawal} 只有 Seata 的 {@code @GlobalTransactional}
     * （CI 集成测试不启 Seata ⇒ 无事务 ⇒ {@code TransactionRequiredException}）。
     * 放在仓储方法上而不是服务方法上是有意的：服务方法里紧接着要调 signing-service 广播，
     * 若把整个方法包进本地事务会让「数据库事务跨越网络调用」、长时间持锁；
     * 仓储级事务保证抢占**立即提交**（并发调用方马上能看到 EXECUTING）。</p>
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WithdrawalRequestEntity r SET r.status = :to "
            + "WHERE r.requestId = :requestId AND r.status = :from")
    int compareAndSetStatus(@Param("requestId") String requestId,
                            @Param("from") WithdrawalRequest.WithdrawalStatus from,
                            @Param("to") WithdrawalRequest.WithdrawalStatus to);
}