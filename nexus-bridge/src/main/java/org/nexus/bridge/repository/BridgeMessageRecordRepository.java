package org.nexus.bridge.repository;

import org.nexus.bridge.messaging.BridgeMessageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 跨链消息落库 Repository（PLAN-026 桥持久化）。
 *
 * <p>为 {@link org.nexus.bridge.messaging.JdbcMessageStore} 提供持久化能力：
 * 按 messageId 主键去重、按源链取最大 nonce（顺序保证）。</p>
 *
 * @since 2.75.0
 */
@Repository
public interface BridgeMessageRecordRepository extends JpaRepository<BridgeMessageRecord, String> {

    /**
     * 查询指定源链已落库的最大 nonce。
     *
     * <p>用于顺序保证：新消息的 nonce 必须严格大于该值。
     * 该链尚无消息时返回 -1（与 {@link org.nexus.bridge.messaging.InMemoryMessageStore#getMaxNonce}
     * 的语义一致）。</p>
     *
     * @param sourceChain 源链 ID
     * @return 最大 nonce；无记录返回 -1
     */
    @Query("select coalesce(max(r.nonce), -1) from BridgeMessageRecord r where r.sourceChain = :chain")
    long findMaxNonceBySourceChain(@Param("chain") String sourceChain);
}
