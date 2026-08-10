package org.nexus.walletsvc.repository;

import org.nexus.walletsvc.entity.CustodyBalanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 托管余额 Repository，对应 {@code custody_balances} 表。
 *
 * <p>主键为 {@code tier}（String，HOT / WARM / COLD），设计文档 §4.2.2。</p>
 */
@Repository
public interface CustodyBalanceRepository extends JpaRepository<CustodyBalanceEntity, String> {

    /**
     * 按托管层级查询余额记录。
     *
     * @param tier 托管层级（HOT / WARM / COLD）
     * @return 余额 Entity，未找到时返回 {@link Optional#empty()}
     */
    Optional<CustodyBalanceEntity> findByTier(String tier);
}