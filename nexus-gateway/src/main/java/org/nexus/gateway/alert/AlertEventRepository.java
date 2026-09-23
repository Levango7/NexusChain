package org.nexus.gateway.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警事件 JPA Repository。
 */
@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    /** 查询指定规则名称的最近告警事件（用于冷却判断）。 */
    List<AlertEvent> findByRuleNameOrderByTimestampDesc(String ruleName);

    /** 查询指定时间之后的告警事件。 */
    List<AlertEvent> findByTimestampAfterOrderByTimestampDesc(LocalDateTime since);

    /** 查询未解决的告警事件。 */
    List<AlertEvent> findByResolvedFalseOrderByTimestampDesc();

    /** 查询指定规则在某个时间点之后的事件（冷却期判断）。 */
    List<AlertEvent> findByRuleNameAndTimestampAfter(String ruleName, LocalDateTime since);
}