package org.nexus.gateway.security.encryption;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 加密密钥元数据 JPA 仓储。
 *
 * <p>支持按 KEK 版本和状态查询，用于密钥轮换时的渐进式 DEK 迁移。</p>
 */
@Repository
public interface EncryptionKeyMetadataRepository extends JpaRepository<EncryptionKeyMetadata, Long> {

    /**
     * 按 KEK 版本 + 状态查询 — 用于渐进式迁移时查找需迁移的 DEK 记录。
     */
    List<EncryptionKeyMetadata> findByKekVersionAndStatus(int kekVersion, KeyMetadataStatus status);

    /**
     * 按 KEK 版本 + 状态 + ID 大于指定值查询 — 用于断点续传的批量迁移。
     */
    Page<EncryptionKeyMetadata> findByKekVersionAndStatusAndIdGreaterThan(
            int kekVersion, KeyMetadataStatus status, Long lastId, Pageable pageable);

    /**
     * 按租户 + 字段名 + KEK 版本查询。
     */
    Optional<EncryptionKeyMetadata> findByTenantIdAndFieldNameAndKekVersion(
            String tenantId, String fieldName, int kekVersion);

    /**
     * 按租户 + 字段名 + 状态查询 — 获取当前活跃的 DEK。
     */
    Optional<EncryptionKeyMetadata> findByTenantIdAndFieldNameAndStatus(
            String tenantId, String fieldName, KeyMetadataStatus status);

    /**
     * 按 KEK 版本统计记录数 — 用于检查旧 KEK 是否仍被引用。
     */
    long countByKekVersionAndStatus(int kekVersion, KeyMetadataStatus status);
}