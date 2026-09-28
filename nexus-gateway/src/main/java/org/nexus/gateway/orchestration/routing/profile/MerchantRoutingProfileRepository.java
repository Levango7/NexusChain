package org.nexus.gateway.orchestration.routing.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 商户路由策略配置仓库（Wave 16 模块六）。
 */
public interface MerchantRoutingProfileRepository extends JpaRepository<MerchantRoutingProfile, Long> {

    /** 商户级生效配置，priority 降序。 */
    List<MerchantRoutingProfile> findByMerchantIdAndEnabledTrueOrderByPriorityDesc(Long merchantId);

    /** 行业级生效配置，priority 降序。 */
    List<MerchantRoutingProfile> findByIndustryAndEnabledTrueOrderByPriorityDesc(String industry);

    /** 按 profile_id 查重/查询。 */
    Optional<MerchantRoutingProfile> findByProfileId(String profileId);

    /** 商户全部配置（含禁用，管理查询）。 */
    List<MerchantRoutingProfile> findByMerchantId(Long merchantId);
}
