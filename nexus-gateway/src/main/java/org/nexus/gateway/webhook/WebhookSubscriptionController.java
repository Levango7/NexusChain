package org.nexus.gateway.webhook;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Webhook 订阅管理 REST API。
 *
 * <p>端点（所有端点需要租户上下文，通过 ApiKeyInterceptor 注入 merchantId）：
 * <ul>
 *   <li>{@code POST /api/v1/webhook-subscriptions} — 创建订阅</li>
 *   <li>{@code GET /api/v1/webhook-subscriptions} — 列出当前租户的订阅</li>
 *   <li>{@code GET /api/v1/webhook-subscriptions/{id}} — 查看订阅详情</li>
 *   <li>{@code PUT /api/v1/webhook-subscriptions/{id}} — 更新订阅</li>
 *   <li>{@code POST /api/v1/webhook-subscriptions/{id}/pause} — 暂停订阅</li>
 *   <li>{@code POST /api/v1/webhook-subscriptions/{id}/resume} — 恢复订阅</li>
 *   <li>{@code DELETE /api/v1/webhook-subscriptions/{id}} — 删除订阅</li>
 * </ul>
 *
 * <p>所有端点强制从认证上下文获取 merchantId（覆盖请求体伪造值），并通过
 * MerchantOwnershipGuard 校验订阅归属，防止跨商户操作。</p>
 */
@RestController
@RequestMapping("/api/v1/webhook-subscriptions")
public class WebhookSubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionController.class);

    private final WebhookSubscriptionService subscriptionService;
    private final MerchantOwnershipGuard ownershipGuard;

    public WebhookSubscriptionController(WebhookSubscriptionService subscriptionService,
                                          MerchantOwnershipGuard ownershipGuard) {
        this.subscriptionService = subscriptionService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 创建 Webhook 订阅。
     *
     * <p>merchantId 强制取认证上下文，请求体中的 merchantId 字段被覆盖。</p>
     *
     * @param request    创建请求
     * @param httpRequest HTTP 请求（用于获取认证上下文）
     * @return 创建的订阅（201）
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateSubscriptionRequest request,
                                                       HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Set<WebhookEventType> eventTypes = parseEventTypes(request.getEventTypes());
        WebhookSubscription subscription = subscriptionService.createSubscription(
                callerMerchantId,
                request.getTargetUrl(),
                eventTypes,
                request.getDescription()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(subscription));
    }

    /**
     * 列出当前租户的所有订阅（不含已删除）。
     *
     * @param httpRequest HTTP 请求
     * @return 订阅列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        List<WebhookSubscription> subscriptions = subscriptionService.listSubscriptions(callerMerchantId);
        List<Map<String, Object>> data = subscriptions.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(data);
    }

    /**
     * 查看订阅详情。
     *
     * @param id          订阅 ID
     * @param httpRequest HTTP 请求
     * @return 订阅详情；404 若不存在或不属于当前租户
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id,
                                                    HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        WebhookSubscription subscription = subscriptionService.findByIdAndMerchantId(id, callerMerchantId);
        return ResponseEntity.ok(toResponse(subscription));
    }

    /**
     * 更新订阅。
     *
     * @param id          订阅 ID
     * @param request     更新请求
     * @param httpRequest HTTP 请求
     * @return 更新后的订阅
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                       @RequestBody UpdateSubscriptionRequest request,
                                                       HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        // 先校验归属
        subscriptionService.findByIdAndMerchantId(id, callerMerchantId);
        Set<WebhookEventType> eventTypes = request.getEventTypes() != null
                ? parseEventTypes(request.getEventTypes()) : null;
        WebhookSubscription subscription = subscriptionService.updateSubscription(
                id,
                request.getTargetUrl(),
                eventTypes,
                request.getDescription()
        );
        return ResponseEntity.ok(toResponse(subscription));
    }

    /**
     * 暂停订阅。
     *
     * @param id          订阅 ID
     * @param httpRequest HTTP 请求
     * @return 暂停后的订阅
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable Long id,
                                                      HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        subscriptionService.findByIdAndMerchantId(id, callerMerchantId);
        WebhookSubscription subscription = subscriptionService.pauseSubscription(id);
        return ResponseEntity.ok(toResponse(subscription));
    }

    /**
     * 恢复订阅。
     *
     * @param id          订阅 ID
     * @param httpRequest HTTP 请求
     * @return 恢复后的订阅
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable Long id,
                                                       HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        subscriptionService.findByIdAndMerchantId(id, callerMerchantId);
        WebhookSubscription subscription = subscriptionService.resumeSubscription(id);
        return ResponseEntity.ok(toResponse(subscription));
    }

    /**
     * 删除订阅（软删除）。
     *
     * @param id          订阅 ID
     * @param httpRequest HTTP 请求
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                        HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        subscriptionService.findByIdAndMerchantId(id, callerMerchantId);
        subscriptionService.deleteSubscription(id);
        return ResponseEntity.noContent().build();
    }

    // --- Helpers ---

    /**
     * 将订阅实体转换为 API 响应 Map。
     *
     * <p>注意：signingSecret 不在列表/详情响应中返回（安全考虑），
     * 仅在创建时返回一次。</p>
     */
    private Map<String, Object> toResponse(WebhookSubscription sub) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sub.getId());
        m.put("merchant_id", sub.getMerchantId());
        m.put("target_url", sub.getTargetUrl());
        m.put("event_types", sub.getEventTypes());
        m.put("status", sub.getStatus().name());
        m.put("description", sub.getDescription());
        m.put("retry_policy", sub.getRetryPolicy());
        m.put("filter_expression", sub.getFilterExpression());
        m.put("created_at", sub.getCreatedAt() != null ? sub.getCreatedAt().toString() : null);
        m.put("updated_at", sub.getUpdatedAt() != null ? sub.getUpdatedAt().toString() : null);
        return m;
    }

    /**
     * 解析逗号分隔的事件类型字符串为 Set。
     */
    private Set<WebhookEventType> parseEventTypes(String eventTypesStr) {
        if (eventTypesStr == null || eventTypesStr.isBlank()) {
            throw new IllegalArgumentException("eventTypes 不能为空");
        }
        return Arrays.stream(eventTypesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return WebhookEventType.valueOf(s.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("未知的事件类型: " + s);
                    }
                })
                .collect(Collectors.toSet());
    }

    // --- Request DTOs ---

    public static class CreateSubscriptionRequest {
        private String targetUrl;
        private String eventTypes;
        private String description;

        public String getTargetUrl() { return targetUrl; }
        public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
        public String getEventTypes() { return eventTypes; }
        public void setEventTypes(String eventTypes) { this.eventTypes = eventTypes; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class UpdateSubscriptionRequest {
        private String targetUrl;
        private String eventTypes;
        private String description;

        public String getTargetUrl() { return targetUrl; }
        public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
        public String getEventTypes() { return eventTypes; }
        public void setEventTypes(String eventTypes) { this.eventTypes = eventTypes; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}