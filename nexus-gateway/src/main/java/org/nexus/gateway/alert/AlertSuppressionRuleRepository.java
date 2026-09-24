package org.nexus.gateway.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 告警抑制规则 JPA Repository。
 */
@Repository
public interface AlertSuppressionRuleRepository extends JpaRepository<AlertSuppressionRule, Long> {

    /** 查询所有启用的抑制规则。 */
    List<AlertSuppressionRule> findByEnabledTrue();

    /** 查询指定父规则名称的抑制规则。 */
    List<AlertSuppressionRule> findByParentRuleNameAndEnabledTrue(String parentRuleName);

    /** 查询指定子规则名称的抑制规则。 */
    List<AlertSuppressionRule> findByChildRuleNameAndEnabledTrue(String childRuleName);

    /** 检查指定父子规则组合是否存在。 */
    boolean existsByParentRuleNameAndChildRuleName(String parentRuleName, String childRuleName);
}