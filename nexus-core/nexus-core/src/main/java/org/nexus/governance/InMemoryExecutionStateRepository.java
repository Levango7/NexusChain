package org.nexus.governance;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存执行状态仓储默认实现。
 *
 * <p>基于 {@link ConcurrentHashMap}，保持与重构前 {@code GovernanceExecutor.executionState}
 * 相同的并发语义与向后兼容性。</p>
 *
 * @since 1.3
 */
@Component
public class InMemoryExecutionStateRepository implements ExecutionStateRepository {

    private final ConcurrentHashMap<String, ExecutionState> store = new ConcurrentHashMap<>();

    @Override
    public void save(String proposalId, ExecutionState state) {
        if (proposalId == null || state == null) {
            return;
        }
        store.put(proposalId, state);
    }

    @Override
    public ExecutionState get(String proposalId) {
        if (proposalId == null) {
            return null;
        }
        return store.get(proposalId);
    }

    @Override
    public boolean remove(String proposalId) {
        if (proposalId == null) {
            return false;
        }
        return store.remove(proposalId) != null;
    }
}