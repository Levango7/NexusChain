package org.nexus.oracle.governance.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 治理审计日志 Spring Data JPA Repository（GOV-P0-02）。
 *
 * <p>提供 {@link GovernanceAuditLogEntry} 的持久化访问，支持按提案 ID 查询审计记录、
 * 获取提案的最后一条审计记录（用于哈希链衔接）等操作。
 *
 * @since 2.1.0
 */
@Repository
public interface GovernanceAuditLogRepository extends JpaRepository<GovernanceAuditLogEntry, Long> {

    /**
     * 按提案 ID 查询所有审计记录，按时间升序返回（哈希链顺序）。
     *
     * @param proposalId 提案 ID
     * @return 审计记录列表（按时间升序）
     */
    List<GovernanceAuditLogEntry> findByProposalIdOrderByTimestampAsc(String proposalId);

    /**
     * 查询提案的最后一条审计记录（按时间降序取第一条，用于哈希链衔接）。
     *
     * @param proposalId 提案 ID
     * @return 最后一条审计记录（若存在）
     */
    Optional<GovernanceAuditLogEntry> findFirstByProposalIdOrderByTimestampDesc(String proposalId);

    /**
     * 统计提案的审计记录数。
     *
     * @param proposalId 提案 ID
     * @return 记录数
     */
    long countByProposalId(String proposalId);

    /**
     * 删除提案的所有审计记录。
     *
     * @param proposalId 提案 ID
     */
    void deleteByProposalId(String proposalId);

    /**
     * 查询所有提案 ID（去重，用于全局审计视图）。
     *
     * @return 去重后的提案 ID 列表
     */
    @Query("SELECT DISTINCT e.proposalId FROM GovernanceAuditLogEntry e")
    List<String> findDistinctProposalIds();

    /**
     * 统计所有审计记录总数。
     *
     * @return 记录总数
     */
    @Query("SELECT COUNT(e) FROM GovernanceAuditLogEntry e")
    long countAllEntries();

    /**
     * 按 ID 升序查询全部记录（用于全局哈希链校验）。
     *
     * @param proposalId 提案 ID
     * @return 按 ID 升序的记录列表
     */
    List<GovernanceAuditLogEntry> findByProposalIdOrderByIdAsc(@Param("proposalId") String proposalId);
}