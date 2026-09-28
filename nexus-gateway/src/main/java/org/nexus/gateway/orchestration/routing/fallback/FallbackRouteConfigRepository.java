package org.nexus.gateway.orchestration.routing.fallback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 降级路由配置仓库（Wave 16 模块三）。
 */
public interface FallbackRouteConfigRepository extends JpaRepository<FallbackRouteConfig, Long> {

    /** 生效配置，priority 降序（解析顺序：先商户级后全局级，由服务层过滤）。 */
    List<FallbackRouteConfig> findByEnabledTrueOrderByPriorityDesc();

    /** 商户的全部配置（含禁用，管理查询）。 */
    List<FallbackRouteConfig> findByMerchantId(Long merchantId);

    /** 按主 connector 查询（管理/诊断）。 */
    List<FallbackRouteConfig> findByPrimaryConnector(String primaryConnector);
}
