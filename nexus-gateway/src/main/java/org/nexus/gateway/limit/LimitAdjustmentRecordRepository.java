package org.nexus.gateway.limit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 限额调整记录 Repository。
 */
@Repository
public interface LimitAdjustmentRecordRepository extends JpaRepository<LimitAdjustmentRecord, Long> {

    /**
     * 按商户 ID 查询限额调整记录。
     *
     * @param merchantId 商户 ID
     * @return 调整记录列表
     */
    List<LimitAdjustmentRecord> findByMerchantId(Long merchantId);

    /**
     * 按商户 ID 和规则类型查询限额调整记录。
     *
     * @param merchantId 商户 ID
     * @param ruleType   规则类型
     * @return 调整记录列表
     */
    List<LimitAdjustmentRecord> findByMerchantIdAndRuleType(Long merchantId,
                                                             LimitAdjustmentRule.RuleType ruleType);
}