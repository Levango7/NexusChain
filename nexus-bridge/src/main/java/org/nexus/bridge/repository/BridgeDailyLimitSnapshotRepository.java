package org.nexus.bridge.repository;

import org.nexus.bridge.safety.BridgeDailyLimitSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 日限额历史快照 Repository（PLAN-038）。
 *
 * <p>为 {@code DailyLimitAuditService} 提供审计历史的落库/读取：
 * 日期键主键去重（同日重复快照为幂等覆盖，多副本午夜重复触发无副作用）。</p>
 *
 * @since 2.76.0
 */
@Repository
public interface BridgeDailyLimitSnapshotRepository extends JpaRepository<BridgeDailyLimitSnapshot, String> {

    /**
     * 取全部快照（按日期升序，供审计读取）。
     *
     * @return 快照列表（按 dateKey 升序）
     */
    List<BridgeDailyLimitSnapshot> findAllByOrderByDateKeyAsc();
}
