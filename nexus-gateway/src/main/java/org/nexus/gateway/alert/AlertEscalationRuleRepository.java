package org.nexus.gateway.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 告警升级规则 JPA Repository。
 */
@Repository
public interface AlertEscalationRuleRepository extends JpaRepository<AlertEscalationRule, Long> {

    /** 查询所有启用的升级规则。 */
    List<AlertEscalationRule> findByEnabledTrue();

    /** 按规则名称查询升级规则。 */
    Optional<AlertEscalationRule> findByRuleName(String ruleName);

    /** 查询指定原始严重级别的启用升级规则。 */
    List<AlertEscalationRule> findByFromSeverityAndEnabledTrue(AlertRule.Severity fromSeverity);

    /** 检查规则名称是否已存在。 */
    boolean existsByRuleName(String ruleName);
}