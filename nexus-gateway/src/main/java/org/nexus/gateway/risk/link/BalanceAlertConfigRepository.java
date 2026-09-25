package org.nexus.gateway.risk.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 余额预警配置 Repository。
 *
 * <p>提供按商户 ID 查询预警配置的方法。</p>
 */
@Repository
public interface BalanceAlertConfigRepository extends JpaRepository<BalanceAlertConfig, Long> {

    /**
     * 按商户 ID 查询预警配置。
     *
     * @param merchantId 商户 ID
     * @return 预警配置（可能为空）
     */
    Optional<BalanceAlertConfig> findByMerchantId(Long merchantId);

    /**
     * 查询所有启用的预警配置 — 供定时任务批量检查使用。
     *
     * @return 启用的预警配置列表
     */
    List<BalanceAlertConfig> findByEnabledTrue();
}