package org.nexus.walletsvc.repository;

import org.nexus.walletsvc.entity.CustodyTransferPendingEntity;
import org.nexus.walletsvc.entity.CustodyTransferPendingEntity.PendingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 托管出金待处置记录 Repository，对应 {@code custody_transfer_pending} 表。
 *
 * <p>供人工核对队列取用（按状态 + 最久未处理优先）与存量可见性统计。
 * 排序字段 {@code createdAt} 是 Entity 真实属性名，不是主键名 {@code id}。</p>
 */
@Repository
public interface CustodyTransferPendingRepository
        extends JpaRepository<CustodyTransferPendingEntity, Long> {

    Optional<CustodyTransferPendingEntity> findByRequestKey(String requestKey);

    /**
     * 按待处置状态取队列（最久未处理优先）。
     *
     * @param status 待处置状态
     * @return 匹配记录，最多 100 条
     */
    List<CustodyTransferPendingEntity> findTop100ByStatusOrderByCreatedAtAsc(PendingStatus status);

    /**
     * 按待处置状态统计存量，供告警与可见性日志使用。
     *
     * @param status 待处置状态
     * @return 记录数
     */
    long countByStatus(PendingStatus status);
}
