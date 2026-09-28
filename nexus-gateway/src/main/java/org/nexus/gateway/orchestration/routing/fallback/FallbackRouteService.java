package org.nexus.gateway.orchestration.routing.fallback;

import org.nexus.gateway.orchestration.routing.RoutingJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 降级路由服务（Wave 16 模块三）。
 *
 * <p>按配置解析「主 connector 不可用时的有序备选列表」。配置分层：
 * 商户级（merchant_id 非空）优先于全局/渠道级（merchant_id 为空），
 * 同层内 priority 大者优先；取第一条条件命中的 enabled 配置。</p>
 *
 * <p>解析结果不校验 connector 是否真实注册/激活——由调用方
 * （{@code RoutingEngine} / 补偿执行方）结合 {@code ConnectorRegistry} 过滤。</p>
 */
@Service
public class FallbackRouteService {

    private static final Logger log = LoggerFactory.getLogger(FallbackRouteService.class);

    private final FallbackRouteConfigRepository repository;

    public FallbackRouteService(FallbackRouteConfigRepository repository) {
        this.repository = repository;
    }

    // ==================== 链路解析 ====================

    /**
     * 解析主 connector 的降级备选链。
     *
     * @param merchantId       商户 ID（可为 null）
     * @param primaryConnector 主 connector id
     * @param amount           金额（分，与 conditions 语义一致）
     * @param currency         币种
     * @return 有序备选 connector id 列表（不含 primary 自身）；无命中配置时为空列表
     */
    public List<String> resolveFallbackChain(Long merchantId, String primaryConnector,
                                             BigDecimal amount, String currency) {
        if (primaryConnector == null || primaryConnector.isBlank()) {
            return List.of();
        }
        // 商户级优先
        List<FallbackRouteConfig> enabled = repository.findByEnabledTrueOrderByPriorityDesc();
        List<FallbackRouteConfig> merchantScoped = enabled.stream()
                .filter(c -> merchantId != null && merchantId.equals(c.getMerchantId()))
                .toList();
        FallbackRouteConfig matched = firstMatching(merchantScoped, primaryConnector, amount, currency);
        if (matched == null) {
            List<FallbackRouteConfig> globalScoped = enabled.stream()
                    .filter(c -> c.getMerchantId() == null)
                    .toList();
            matched = firstMatching(globalScoped, primaryConnector, amount, currency);
        }
        if (matched == null) {
            return List.of();
        }
        List<String> chain = Arrays.stream(matched.getFallbackConnectorsCsv().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !s.equals(primaryConnector))
                .distinct()
                .collect(Collectors.toList());
        log.debug("Fallback chain resolved: merchantId={}, primary={}, chain={}",
                merchantId, primaryConnector, chain);
        return chain;
    }

    private FallbackRouteConfig firstMatching(List<FallbackRouteConfig> configs, String primaryConnector,
                                              BigDecimal amount, String currency) {
        for (FallbackRouteConfig config : configs) {
            if (!primaryConnector.equals(config.getPrimaryConnector())) continue;
            if (matches(config.getConditionsJson(), amount, currency)) return config;
        }
        return null;
    }

    /** 条件匹配语义与 RoutingRule.matches 一致（currency/amount_gte/amount_lte，分）。 */
    private boolean matches(String conditionsJson, BigDecimal amount, String currency) {
        if (conditionsJson == null || conditionsJson.isBlank()) return true;
        Map<String, String> conditions;
        try {
            conditions = RoutingJsonCodec.fromJsonToMap(conditionsJson);
        } catch (IllegalArgumentException e) {
            log.warn("Fallback conditions_json invalid, treat as unconditional: {}", e.getMessage());
            return true;
        }
        if (conditions.isEmpty()) return true;
        String condCurrency = conditions.get("currency");
        if (condCurrency != null && !condCurrency.equals(currency)) return false;
        long amt = amount == null ? 0L : amount.longValue();
        String amtGte = conditions.get("amount_gte");
        if (amtGte != null && amt < Long.parseLong(amtGte.trim())) return false;
        String amtLte = conditions.get("amount_lte");
        if (amtLte != null && amt > Long.parseLong(amtLte.trim())) return false;
        return true;
    }

    // ==================== CRUD ====================

    public List<FallbackRouteConfig> listAll() {
        return repository.findAll();
    }

    public List<FallbackRouteConfig> listByMerchant(Long merchantId) {
        return repository.findByMerchantId(merchantId);
    }

    public FallbackRouteConfig get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("降级路由配置不存在: id=" + id));
    }

    @Transactional
    public FallbackRouteConfig create(FallbackRouteConfig config) {
        validate(config);
        FallbackRouteConfig saved = repository.save(config);
        log.info("创建降级路由配置: id={}, merchantId={}, primary={}",
                saved.getId(), saved.getMerchantId(), saved.getPrimaryConnector());
        return saved;
    }

    @Transactional
    public FallbackRouteConfig update(Long id, FallbackRouteConfig update) {
        FallbackRouteConfig existing = get(id);
        if (update.getPrimaryConnector() != null) {
            existing.setPrimaryConnector(update.getPrimaryConnector());
        }
        if (update.getFallbackConnectorsCsv() != null) {
            existing.setFallbackConnectorsCsv(update.getFallbackConnectorsCsv());
        }
        if (update.getConditionsJson() != null) {
            existing.setConditionsJson(update.getConditionsJson());
        }
        existing.setPriority(update.getPriority());
        existing.setEnabled(update.isEnabled());
        validate(existing);
        FallbackRouteConfig saved = repository.save(existing);
        log.info("更新降级路由配置: id={}", id);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        repository.deleteById(id);
        log.info("删除降级路由配置: id={}", id);
    }

    private void validate(FallbackRouteConfig config) {
        if (config.getPrimaryConnector() == null || config.getPrimaryConnector().isBlank()) {
            throw new IllegalArgumentException("primary_connector 不能为空");
        }
        if (config.getFallbackConnectorsCsv() == null || config.getFallbackConnectorsCsv().isBlank()) {
            throw new IllegalArgumentException("fallback_connectors_csv 不能为空");
        }
        List<String> chain = new ArrayList<>(List.of(config.getFallbackConnectorsCsv().split(",")));
        if (chain.stream().map(String::trim).allMatch(String::isEmpty)) {
            throw new IllegalArgumentException("fallback_connectors_csv 至少需要一个备选 connector");
        }
        if (config.getConditionsJson() != null && !config.getConditionsJson().isBlank()) {
            // 触发解析校验（非法 JSON 抛 IllegalArgumentException）
            RoutingJsonCodec.fromJsonToMap(config.getConditionsJson());
        }
    }
}
