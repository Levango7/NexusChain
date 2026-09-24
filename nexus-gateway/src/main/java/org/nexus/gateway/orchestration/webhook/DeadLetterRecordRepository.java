package org.nexus.gateway.orchestration.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 死信记录 Repository（Wave 8-A5）。
 *
 * @since Wave 8-A5 - Webhook 可靠性增强
 */
@Repository
public interface DeadLetterRecordRepository extends JpaRepository<DeadLetterRecord, Long> {

    /** 按事件 ID 查询死信记录（一个投递可能对应一条死信记录）。 */
    List<DeadLetterRecord> findByEventId(String eventId);

    /** 按状态查询死信记录（用于监控/统计/重投扫描）。 */
    List<DeadLetterRecord> findByStatus(DeadLetterRecordStatus status);

    /** 按状态分页查询死信记录。 */
    Page<DeadLetterRecord> findByStatus(DeadLetterRecordStatus status, Pageable pageable);

    /** 按状态查询数量。 */
    long countByStatus(DeadLetterRecordStatus status);
}