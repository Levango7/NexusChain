package org.nexus.gateway.apiversion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API 版本废弃治理服务。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>查询版本策略与状态</li>
 *   <li>生成 RFC 7234 Deprecation / RFC 8594 Sunset / Link 响应头</li>
 *   <li>更新版本策略（管理员操作）</li>
 *   <li>发布 {@link ApiVersionDeprecatedEvent} 通知下游</li>
 * </ul>
 *
 * <p>与 {@link ApiVersionDeprecationFilter} 配合实现请求级别的自动化治理：
 * Filter 负责拦截与响应头注入，Service 负责策略查询与业务逻辑。</p>
 */
@Service
public class ApiVersionDeprecationService {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionDeprecationService.class);

    /** Deprecation 头名（RFC 7234） */
    public static final String DEPRECATION_HEADER = "Deprecation";

    /** Sunset 头名（RFC 8594） */
    public static final String SUNSET_HEADER = "Sunset";

    /** Link 头名 */
    public static final String LINK_HEADER = "Link";

    private final ApiVersionPolicyRepository policyRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ApiVersionDeprecationService(ApiVersionPolicyRepository policyRepository,
                                        ApplicationEventPublisher eventPublisher) {
        this.policyRepository = policyRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 获取指定版本的策略。
     *
     * @param version 版本标签，如 "v1"、"v2"
     * @return 版本策略（可能不存在）
     */
    public Optional<ApiVersionPolicy> getPolicy(String version) {
        return policyRepository.findByVersion(version);
    }

    /**
     * 检查版本状态。
     *
     * <p>如果策略不存在，默认返回 ACTIVE（未配置策略的版本视为正常服务）。</p>
     * <p>如果策略状态为 DEPRECATED 但 Sunset 日期已过，自动视为 SUNSET。</p>
     *
     * @param version 版本标签
     * @return 版本状态
     */
    public ApiVersionPolicy.VersionStatus checkVersionStatus(String version) {
        Optional<ApiVersionPolicy> opt = policyRepository.findByVersion(version);
        if (opt.isEmpty()) {
            return ApiVersionPolicy.VersionStatus.ACTIVE;
        }
        ApiVersionPolicy policy = opt.get();
        // DEPRECATED 且 Sunset 日期已过 → 自动视为 SUNSET
        if (policy.getStatus() == ApiVersionPolicy.VersionStatus.DEPRECATED
                && policy.getSunsetAt() != null
                && policy.getSunsetAt().isBefore(LocalDate.now())) {
            return ApiVersionPolicy.VersionStatus.SUNSET;
        }
        return policy.getStatus();
    }

    /**
     * 生成废弃相关的 HTTP 响应头。
     *
     * <p>遵循 RFC 7234（Deprecation）与 RFC 8594（Sunset）：</p>
     * <ul>
     *   <li>{@code Deprecation: true} — 标识此版本已废弃</li>
     *   <li>{@code Sunset: <date>} — 日落日期（ISO-8601）</li>
     *   <li>{@code Link: </api/v2/orders>; rel="successor-version"} — 后继版本链接</li>
     * </ul>
     *
     * @param version 版本标签
     * @return HTTP 头映射（如果版本非 DEPRECATED 则返回空 Map）
     */
    public Map<String, String> getDeprecationHeaders(String version) {
        Optional<ApiVersionPolicy> opt = policyRepository.findByVersion(version);
        if (opt.isEmpty()) {
            return Map.of();
        }
        ApiVersionPolicy policy = opt.get();
        if (policy.getStatus() != ApiVersionPolicy.VersionStatus.DEPRECATED
                && policy.getStatus() != ApiVersionPolicy.VersionStatus.SUNSET) {
            return Map.of();
        }

        Map<String, String> headers = new java.util.LinkedHashMap<>();

        // Deprecation 头
        headers.put(DEPRECATION_HEADER, "true");

        // Sunset 头
        if (policy.getSunsetAt() != null) {
            headers.put(SUNSET_HEADER, policy.getSunsetAt().toString());
        }

        // Link 头 — 后继版本链接
        if (policy.getSuccessorVersion() != null && !policy.getSuccessorVersion().isEmpty()) {
            String successorPath = "/api/" + policy.getSuccessorVersion() + "/orders";
            headers.put(LINK_HEADER,
                    "<" + successorPath + ">; rel=\"successor-version\"");
        }

        return headers;
    }

    /**
     * 列出所有版本策略。
     *
     * @return 所有版本策略列表
     */
    public List<ApiVersionPolicy> listAllPolicies() {
        return policyRepository.findAll();
    }

    /**
     * 更新版本策略。
     *
     * @param version           版本标签
     * @param status            新状态（可为 null 表示不更新）
     * @param sunsetAt          新日落日期（可为 null 表示不更新）
     * @param successorVersion  新后继版本（可为 null 表示不更新）
     * @param migrationGuide    新迁移指南（可为 null 表示不更新）
     * @return 更新后的策略，或空 Optional（版本不存在）
     */
    @Transactional
    public Optional<ApiVersionPolicy> updatePolicy(String version,
                                                   ApiVersionPolicy.VersionStatus status,
                                                   LocalDate sunsetAt,
                                                   String successorVersion,
                                                   String migrationGuide) {
        Optional<ApiVersionPolicy> opt = policyRepository.findByVersion(version);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        ApiVersionPolicy policy = opt.get();

        if (status != null) {
            policy.setStatus(status);
        }
        if (sunsetAt != null) {
            policy.setSunsetAt(sunsetAt);
        }
        if (successorVersion != null) {
            policy.setSuccessorVersion(successorVersion);
        }
        if (migrationGuide != null) {
            policy.setMigrationGuide(migrationGuide);
        }

        ApiVersionPolicy saved = policyRepository.save(policy);
        log.info("API version policy updated: version={}, status={}", version, saved.getStatus());
        return Optional.of(saved);
    }

    /**
     * 发布版本废弃事件。
     *
     * <p>通知下游监听器某版本已进入废弃/日落/退役状态，
     * 可据此触发告警、商户邮件、文档更新等。</p>
     *
     * @param version 版本标签
     */
    public void notifyDeprecation(String version) {
        Optional<ApiVersionPolicy> opt = policyRepository.findByVersion(version);
        if (opt.isEmpty()) {
            log.warn("Cannot notify deprecation: policy not found for version {}", version);
            return;
        }
        ApiVersionPolicy policy = opt.get();
        ApiVersionDeprecatedEvent event = new ApiVersionDeprecatedEvent(this, policy);
        eventPublisher.publishEvent(event);
        log.info("ApiVersionDeprecatedEvent published: version={}, status={}",
                version, policy.getStatus());
    }

    /**
     * 获取迁移指南。
     *
     * @param fromVersion 源版本
     * @param toVersion   目标版本
     * @return 迁移指南文本，或空 Optional（策略不存在）
     */
    public Optional<String> getMigrationGuide(String fromVersion, String toVersion) {
        Optional<ApiVersionPolicy> opt = policyRepository.findByVersion(fromVersion);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        ApiVersionPolicy policy = opt.get();
        // 如果指定了目标版本且与后继版本匹配，返回迁移指南
        if (toVersion != null && policy.getSuccessorVersion() != null
                && !toVersion.equals(policy.getSuccessorVersion())) {
            log.debug("Requested migration guide to {} but successor is {}",
                    toVersion, policy.getSuccessorVersion());
        }
        return Optional.ofNullable(policy.getMigrationGuide());
    }
}