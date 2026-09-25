package org.nexus.gateway.fundtransfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 归集策略 Repository。
 *
 * <p>提供按策略编号、启用状态等条件查询归集策略的方法。</p>
 */
@Repository
public interface CollectionStrategyRepository extends JpaRepository<CollectionStrategy, Long> {

    /**
     * 按策略编号查询。
     *
     * @param strategyCode 策略编号
     * @return 归集策略（可能为空）
     */
    Optional<CollectionStrategy> findByStrategyCode(String strategyCode);

    /**
     * 按启用状态查询所有策略。
     *
     * @param enabled 是否启用
     * @return 归集策略列表
     */
    List<CollectionStrategy> findByEnabled(Boolean enabled);

    /**
     * 按目标商户ID查询策略。
     *
     * @param targetMerchantId 目标商户ID
     * @return 归集策略列表
     */
    List<CollectionStrategy> findByTargetMerchantId(Long targetMerchantId);
}