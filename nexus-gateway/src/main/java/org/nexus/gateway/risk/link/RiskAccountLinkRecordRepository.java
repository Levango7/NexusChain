package org.nexus.gateway.risk.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 风控联动记录 Repository。
 *
 * <p>提供按风控事件 ID + 联动动作查询的方法，用于幂等检查。</p>
 */
@Repository
public interface RiskAccountLinkRecordRepository extends JpaRepository<RiskAccountLinkRecord, Long> {

    /**
     * 按风控事件 ID 和联动动作查询 — 用于幂等检查。
     *
     * <p>同一 riskEventId + linkAction 组合唯一，如果已存在记录则跳过执行。</p>
     *
     * @param riskEventId 风控事件 ID
     * @param linkAction  联动动作
     * @return 联动记录（可能为空）
     */
    Optional<RiskAccountLinkRecord> findByRiskEventIdAndLinkAction(String riskEventId, LinkAction linkAction);
}