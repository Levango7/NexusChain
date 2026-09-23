package org.nexus.gateway.sla;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SLA 测量记录 Repository。
 */
@Repository
public interface SlaMeasurementRepository extends JpaRepository<SlaMeasurement, Long> {

    /** 按目标 ID 查询测量记录，按时间倒序 */
    List<SlaMeasurement> findByTargetIdOrderByMeasuredAtDesc(Long targetId);

    /** 按目标 ID 和时间范围查询测量记录 */
    List<SlaMeasurement> findByTargetIdAndMeasuredAtBetweenOrderByMeasuredAtDesc(
            Long targetId, LocalDateTime start, LocalDateTime end);

    /** 按时间范围查询所有测量记录 */
    List<SlaMeasurement> findByMeasuredAtBetweenOrderByMeasuredAtDesc(
            LocalDateTime start, LocalDateTime end);

    /** 统计指定目标在时间范围内的达标次数 */
    @Query("SELECT COUNT(m) FROM SlaMeasurement m WHERE m.targetId = :targetId "
            + "AND m.measuredAt >= :start AND m.measuredAt < :end AND m.isMet = true")
    long countMetInWindow(@Param("targetId") Long targetId,
                          @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end);

    /** 统计指定目标在时间范围内的总测量次数 */
    @Query("SELECT COUNT(m) FROM SlaMeasurement m WHERE m.targetId = :targetId "
            + "AND m.measuredAt >= :start AND m.measuredAt < :end")
    long countTotalInWindow(@Param("targetId") Long targetId,
                             @Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end);

    /** 查询指定目标的最近 N 条测量记录 */
    List<SlaMeasurement> findTopNByTargetIdOrderByMeasuredAtDesc(
            Long targetId, org.springframework.data.domain.Pageable pageable);
}