package org.nexus.gateway.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Webhook 订阅管理服务。
 *
 * <p>提供订阅的创建、更新、暂停、恢复、删除和查询功能。创建订阅时自动生成
 * signingSecret（SecureRandom 32 字节 → Base64 编码），用于回调 HMAC-SHA256
 * 签名验证。删除为软删除（标记 DELETED 状态），不物理移除记录。</p>
 *
 * <p>所有写操作均在事务中执行。事件类型参数接受逗号分隔字符串或 Set&lt;WebhookEventType&gt;，
 * 内部统一转换为逗号分隔的字符串存储。</p>
 */
@Service
@Transactional
public class WebhookSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionService.class);

    /** signingSecret 生成使用的随机字节数（32 字节 = 256 位）。 */
    private static final int SECRET_BYTES = 32;

    private final WebhookSubscriptionRepository repository;
    private final WebhookUrlValidator urlValidator;
    private final SecureRandom secureRandom;

    public WebhookSubscriptionService(WebhookSubscriptionRepository repository,
                                      WebhookUrlValidator urlValidator) {
        this.repository = repository;
        this.urlValidator = urlValidator;
        this.secureRandom = new SecureRandom();
    }

    /**
     * 创建 Webhook 订阅。
     *
     * <p>创建时自动生成 signingSecret，状态设为 ACTIVE，默认重试策略为
     * 3 次指数退避。</p>
     *
     * @param merchantId  商户 ID
     * @param targetUrl   回调目标 URL（须通过 SSRF 校验）
     * @param eventTypes  订阅的事件类型集合
     * @param description 订阅描述（可选）
     * @return 创建的订阅实体
     * @throws IllegalArgumentException URL 非法或事件类型为空
     */
    public WebhookSubscription createSubscription(Long merchantId,
                                                   String targetUrl,
                                                   Set<WebhookEventType> eventTypes,
                                                   String description) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId 不能为空");
        }
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("targetUrl 不能为空");
        }
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException("eventTypes 不能为空");
        }

        // SSRF 防护：校验目标 URL
        urlValidator.validate(targetUrl);

        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setMerchantId(merchantId);
        subscription.setTargetUrl(targetUrl);
        subscription.setEventTypes(formatEventTypes(eventTypes));
        subscription.setStatus(WebhookSubscriptionStatus.ACTIVE);
        subscription.setSigningSecret(generateSigningSecret());
        subscription.setDescription(description);
        subscription.setRetryPolicy("{\"maxRetries\":3,\"backoff\":\"exponential\"}");

        WebhookSubscription saved = repository.save(subscription);
        log.info("Webhook 订阅已创建: id={}, merchantId={}, targetUrl={}, eventTypes={}",
                saved.getId(), merchantId, targetUrl, saved.getEventTypes());
        return saved;
    }

    /**
     * 更新 Webhook 订阅。
     *
     * <p>仅允许更新 targetUrl、eventTypes 和 description。signingSecret 保持不变。
     * 若订阅已删除则抛出异常。</p>
     *
     * @param id          订阅 ID
     * @param targetUrl   新的回调目标 URL（null 表示不更新）
     * @param eventTypes  新的事件类型集合（null 表示不更新）
     * @param description 新的描述（null 表示不更新）
     * @return 更新后的订阅实体
     * @throws IllegalArgumentException 订阅不存在或已删除
     */
    public WebhookSubscription updateSubscription(Long id,
                                                   String targetUrl,
                                                   Set<WebhookEventType> eventTypes,
                                                   String description) {
        WebhookSubscription subscription = findById(id);
        if (subscription.getStatus() == WebhookSubscriptionStatus.DELETED) {
            throw new IllegalArgumentException("订阅已删除，无法更新: id=" + id);
        }

        if (targetUrl != null && !targetUrl.isBlank()) {
            urlValidator.validate(targetUrl);
            subscription.setTargetUrl(targetUrl);
        }
        if (eventTypes != null && !eventTypes.isEmpty()) {
            subscription.setEventTypes(formatEventTypes(eventTypes));
        }
        if (description != null) {
            subscription.setDescription(description);
        }

        WebhookSubscription saved = repository.save(subscription);
        log.info("Webhook 订阅已更新: id={}", id);
        return saved;
    }

    /**
     * 暂停订阅（ACTIVE → PAUSED）。
     *
     * @param id 订阅 ID
     * @return 更新后的订阅实体
     * @throws IllegalArgumentException 订阅不存在、已删除或已暂停
     */
    public WebhookSubscription pauseSubscription(Long id) {
        WebhookSubscription subscription = findById(id);
        if (subscription.getStatus() == WebhookSubscriptionStatus.DELETED) {
            throw new IllegalArgumentException("订阅已删除，无法暂停: id=" + id);
        }
        if (subscription.getStatus() == WebhookSubscriptionStatus.PAUSED) {
            throw new IllegalArgumentException("订阅已处于暂停状态: id=" + id);
        }
        subscription.setStatus(WebhookSubscriptionStatus.PAUSED);
        WebhookSubscription saved = repository.save(subscription);
        log.info("Webhook 订阅已暂停: id={}", id);
        return saved;
    }

    /**
     * 恢复订阅（PAUSED → ACTIVE）。
     *
     * @param id 订阅 ID
     * @return 更新后的订阅实体
     * @throws IllegalArgumentException 订阅不存在、已删除或已活跃
     */
    public WebhookSubscription resumeSubscription(Long id) {
        WebhookSubscription subscription = findById(id);
        if (subscription.getStatus() == WebhookSubscriptionStatus.DELETED) {
            throw new IllegalArgumentException("订阅已删除，无法恢复: id=" + id);
        }
        if (subscription.getStatus() == WebhookSubscriptionStatus.ACTIVE) {
            throw new IllegalArgumentException("订阅已处于活跃状态: id=" + id);
        }
        subscription.setStatus(WebhookSubscriptionStatus.ACTIVE);
        WebhookSubscription saved = repository.save(subscription);
        log.info("Webhook 订阅已恢复: id={}", id);
        return saved;
    }

    /**
     * 删除订阅（软删除，标记 DELETED）。
     *
     * @param id 订阅 ID
     * @throws IllegalArgumentException 订阅不存在或已删除
     */
    public void deleteSubscription(Long id) {
        WebhookSubscription subscription = findById(id);
        if (subscription.getStatus() == WebhookSubscriptionStatus.DELETED) {
            throw new IllegalArgumentException("订阅已删除: id=" + id);
        }
        subscription.setStatus(WebhookSubscriptionStatus.DELETED);
        repository.save(subscription);
        log.info("Webhook 订阅已删除（软删除）: id={}", id);
    }

    /**
     * 列出租户的所有订阅（不含已删除）。
     *
     * @param merchantId 商户 ID
     * @return 订阅列表
     */
    @Transactional(readOnly = true)
    public List<WebhookSubscription> listSubscriptions(Long merchantId) {
        return repository.findByMerchantIdAndStatusNot(merchantId, WebhookSubscriptionStatus.DELETED);
    }

    /**
     * 获取某事件类型的活跃订阅（供投递服务调用）。
     *
     * <p>查询所有 ACTIVE 状态且 eventTypes 包含指定事件类型的订阅。
     * 由于 eventTypes 是逗号分隔字符串，使用 LIKE 模糊匹配。</p>
     *
     * @param merchantId 商户 ID
     * @param eventType  事件类型
     * @return 活跃订阅列表
     */
    @Transactional(readOnly = true)
    public List<WebhookSubscription> getActiveSubscriptions(Long merchantId, WebhookEventType eventType) {
        List<WebhookSubscription> active = repository.findByMerchantIdAndStatus(
                merchantId, WebhookSubscriptionStatus.ACTIVE);
        return active.stream()
                .filter(sub -> sub.isSubscribedTo(eventType))
                .collect(Collectors.toList());
    }

    /**
     * 按 ID 查询订阅。
     *
     * @param id 订阅 ID
     * @return 订阅实体
     * @throws IllegalArgumentException 订阅不存在
     */
    @Transactional(readOnly = true)
    public WebhookSubscription findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("订阅不存在: id=" + id));
    }

    /**
     * 按 ID 和商户 ID 查询订阅（用于归属校验）。
     *
     * @param id         订阅 ID
     * @param merchantId 商户 ID
     * @return 订阅实体（Optional）
     */
    @Transactional(readOnly = true)
    public WebhookSubscription findByIdAndMerchantId(Long id, Long merchantId) {
        return repository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "订阅不存在或不属于该商户: id=" + id + ", merchantId=" + merchantId));
    }

    /**
     * 验证目标 URL 合法性（委托给 WebhookUrlValidator）。
     *
     * @param url 目标 URL
     * @throws IllegalArgumentException URL 非法
     */
    public void validateTargetUrl(String url) {
        urlValidator.validate(url);
    }

    // --- 内部方法 ---

    /**
     * 生成 signingSecret：32 字节随机数 → Base64 编码。
     */
    private String generateSigningSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 将事件类型集合格式化为逗号分隔字符串。
     */
    private String formatEventTypes(Set<WebhookEventType> eventTypes) {
        return eventTypes.stream()
                .map(WebhookEventType::name)
                .collect(Collectors.joining(","));
    }
}