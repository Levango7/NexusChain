package org.nexus.gateway.security.replay;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 防重放拦截统计 JPA 仓储。
 *
 * <p>设计依据：Wave 12 设计文档 §2.2.2、§4.2.2。</p>
 */
@Repository
public interface ReplayInterceptionStatsRepository extends JpaRepository<ReplayInterceptionStat, Long> {

    /**
     * 按租户和时间范围查询拦截统计。
     *
     * @param tenantId  租户 ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 拦截统计列表
     */
    @Query("SELECT s FROM ReplayInterceptionStat s WHERE s.tenantId = :tenantId " +
            "AND s.interceptedAt >= :startTime AND s.interceptedAt <= :endTime " +
            "ORDER BY s.interceptedAt DESC")
    List<ReplayInterceptionStat> findByTenantIdAndTimeRange(
            @Param("tenantId") String tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}