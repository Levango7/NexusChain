package org.nexus.gateway.repository;

import org.nexus.gateway.model.MerchantKeypairEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 商户密钥对持久化仓库（B-14 修复）。
 *
 * <p>供 {@link org.nexus.gateway.security.VaultKeyManager} 在启动时全量加载、
 * 写入/更新时同步落库使用。复用 Spring Data JPA 基础设施，无需手写 SQL。</p>
 */
@Repository
public interface MerchantKeypairRepository extends JpaRepository<MerchantKeypairEntry, Long> {

    Optional<MerchantKeypairEntry> findByMerchantId(Long merchantId);
}