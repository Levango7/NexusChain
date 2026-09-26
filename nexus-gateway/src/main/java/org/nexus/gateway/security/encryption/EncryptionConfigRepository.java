package org.nexus.gateway.security.encryption;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 加密策略配置 JPA 仓储。
 *
 * <p>支持按租户、商户查询加密配置。商户级配置优先于全局配置（merchant_id=NULL）。</p>
 */
@Repository
public interface EncryptionConfigRepository extends JpaRepository<EncryptionConfig, Long> {

    /**
     * 按租户 + 商户查询加密配置（商户级配置）。
     */
    Optional<EncryptionConfig> findByTenantIdAndMerchantId(String tenantId, Long merchantId);

    /**
     * 按租户查询全局加密配置（merchant_id 为 NULL）。
     */
    Optional<EncryptionConfig> findByTenantIdAndMerchantIdIsNull(String tenantId);

    /**
     * 按租户查询所有加密配置。
     */
    List<EncryptionConfig> findByTenantId(String tenantId);
}