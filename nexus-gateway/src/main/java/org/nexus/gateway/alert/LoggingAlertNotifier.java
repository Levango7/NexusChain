package org.nexus.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 日志告警通知器。
 *
 * <p>将告警事件输出到应用日志，默认启用。适用于开发环境及轻量级告警场景。</p>
 */
@Component
@ConditionalOnProperty(name = "nexus.alert.logging.enabled", havingValue = "true", matchIfMissing = true)
public class LoggingAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingAlertNotifier.class);

    @Override
    public void notify(AlertEvent event) {
        log.warn("ALERT [{}] rule={} metric={} value={} threshold={} severity={} message={}",
                event.getSeverity(),
                event.getRuleName(),
                event.getMetricName(),
                event.getCurrentValue(),
                event.getThreshold(),
                event.getSeverity(),
                event.getMessage());
    }

    @Override
    public String channel() {
        return "logging";
    }
}