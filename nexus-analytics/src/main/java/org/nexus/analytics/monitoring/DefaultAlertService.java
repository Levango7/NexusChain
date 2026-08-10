package org.nexus.analytics.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link AlertService} 默认实现。
 *
 * <p>告警以进程内注册表持久化：
 * <ul>
 *   <li>{@link #raiseAlert}：分配 alertId、补齐时间戳、置 OPEN 状态并登记</li>
 *   <li>{@link #acknowledgeAlert}：将 OPEN 状态告警置为 ACKNOWLEDGED；
 *       不存在 / 已确认 / 已恢复时返回 false</li>
 *   <li>{@link #getActiveAlerts}：返回 OPEN 与 ACKNOWLEDGED 状态告警（RESOLVED 除外）</li>
 *   <li>{@link #getActiveAlertsByLevel}：按级别过滤活动告警</li>
 * </ul>
 *
 * <p>后续接入持久化存储（JPA / Redis Stream）与下游通知（webhook / email）
 * 时仅需替换本实现。
 */
@Slf4j
@Service
public class DefaultAlertService implements AlertService {

    /** 告警注册表（alertId → alert） */
    private final Map<String, Alert> alerts = new ConcurrentHashMap<>();

    @Override
    public Alert raiseAlert(Alert alert) {
        if (alert == null) {
            throw new IllegalArgumentException("Alert must not be null");
        }
        if (alert.getAlertId() == null || alert.getAlertId().isBlank()) {
            alert.setAlertId("ALERT-" + UUID.randomUUID().toString().replace("-", ""));
        }
        if (alert.getTimestamp() == null) {
            alert.setTimestamp(Instant.now());
        }
        if (alert.getState() == null) {
            alert.setState(Alert.State.OPEN);
        }
        alerts.put(alert.getAlertId(), alert);
        log.warn("Alert raised: id={}, level={}, source={}, content={}",
                alert.getAlertId(), alert.getLevel(), alert.getSource(), alert.getContent());
        return alert;
    }

    @Override
    public boolean acknowledgeAlert(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        Alert alert = alerts.get(id);
        if (alert == null || alert.getState() != Alert.State.OPEN) {
            return false;
        }
        alert.setState(Alert.State.ACKNOWLEDGED);
        log.info("Alert acknowledged: id={}", id);
        return true;
    }

    @Override
    public List<Alert> getActiveAlerts() {
        return alerts.values().stream()
                .filter(a -> a.getState() == Alert.State.OPEN || a.getState() == Alert.State.ACKNOWLEDGED)
                .collect(Collectors.toList());
    }

    @Override
    public List<Alert> getActiveAlertsByLevel(Alert.Level level) {
        if (level == null) {
            return List.of();
        }
        return alerts.values().stream()
                .filter(a -> a.getState() == Alert.State.OPEN || a.getState() == Alert.State.ACKNOWLEDGED)
                .filter(a -> a.getLevel() == level)
                .collect(Collectors.toList());
    }

    /**
     * 查询全部告警（含已恢复，测试 / 审计用）。
     *
     * @return 全部告警列表
     */
    public List<Alert> getAllAlerts() {
        return List.copyOf(alerts.values());
    }
}
