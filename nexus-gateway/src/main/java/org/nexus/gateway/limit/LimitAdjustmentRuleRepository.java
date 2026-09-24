package org.nexus.gateway.limit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 限额调整规则 Repository。
 */
@Repository
public interface LimitAdjustmentRuleRepository extends JpaRepository<LimitAdjustmentRule, Long> {

    /**
     * 查询所有活跃的调整规则。
     *
     * @return 活跃规则列表
     */
    List<LimitAdjustmentRule> findByActiveTrue();

    /**
     * 查询指定商户的活跃调整规则。
     *
     * @param merchantId 商户 ID
     * @return 活跃规则列表
     */
    List<LimitAdjustmentRule> findByMerchantIdAndActiveTrue(Long merchantId);

    /**
     * 查询指定商户和规则类型的活跃调整规则。
     *
     * @param merchantId 商户 ID
     * @param ruleType   规则类型
     * @return 活跃规则列表
     */
    List<LimitAdjustmentRule> findByMerchantIdAndActiveTrueAndRuleType(
            Long merchantId, LimitAdjustmentRule.RuleType ruleType);
}