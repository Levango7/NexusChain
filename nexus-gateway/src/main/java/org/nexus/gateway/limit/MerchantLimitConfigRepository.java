package org.nexus.gateway.limit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 商户限额配置 Repository。
 */
@Repository
public interface MerchantLimitConfigRepository extends JpaRepository<MerchantLimitConfig, Long> {

    /**
     * 按商户 ID 查询限额配置。
     *
     * @param merchantId 商户 ID
     * @return 限额配置（可能为空）
     */
    Optional<MerchantLimitConfig> findByMerchantId(Long merchantId);
}