package org.nexus.gateway.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 商户限额滚动计数仓库（A3，V92）。
 */
public interface RiskLimitAccrualRepository extends JpaRepository<RiskLimitAccrual, Long> {

    Optional<RiskLimitAccrual> findByMerchantIdAndPeriodTypeAndPeriodKey(
            Long merchantId, RiskLimitAccrual.PeriodType periodType, LocalDate periodKey);

    /**
     * 原子增减（delta 可负）：行存在时数据库侧直接加减——并发状态变更的
     * 丢失更新被排除。0 受影响行 = 行不存在，调用方负责插入。
     */
    @Modifying
    @Query("UPDATE RiskLimitAccrual a "
            + "SET a.accruedAmount = a.accruedAmount + :delta, "
            + "    a.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE a.merchantId = :merchantId AND a.periodType = :periodType AND a.periodKey = :periodKey")
    int addAmount(@Param("merchantId") Long merchantId,
                  @Param("periodType") RiskLimitAccrual.PeriodType periodType,
                  @Param("periodKey") LocalDate periodKey,
                  @Param("delta") BigDecimal delta);
}
