package org.nexus.analytics.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * {@link AlertService} 默认骨架实现。
 *
 * <p>当前为占位实现，告警仅记录日志不入库。后续接入持久化存储
 * （如 JPA Repository / Redis Stream）后填充业务逻辑。
 */
@Slf4j
@Service
public class DefaultAlertService implements AlertService {

    @Override
    public Alert raiseAlert(Alert alert) {
        // TODO: 持久化告警事件，分配 alertId，触发下游通知（webhook / email）
        log.warn("raiseAlert skeleton invoked: level={}, source={}, content={}",
                alert.getLevel(), alert.getSource(), alert.getContent());
        return alert;
    }

    @Override
    public boolean acknowledgeAlert(String id) {
        // TODO: 标记告警状态为 ACKNOWLEDGED
        log.debug("acknowledgeAlert skeleton invoked: id={}", id);
        return false;
    }

    @Override
    public List<Alert> getActiveAlerts() {
        // TODO: 查询所有 OPEN / ACKNOWLEDGED 状态告警
        log.debug("getActiveAlerts skeleton invoked");
        return Collections.emptyList();
    }

    @Override
    public List<Alert> getActiveAlertsByLevel(Alert.Level level) {
        // TODO: 按级别过滤活动告警
        log.debug("getActiveAlertsByLevel skeleton invoked: level={}", level);
        return Collections.emptyList();
    }
}