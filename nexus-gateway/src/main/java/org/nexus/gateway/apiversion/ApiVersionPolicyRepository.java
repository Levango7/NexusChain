package org.nexus.gateway.apiversion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * API 版本策略 Repository。
 *
 * <p>提供按版本标签查询策略的能力，供 {@link ApiVersionDeprecationService}
 * 和 {@link ApiVersionDeprecationFilter} 使用。</p>
 */
@Repository
public interface ApiVersionPolicyRepository extends JpaRepository<ApiVersionPolicy, Long> {

    /**
     * 按版本标签查找策略。
     *
     * @param version 版本标签，如 "v1"、"v2"
     * @return 版本策略（可能不存在）
     */
    Optional<ApiVersionPolicy> findByVersion(String version);
}