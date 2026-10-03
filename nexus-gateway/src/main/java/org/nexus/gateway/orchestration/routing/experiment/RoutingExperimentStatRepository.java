package org.nexus.gateway.orchestration.routing.experiment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 路由实验组统计仓库（Wave 16 模块四边界收尾，V91）。
 * 启动回灌用继承的 {@code findAll()}。
 */
public interface RoutingExperimentStatRepository extends JpaRepository<RoutingExperimentStat, Long> {

    /** 按 (experimentId, groupId) 查行（累加 miss 时的插入前查 + 启动回灌）。 */
    Optional<RoutingExperimentStat> findByExperimentIdAndGroupId(String experimentId, String groupId);

    /**
     * 原子累加：行存在时直接在数据库侧加（同组并发事件的丢失更新被排除）。
     *
     * @return 受影响行数（0 = 行不存在，调用方负责插入）
     */
    @Modifying
    @Query("UPDATE RoutingExperimentStat s "
            + "SET s.eventCount = s.eventCount + 1, "
            + "    s.successCount = s.successCount + :successInc, "
            + "    s.totalLatencyMs = s.totalLatencyMs + :latencyInc, "
            + "    s.totalCostBps = s.totalCostBps + :costInc, "
            + "    s.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE s.experimentId = :experimentId AND s.groupId = :groupId")
    int accumulate(@Param("experimentId") String experimentId,
                   @Param("groupId") String groupId,
                   @Param("successInc") long successInc,
                   @Param("latencyInc") long latencyInc,
                   @Param("costInc") long costInc);
}
