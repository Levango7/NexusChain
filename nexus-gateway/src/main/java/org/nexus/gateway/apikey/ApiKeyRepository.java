package org.nexus.gateway.apikey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * API Key JPA Repository。
 *
 * <p>提供按 keyId、merchantId 查询 Key 的能力。{@link ApiKeyService} 通过
 * {@link #findByKeyId} 在验证阶段查询 Key，通过 {@link #findByMerchantId}
 * 列出租户的所有 Key。</p>
 */
@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** 按 keyId（公开标识）查询。 */
    Optional<ApiKey> findByKeyId(String keyId);

    /** 列出租户的所有 Key（按创建时间降序）。 */
    List<ApiKey> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    /** 判断 keyId 是否已存在。 */
    boolean existsByKeyId(String keyId);

    /** 统计租户的 Key 数量（用于 max-keys-per-merchant 限制）。 */
    long countByMerchantId(String merchantId);
}