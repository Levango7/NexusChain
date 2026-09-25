package org.nexus.gateway.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分布式事务日志 JPA Repository。
 *
 * <p>提供按事务 ID、状态、事务类型等维度查询事务日志的方法，
 * 供事务管理器和恢复调度器使用。</p>
 */
@Repository
public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {

    /**
     * 按事务 ID 查询所有日志，按创建时间排序（正向执行顺序）。
     *
     * @param transactionId 事务编号
     * @return 该事务的所有步骤日志（按创建时间升序）
     */
    List<TransactionLog> findByTransactionIdOrderByCreatedAtAsc(String transactionId);

    /**
     * 按步骤状态查询日志（用于恢复调度器扫描未完成的事务）。
     *
     * @param stepStatus 步骤状态
     * @return 匹配的事务日志列表
     */
    List<TransactionLog> findByStepStatus(TransactionStepStatus stepStatus);

    /**
     * 按事务类型和步骤状态查询日志。
     *
     * @param transactionType 事务类型
     * @param stepStatus 步骤状态
     * @return 匹配的事务日志列表
     */
    List<TransactionLog> findByTransactionTypeAndStepStatus(TransactionType transactionType,
                                                             TransactionStepStatus stepStatus);

    /**
     * 按步骤状态和更新时间之前查询日志（用于恢复调度器扫描超时事务）。
     *
     * @param stepStatus 步骤状态
     * @param cutoff 超时截止时间
     * @return 匹配的事务日志列表
     */
    List<TransactionLog> findByStepStatusAndUpdatedAtBefore(TransactionStepStatus stepStatus,
                                                             LocalDateTime cutoff);

    /**
     * 按事务类型、步骤状态和更新时间之前查询日志。
     *
     * @param transactionType 事务类型
     * @param stepStatus 步骤状态
     * @param cutoff 超时截止时间
     * @return 匹配的事务日志列表
     */
    List<TransactionLog> findByTransactionTypeAndStepStatusAndUpdatedAtBefore(
            TransactionType transactionType,
            TransactionStepStatus stepStatus,
            LocalDateTime cutoff);

    /**
     * 按业务凭证查询日志（用于幂等校验）。
     *
     * @param businessReference 业务凭证
     * @return 匹配的事务日志列表
     */
    List<TransactionLog> findByBusinessReference(String businessReference);
}