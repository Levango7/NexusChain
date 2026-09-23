package org.nexus.gateway.orchestration.connector;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link ConnectorConfig} 的 Spring Data JPA Repository。
 *
 * <p>提供动态注册 Connector 配置的持久化访问，支持按激活状态查询、
 * 按 ID 查找和存在性判断。</p>
 */
@Repository
public interface ConnectorConfigRepository extends JpaRepository<ConnectorConfig, String> {

    /**
     * 查找所有激活的 Connector 配置。
     *
     * @return 激活配置列表（active=true）
     */
    List<ConnectorConfig> findByActiveTrue();

    /**
     * 按 ID 查找 Connector 配置。
     *
     * @param id connector ID
     * @return 配置 Optional
     */
    @Override
    Optional<ConnectorConfig> findById(String id);

    /**
     * 判断指定 ID 的 Connector 配置是否存在。
     *
     * @param id connector ID
     * @return 存在返回 true
     */
    @Override
    boolean existsById(String id);
}