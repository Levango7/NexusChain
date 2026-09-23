package org.nexus.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 邮件告警通知器（占位实现）。
 *
 * <p>通过 {@code nexus.alert.email.enabled} 控制启用，默认关闭。
 * 当前版本仅记录日志，实际邮件发送需接入 SMTP/邮件服务后补全。</p>
 */
@Component
@ConditionalOnProperty(name = "nexus.alert.email.enabled", havingValue = "true")
public class EmailAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertNotifier.class);

    @Value("${nexus.alert.email.recipients:}")
    private String recipients;

    @Override
    public void notify(AlertEvent event) {
        // 占位实现：当前仅记录日志，后续接入 SMTP 后补全邮件发送逻辑
        log.info("EMAIL ALERT to [{}] rule={} metric={} value={} threshold={} severity={} message={}",
                recipients,
                event.getRuleName(),
                event.getMetricName(),
                event.getCurrentValue(),
                event.getThreshold(),
                event.getSeverity(),
                event.getMessage());
    }

    @Override
    public String channel() {
        return "email";
    }
}