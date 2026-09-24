package org.nexus.settlement.risk.composition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 规则组合 Repository。
 */
@Repository
public interface RuleCompositionRepository extends JpaRepository<RuleComposition, Long> {

    /**
     * 根据组合名称查找。
     *
     * @param compositionName 组合名称
     * @return 组合配置（可选）
     */
    Optional<RuleComposition> findByCompositionName(String compositionName);

    /**
     * 查找所有已启用的组合，按优先级排序。
     *
     * @return 已启用的组合列表
     */
    List<RuleComposition> findByEnabledTrueOrderByPriorityAsc();

    /**
     * 检查组合名称是否已存在。
     *
     * @param compositionName 组合名称
     * @return 是否存在
     */
    boolean existsByCompositionName(String compositionName);
}