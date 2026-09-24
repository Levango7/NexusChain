package org.nexus.gateway.limit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 渠道限额配置 Repository。
 */
@Repository
public interface ChannelLimitConfigRepository extends JpaRepository<ChannelLimitConfig, Long> {

    /**
     * 按商户 ID 查询所有渠道限额配置。
     *
     * @param merchantId 商户 ID
     * @return 渠道限额配置列表
     */
    List<ChannelLimitConfig> findByMerchantId(Long merchantId);

    /**
     * 按商户 ID 和渠道类型查询渠道限额配置。
     *
     * @param merchantId   商户 ID
     * @param channelType  渠道类型
     * @return 渠道限额配置 Optional
     */
    Optional<ChannelLimitConfig> findByMerchantIdAndChannelType(Long merchantId,
                                                                 ChannelLimitConfig.ChannelType channelType);

    /**
     * 按商户 ID 查询所有活跃的渠道限额配置。
     *
     * @param merchantId 商户 ID
     * @return 活跃渠道限额配置列表
     */
    List<ChannelLimitConfig> findByMerchantIdAndActiveTrue(Long merchantId);
}