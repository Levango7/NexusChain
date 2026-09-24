package org.nexus.gateway.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 告警聚合 JPA Repository。
 */
@Repository
public interface AlertAggregationRepository extends JpaRepository<AlertAggregation, Long> {

    /** 按聚合键查询聚合记录。 */
    Optional<AlertAggregation> findByAggregationKey(String aggregationKey);

    /** 查询指定时间窗口内未通知的聚合记录。 */
    List<AlertAggregation> findByNotifiedFalseAndWindowEndBefore(LocalDateTime windowEnd);

    /** 查询指定规则名称的聚合记录。 */
    List<AlertAggregation> findByRuleNameOrderByAggregatedAtDesc(String ruleName);

    /** 查询指定规则和严重级别的聚合记录。 */
    List<AlertAggregation> findByRuleNameAndSeverityOrderByAggregatedAtDesc(
            String ruleName, AlertRule.Severity severity);
}