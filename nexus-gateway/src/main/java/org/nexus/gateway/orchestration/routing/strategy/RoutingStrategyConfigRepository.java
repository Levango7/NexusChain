package org.nexus.gateway.orchestration.routing.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 多目标路由策略配置仓库（Wave 16 模块一）。
 */
public interface RoutingStrategyConfigRepository extends JpaRepository<RoutingStrategyConfig, Long> {

    /** 按名称查重（name 唯一约束）。 */
    Optional<RoutingStrategyConfig> findByName(String name);

    /** 生效配置列表，priority 降序（多目标权重解析顺序）。 */
    List<RoutingStrategyConfig> findByEnabledTrueOrderByPriorityDesc();

    /** 按创建人查询（审计辅助）。 */
    List<RoutingStrategyConfig> findByCreatedBy(String createdBy);
}
