package org.nexus.gateway.split;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 阶梯分账规则 Repository。
 *
 * <p>提供按商户查询活跃阶梯规则等方法。</p>
 */
@Repository
public interface TieredSplitRuleRepository extends JpaRepository<TieredSplitRule, Long> {

    /**
     * 查询商户的所有活跃阶梯分账规则，按阶梯序号升序排列。
     *
     * @param merchantId 商户 ID
     * @return 活跃阶梯分账规则列表（按 tier_order 升序）
     */
    List<TieredSplitRule> findByMerchantIdAndActiveTrueOrderByTierOrderAsc(Long merchantId);

    /**
     * 按 ID 和商户 ID 查询阶梯分账规则（用于归属校验）。
     *
     * @param id         规则 ID
     * @param merchantId 商户 ID
     * @return 规则 Optional
     */
    Optional<TieredSplitRule> findByIdAndMerchantId(Long id, Long merchantId);
}