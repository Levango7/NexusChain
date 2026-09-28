package org.nexus.gateway.orchestration.routing.health;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 渠道健康度历史记录仓库（Wave 16 模块二）。
 */
public interface ChannelHealthHistoryRepository extends JpaRepository<ChannelHealthHistory, Long> {

    /** 按 connector 查最近历史（sampled_at 降序）。 */
    List<ChannelHealthHistory> findByConnectorIdOrderBySampledAtDesc(String connectorId, Pageable pageable);

    /** 保留期清理。 */
    @Modifying
    @Query("DELETE FROM ChannelHealthHistory h WHERE h.sampledAt < :cutoff")
    int deleteBySampledAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /** 近 N 分钟内的样本数（采样去抖参考）。 */
    long countByConnectorIdAndSampledAtAfter(String connectorId, LocalDateTime after);
}
