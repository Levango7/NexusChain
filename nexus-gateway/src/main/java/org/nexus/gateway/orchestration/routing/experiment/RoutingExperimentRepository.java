package org.nexus.gateway.orchestration.routing.experiment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 路由实验配置仓库（Wave 16 模块四）。
 */
public interface RoutingExperimentRepository extends JpaRepository<RoutingExperiment, String> {

    /** 按状态查询。 */
    List<RoutingExperiment> findByStatus(String status);

    /** 按名称查询。 */
    Optional<RoutingExperiment> findByName(String name);
}
