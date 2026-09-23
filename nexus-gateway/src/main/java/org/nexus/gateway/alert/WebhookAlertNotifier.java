package org.nexus.gateway.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Webhook 告警通知器。
 *
 * <p>将告警事件以 HTTP POST 方式发送到配置的 webhook URL。
 * 通过 {@code nexus.alert.webhook.enabled} 控制启用，默认关闭。</p>
 */
@Component
@ConditionalOnProperty(name = "nexus.alert.webhook.enabled", havingValue = "true")
public class WebhookAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertNotifier.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${nexus.alert.webhook.url:}")
    private String webhookUrl;

    public WebhookAlertNotifier(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public void notify(AlertEvent event) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("Webhook URL not configured, skipping webhook notification for alert: {}", event.getRuleName());
            return;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("ruleName", event.getRuleName());
            payload.put("metricName", event.getMetricName());
            payload.put("currentValue", event.getCurrentValue());
            payload.put("threshold", event.getThreshold());
            payload.put("severity", event.getSeverity().name());
            payload.put("message", event.getMessage());
            payload.put("timestamp", event.getTimestamp().toString());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            restClient.post()
                    .uri(webhookUrl)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Webhook alert sent to {} for rule: {}", webhookUrl, event.getRuleName());
        } catch (Exception e) {
            log.error("Failed to send webhook alert to {} for rule {}: {}", webhookUrl, event.getRuleName(), e.getMessage());
        }
    }

    @Override
    public String channel() {
        return "webhook";
    }
}