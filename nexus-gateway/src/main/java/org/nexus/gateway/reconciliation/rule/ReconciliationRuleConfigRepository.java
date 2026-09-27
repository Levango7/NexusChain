package org.nexus.gateway.reconciliation.rule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对账规则配置 Repository。
 *
 * <p>支持按多层级组合查询：商户+渠道、商户+null、null+渠道、null+null。</p>
 */
@Repository
public interface ReconciliationRuleConfigRepository extends JpaRepository<ReconciliationRuleConfig, Long> {

    /** 商户+渠道级配置 */
    Optional<ReconciliationRuleConfig> findByMerchantIdAndChannelType(Long merchantId, String channelType);

    /** 商户级默认配置（不限渠道） */
    Optional<ReconciliationRuleConfig> findByMerchantIdAndChannelTypeIsNull(Long merchantId);

    /** 渠道级默认配置（不限商户） */
    Optional<ReconciliationRuleConfig> findByMerchantIdIsNullAndChannelType(String channelType);

    /** 全局默认配置 */
    Optional<ReconciliationRuleConfig> findByMerchantIdIsNullAndChannelTypeIsNull();

    /** 查询商户的所有配置 */
    List<ReconciliationRuleConfig> findByMerchantId(Long merchantId);
}