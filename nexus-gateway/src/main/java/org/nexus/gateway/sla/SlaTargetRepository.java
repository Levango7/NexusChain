package org.nexus.gateway.sla;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SLA 目标 Repository。
 */
@Repository
public interface SlaTargetRepository extends JpaRepository<SlaTarget, Long> {

    /** 查询所有已启用的 SLA 目标 */
    List<SlaTarget> findByEnabledTrue();
}