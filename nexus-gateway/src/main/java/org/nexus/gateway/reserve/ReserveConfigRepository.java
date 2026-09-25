package org.nexus.gateway.reserve;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 备付金配置 Repository。
 *
 * <p>提供按商户ID、配置编号、自动补充开关等条件查询备付金配置的方法。</p>
 */
@Repository
public interface ReserveConfigRepository extends JpaRepository<ReserveConfig, Long> {

    /**
     * 按商户ID查询备付金配置。
     *
     * @param merchantId 商户ID
     * @return 备付金配置（可能为空）
     */
    Optional<ReserveConfig> findByMerchantId(Long merchantId);

    /**
     * 按配置编号查询。
     *
     * @param configCode 配置编号
     * @return 备付金配置（可能为空）
     */
    Optional<ReserveConfig> findByConfigCode(String configCode);

    /**
     * 按自动补充开关查询配置。
     *
     * @param autoReplenishEnabled 是否自动补充
     * @return 备付金配置列表
     */
    List<ReserveConfig> findByAutoReplenishEnabled(Boolean autoReplenishEnabled);

    /**
     * 按监控开关查询配置。
     *
     * @param monitoringEnabled 是否启用监控
     * @return 备付金配置列表
     */
    List<ReserveConfig> findByMonitoringEnabled(Boolean monitoringEnabled);
}