package org.nexus.governance;

/**
 * 治理提案执行状态持久化仓储接口。
 *
 * <p>抽象 {@link GovernanceExecutor} 中执行状态的存储职责，
 * 默认实现 {@link InMemoryExecutionStateRepository} 保持原有 {@code ConcurrentHashMap} 行为。</p>
 *
 * @since 1.3
 */
public interface ExecutionStateRepository {

    /**
     * 保存或更新执行状态。
     *
     * @param proposalId 提案 ID
     * @param state      执行状态
     */
    void save(String proposalId, ExecutionState state);

    /**
     * 查询执行状态。
     *
     * @param proposalId 提案 ID
     * @return 执行状态；不存在返回 null
     */
    ExecutionState get(String proposalId);

    /**
     * 删除执行状态。
     *
     * @param proposalId 提案 ID
     * @return 删除成功返回 true
     */
    boolean remove(String proposalId);
}