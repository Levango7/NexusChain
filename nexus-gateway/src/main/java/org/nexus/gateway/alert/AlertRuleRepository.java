package org.nexus.gateway.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 告警规则 JPA Repository。
 */
@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    /** 查询所有启用的告警规则。 */
    List<AlertRule> findByEnabledTrue();

    /** 按名称查询告警规则。 */
    Optional<AlertRule> findByName(String name);

    /** 检查名称是否已存在。 */
    boolean existsByName(String name);
}