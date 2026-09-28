package org.nexus.gateway.orchestration.routing.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.routing.RoutingJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商户路由画像服务（Wave 16 模块六）。
 *
 * <p>把商户/行业级路由配置解析为 {@link ResolvedRoutingProfile}，解析顺序：</p>
 * <ol>
 *   <li>商户级配置（merchantId 命中，priority 降序取第一条 enabled）；</li>
 *   <li>行业级配置（调用方提供 industry 时命中）；</li>
 *   <li>无命中 → 默认画像（profileId=default：金额分层取全局阈值、无偏好/排除）。</li>
 * </ol>
 *
 * <p>金额分层：配置了 {@code amount_tier_rules_json} 时按 [min,max) 区间匹配（分），
 * 否则按全局阈值（{@code small-amount-threshold} / {@code large-amount-threshold}）。
 * 高峰时段：配置了 {@code time_window_rules_json} 时按本地时间窗口匹配（支持跨零点），
 * 否则按全局 peak-hours 配置。</p>
 */
@Service
public class MerchantRoutingProfileService {

    private static final Logger log = LoggerFactory.getLogger(MerchantRoutingProfileService.class);

    private final MerchantRoutingProfileRepository repository;
    private final RoutingWave16Properties properties;

    @Autowired
    public MerchantRoutingProfileService(MerchantRoutingProfileRepository repository,
                                         RoutingWave16Properties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    // ==================== 解析 ====================

    /**
     * 解析路由画像。
     *
     * @param merchantId 商户 ID（可空）
     * @param industry   行业分类（可空；商户级未命中且提供时才查行业级）
     * @param amount     金额（分）
     * @return 永不返回 null（无命中时返回默认画像）
     */
    public ResolvedRoutingProfile resolve(Long merchantId, String industry, BigDecimal amount) {
        MerchantRoutingProfile profile = findMerchantProfile(merchantId);
        if (profile == null && industry != null && !industry.isBlank()) {
            profile = findIndustryProfile(industry);
        }
        if (profile == null) {
            return defaultProfile(merchantId, industry, amount);
        }

        List<String> objectives = RoutingJsonCodec.fromJsonToList(profile.getPreferredObjectivesJson());
        List<String> preferred = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        parseConnectorPreferences(profile.getConnectorPreferencesJson(), preferred, excluded);

        long amountValue = amount == null ? 0L : amount.longValue();
        return new ResolvedRoutingProfile(
                profile.getProfileId(),
                profile.getMerchantId(),
                profile.getIndustry(),
                resolveAmountTier(profile.getAmountTierRulesJson(), amountValue),
                inPeakHours(profile.getTimeWindowRulesJson()),
                objectives,
                List.copyOf(preferred),
                List.copyOf(excluded));
    }

    private MerchantRoutingProfile findMerchantProfile(Long merchantId) {
        if (merchantId == null) return null;
        return repository.findByMerchantIdAndEnabledTrueOrderByPriorityDesc(merchantId)
                .stream().findFirst().orElse(null);
    }

    private MerchantRoutingProfile findIndustryProfile(String industry) {
        return repository.findByIndustryAndEnabledTrueOrderByPriorityDesc(industry)
                .stream().findFirst().orElse(null);
    }

    private ResolvedRoutingProfile defaultProfile(Long merchantId, String industry, BigDecimal amount) {
        long amountValue = amount == null ? 0L : amount.longValue();
        return new ResolvedRoutingProfile("default", merchantId, industry,
                tierByGlobalThresholds(amountValue),
                globalPeakHours(),
                List.of(), List.of(), List.of());
    }

    // ==================== 金额分层 ====================

    String resolveAmountTier(String tierRulesJson, long amount) {
        List<AmountTierRule> rules = parseTierRules(tierRulesJson);
        if (!rules.isEmpty()) {
            for (AmountTierRule rule : rules) {
                long min = rule.min == null ? Long.MIN_VALUE : rule.min;
                long max = rule.max == null ? Long.MAX_VALUE : rule.max;
                if (amount >= min && amount < max) {
                    return rule.tier == null ? ResolvedRoutingProfile.TIER_MEDIUM : rule.tier;
                }
            }
            log.debug("Amount {} matched no tier rule, falling back to global thresholds", amount);
        }
        return tierByGlobalThresholds(amount);
    }

    private String tierByGlobalThresholds(long amount) {
        RoutingWave16Properties.Profile p = properties.getProfile();
        if (amount < p.getSmallAmountThreshold()) return ResolvedRoutingProfile.TIER_SMALL;
        if (amount >= p.getLargeAmountThreshold()) return ResolvedRoutingProfile.TIER_LARGE;
        return ResolvedRoutingProfile.TIER_MEDIUM;
    }

    private List<AmountTierRule> parseTierRules(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<AmountTierRule> rules = RoutingJsonCodec.readValue(json,
                    new TypeReference<List<AmountTierRule>>() {});
            return rules == null ? List.of() : rules;
        } catch (IllegalArgumentException e) {
            log.warn("amount_tier_rules_json invalid, using global thresholds: {}", e.getMessage());
            return List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AmountTierRule {
        public Long min;
        public Long max;
        public String tier;
    }

    // ==================== 高峰时段 ====================

    boolean inPeakHours(String timeWindowRulesJson) {
        List<TimeWindowRule> rules = parseTimeWindowRules(timeWindowRulesJson);
        if (rules.isEmpty()) return globalPeakHours();
        LocalTime now = LocalTime.now();
        for (TimeWindowRule rule : rules) {
            LocalTime start = parseTime(rule.start);
            LocalTime end = parseTime(rule.end);
            if (start == null || end == null) continue;
            if (start.isBefore(end)) {
                if (!now.isBefore(start) && now.isBefore(end)) return true;
            } else {
                // 跨零点窗口（如 22:00-06:00）
                if (!now.isBefore(start) || now.isBefore(end)) return true;
            }
        }
        return false;
    }

    private boolean globalPeakHours() {
        RoutingWave16Properties.Profile p = properties.getProfile();
        LocalTime start = parseTime(p.getPeakHoursStart());
        LocalTime end = parseTime(p.getPeakHoursEnd());
        if (start == null || end == null) return false;
        LocalTime now = LocalTime.now();
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private List<TimeWindowRule> parseTimeWindowRules(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<TimeWindowRule> rules = RoutingJsonCodec.readValue(json,
                    new TypeReference<List<TimeWindowRule>>() {});
            return rules == null ? List.of() : rules;
        } catch (IllegalArgumentException e) {
            log.warn("time_window_rules_json invalid, using global peak hours: {}", e.getMessage());
            return List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TimeWindowRule {
        public String start;
        public String end;
        public String name;
    }

    // ==================== Connector 偏好 ====================

    private void parseConnectorPreferences(String json, List<String> preferred, List<String> excluded) {
        if (json == null || json.isBlank()) return;
        try {
            Map<String, List<String>> prefs = RoutingJsonCodec.readValue(json,
                    new TypeReference<Map<String, List<String>>>() {});
            if (prefs == null) return;
            List<String> p = prefs.get("preferred");
            List<String> e = prefs.get("excluded");
            if (p != null) preferred.addAll(p);
            if (e != null) excluded.addAll(e);
        } catch (IllegalArgumentException ex) {
            log.warn("connector_preferences_json invalid, ignoring: {}", ex.getMessage());
        }
    }

    // ==================== CRUD ====================

    public List<MerchantRoutingProfile> listAll() {
        return repository.findAll();
    }

    public List<MerchantRoutingProfile> listByMerchant(Long merchantId) {
        return repository.findByMerchantId(merchantId);
    }

    public MerchantRoutingProfile get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("路由画像配置不存在: id=" + id));
    }

    @Transactional
    public MerchantRoutingProfile create(MerchantRoutingProfile profile) {
        validate(profile);
        repository.findByProfileId(profile.getProfileId()).ifPresent(existing -> {
            throw new IllegalArgumentException("profile_id 已存在: " + profile.getProfileId());
        });
        MerchantRoutingProfile saved = repository.save(profile);
        log.info("创建商户路由画像: id={}, profileId={}, merchantId={}",
                saved.getId(), saved.getProfileId(), saved.getMerchantId());
        return saved;
    }

    @Transactional
    public MerchantRoutingProfile update(Long id, MerchantRoutingProfile update) {
        MerchantRoutingProfile existing = get(id);
        if (update.getIndustry() != null) existing.setIndustry(update.getIndustry());
        if (update.getAmountTierRulesJson() != null) existing.setAmountTierRulesJson(update.getAmountTierRulesJson());
        if (update.getTimeWindowRulesJson() != null) existing.setTimeWindowRulesJson(update.getTimeWindowRulesJson());
        if (update.getPreferredObjectivesJson() != null) existing.setPreferredObjectivesJson(update.getPreferredObjectivesJson());
        if (update.getConnectorPreferencesJson() != null) existing.setConnectorPreferencesJson(update.getConnectorPreferencesJson());
        existing.setPriority(update.getPriority());
        existing.setEnabled(update.isEnabled());
        validate(existing);
        MerchantRoutingProfile saved = repository.save(existing);
        log.info("更新商户路由画像: id={}", id);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        repository.deleteById(id);
        log.info("删除商户路由画像: id={}", id);
    }

    private void validate(MerchantRoutingProfile profile) {
        if (profile.getProfileId() == null || profile.getProfileId().isBlank()) {
            throw new IllegalArgumentException("profile_id 不能为空");
        }
        if (profile.getMerchantId() == null && (profile.getIndustry() == null || profile.getIndustry().isBlank())) {
            throw new IllegalArgumentException("行业级配置必须指定 industry（merchantId 为空时）");
        }
        if (profile.getAmountTierRulesJson() != null && !profile.getAmountTierRulesJson().isBlank()) {
            parseTierRules(profile.getAmountTierRulesJson()); // 非法 JSON 在此抛出
        }
        if (profile.getTimeWindowRulesJson() != null && !profile.getTimeWindowRulesJson().isBlank()) {
            parseTimeWindowRules(profile.getTimeWindowRulesJson());
        }
        if (profile.getConnectorPreferencesJson() != null && !profile.getConnectorPreferencesJson().isBlank()) {
            List<String> preferred = new ArrayList<>();
            List<String> excluded = new ArrayList<>();
            parseConnectorPreferences(profile.getConnectorPreferencesJson(), preferred, excluded);
        }
    }
}
