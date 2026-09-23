package org.nexus.gateway.split;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 分账规则 Repository。
 *
 * <p>提供按商户查询活跃规则、按 ID+商户查询规则等方法。</p>
 */
@Repository
public interface SplitRuleRepository extends JpaRepository<SplitRule, Long> {

    /**
     * 查询商户的所有活跃分账规则，按优先级升序排列。
     *
     * @param merchantId 商户 ID
     * @return 活跃分账规则列表（按 priority 升序）
     */
    List<SplitRule> findByMerchantIdAndActiveTrueOrderByPriorityAsc(Long merchantId);

    /**
     * 按 ID 和商户 ID 查询分账规则（用于归属校验）。
     *
     * @param id         规则 ID
     * @param merchantId 商户 ID
     * @return 规则 Optional
     */
    Optional<SplitRule> findByIdAndMerchantId(Long id, Long merchantId);
}