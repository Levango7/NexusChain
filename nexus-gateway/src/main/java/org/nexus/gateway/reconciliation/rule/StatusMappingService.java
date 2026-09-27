package org.nexus.gateway.reconciliation.rule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * 状态映射服务 — 解析渠道状态到内部状态的映射配置。
 *
 * <p>每个 {@link ReconciliationRuleConfig} 的 {@code statusMappingJson} 字段
 * 存储 JSON 格式的映射表，例如：
 * <pre>
 * {
 *   "SUCCESS": "SUCCESS",
 *   "REFUND": "REFUNDED",
 *   "NOTPAY": "PENDING",
 *   "TRADE_SUCCESS": "SUCCESS",
 *   "TRADE_REFUND": "REFUNDED",
 *   "WAIT_BUYER_PAY": "PENDING"
 * }
 * </pre>
 * </p>
 *
 * <p>当渠道状态在映射表中找不到时，返回原始渠道状态值（不做映射）。</p>
 */
@Service
public class StatusMappingService {

    private static final Logger log = LoggerFactory.getLogger(StatusMappingService.class);

    private final ObjectMapper objectMapper;

    public StatusMappingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析状态映射 JSON 字符串为 Map。
     *
     * @param statusMappingJson JSON 格式的状态映射表
     * @return 映射 Map，解析失败时返回空 Map
     */
    public Map<String, String> parseStatusMapping(String statusMappingJson) {
        if (statusMappingJson == null || statusMappingJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(statusMappingJson,
                    new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("解析状态映射 JSON 失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 将渠道状态映射为内部状态。
     *
     * @param statusMappingJson JSON 格式的状态映射表
     * @param channelStatus     渠道侧状态值
     * @return 映射后的内部状态值；如果映射表中不存在，返回原始渠道状态值
     */
    public String mapToInternalStatus(String statusMappingJson, String channelStatus) {
        if (channelStatus == null) {
            return null;
        }
        Map<String, String> mapping = parseStatusMapping(statusMappingJson);
        String internalStatus = mapping.get(channelStatus);
        if (internalStatus != null) {
            return internalStatus;
        }
        // 映射表中找不到时，返回原始值
        return channelStatus;
    }

    /**
     * 将状态映射表序列化为 JSON 字符串。
     *
     * @param statusMapping 状态映射 Map
     * @return JSON 字符串，序列化失败时返回 null
     */
    public String serializeStatusMapping(Map<String, String> statusMapping) {
        if (statusMapping == null || statusMapping.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(statusMapping);
        } catch (Exception e) {
            log.warn("序列化状态映射 JSON 失败: {}", e.getMessage());
            return null;
        }
    }
}