package org.nexus.bridge.repository;

import org.nexus.bridge.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等键 Repository（P2-F2）。
 *
 * <p>提供按 {@code (key, operation)} 复合查询的能力，用于
 * {@code BridgeServiceImpl} 在 lock / mint / burn / unlock 入口
 * 做幂等检查。</p>
 *
 * @since 2.2.0
 */
@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * 按幂等键 + 操作类型查询。
     *
     * @param key       幂等键
     * @param operation 操作类型
     * @return 命中返回 {@link IdempotencyKey}，否则空
     */
    Optional<IdempotencyKey> findByKeyAndOperation(String key, String operation);
}