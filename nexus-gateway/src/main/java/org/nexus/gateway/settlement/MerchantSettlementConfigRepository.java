package org.nexus.gateway.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 商户结算周期配置仓储。
 */
public interface MerchantSettlementConfigRepository extends JpaRepository<MerchantSettlementConfig, Long> {

    /**
     * 根据商户 ID 查找结算配置。
     *
     * @param merchantId 商户 ID
     * @return 结算配置（可能为空）
     */
    Optional<MerchantSettlementConfig> findByMerchantId(Long merchantId);
}