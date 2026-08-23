package org.nexus.bridge.repository;

import org.nexus.bridge.entity.NonceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Nonce 持久化 Repository（B-22 修复）。
 *
 * <p>提供 nonce 记录的 CRUD 与过期清理能力，用于
 * {@link org.nexus.bridge.security.ReplayProtection} 将已使用 nonce
 * 持久化到 DB，节点重启后仍能防止重放攻击。</p>
 *
 * @since 2.28.0
 */
@Repository
public interface NonceRecordRepository extends JpaRepository<NonceRecord, String> {

    /**
     * 按创建时间查询过期 nonce（用于清理）。
     *
     * @param cutoff 截止时间（早于此时间的记录视为过期）
     * @return 过期 nonce 记录列表
     */
    List<NonceRecord> findByCreatedAtBefore(Instant cutoff);
}