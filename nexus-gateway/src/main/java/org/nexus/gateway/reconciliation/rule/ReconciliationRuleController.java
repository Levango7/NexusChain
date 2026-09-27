package org.nexus.gateway.reconciliation.rule;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 对账规则配置 REST API。
 *
 * <p>提供多层级对账规则的 CRUD 接口，支持按商户、渠道维度配置
 * 金额容差、时间窗口和状态映射。</p>
 *
 * <p>P0-1 修复：所有端点添加商户归属校验，防止 IDOR 攻击。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/reconciliation/rules} — 查询当前商户的所有规则配置</li>
 *   <li>{@code GET /api/reconciliation/rules/{id}} — 查询单个规则配置</li>
 *   <li>{@code GET /api/reconciliation/rules/merchant/{merchantId}} — 查询商户的所有配置</li>
 *   <li>{@code GET /api/reconciliation/rules/resolve} — 按优先级解析规则配置</li>
 *   <li>{@code POST /api/reconciliation/rules} — 创建规则配置</li>
 *   <li>{@code PUT /api/reconciliation/rules/{id}} — 更新规则配置</li>
 *   <li>{@code DELETE /api/reconciliation/rules/{id}} — 删除规则配置</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reconciliation/rules")
public class ReconciliationRuleController {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRuleController.class);

    private final ReconciliationRuleConfigService ruleConfigService;
    private final StatusMappingService statusMappingService;
    private final MerchantOwnershipGuard ownershipGuard;

    public ReconciliationRuleController(ReconciliationRuleConfigService ruleConfigService,
                                         StatusMappingService statusMappingService,
                                         MerchantOwnershipGuard ownershipGuard) {
        this.ruleConfigService = ruleConfigService;
        this.statusMappingService = statusMappingService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 查询当前商户的所有规则配置。
     *
     * <p>P0-1：只返回当前认证商户的配置，不再返回所有商户的配置。</p>
     */
    @GetMapping
    public ResponseEntity<List<ReconciliationRuleConfig>> getAllConfigs(HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        List<ReconciliationRuleConfig> configs = ruleConfigService.getConfigsByMerchant(callerMerchantId);
        return ResponseEntity.ok(configs);
    }

    /**
     * 查询单个规则配置。
     *
     * <p>P0-1：规则配置必须属于当前认证商户。</p>
     */
    @GetMapping("/{id}")
    public ResponseEntity<ReconciliationRuleConfig> getConfig(@PathVariable Long id,
                                                              HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        return ruleConfigService.getConfig(id)
                .map(config -> {
                    ownershipGuard.requireOwned(callerMerchantId, config.getMerchantId(), "rule-config", id);
                    return ResponseEntity.ok(config);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查询商户的所有规则配置。
     *
     * <p>P0-1：请求的 merchantId 必须与认证上下文一致。</p>
     */
    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<ReconciliationRuleConfig>> getConfigsByMerchant(
            @PathVariable Long merchantId,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "merchant", merchantId);
        List<ReconciliationRuleConfig> configs = ruleConfigService.getConfigsByMerchant(merchantId);
        return ResponseEntity.ok(configs);
    }

    /**
     * 按优先级解析规则配置。
     *
     * <p>P0-1：如果指定了 merchantId，必须与认证上下文一致；如果未指定，使用认证商户 ID。</p>
     *
     * <p>查询参数：merchantId（可选）、channelType（可选）</p>
     */
    @GetMapping("/resolve")
    public ResponseEntity<ReconciliationRuleConfig> resolveConfig(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) String channelType,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        if (merchantId != null) {
            ownershipGuard.requireOwned(callerMerchantId, merchantId, "rule-config", merchantId);
        } else {
            merchantId = callerMerchantId;
        }
        ReconciliationRuleConfig config = ruleConfigService.resolveConfig(merchantId, channelType);
        return ResponseEntity.ok(config);
    }

    /**
     * 创建规则配置。
     *
     * <p>P0-1：请求体中的 merchantId 必须与认证上下文一致。</p>
     * <p>P0-5：移除 X-Operator defaultValue="system"，operator 为 null 时使用 callerMerchantId。</p>
     */
    @PostMapping
    public ResponseEntity<ReconciliationRuleConfig> createConfig(
            @RequestBody CreateRuleConfigRequest request,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, request.getMerchantId(), "rule-config", request.getMerchantId());

        String effectiveOperator = operator != null ? operator : String.valueOf(callerMerchantId);

        ReconciliationRuleConfig config = new ReconciliationRuleConfig();
        config.setMerchantId(request.getMerchantId());
        config.setChannelType(request.getChannelType());
        config.setAmountTolerance(request.getAmountTolerance() != null
                ? request.getAmountTolerance() : new BigDecimal("0.01"));
        config.setTimeWindowMinutes(request.getTimeWindowMinutes() != null
                ? request.getTimeWindowMinutes() : 5);
        config.setStatusMappingJson(request.getStatusMappingJson());

        ReconciliationRuleConfig saved = ruleConfigService.createConfig(config, effectiveOperator);
        return ResponseEntity.ok(saved);
    }

    /**
     * 更新规则配置。
     *
     * <p>P0-1：规则配置必须属于当前认证商户。</p>
     * <p>P0-5：移除 X-Operator defaultValue="system"，operator 为 null 时使用 callerMerchantId。</p>
     */
    @PutMapping("/{id}")
    public ResponseEntity<ReconciliationRuleConfig> updateConfig(
            @PathVariable Long id,
            @RequestBody UpdateRuleConfigRequest request,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);

        // 先加载现有 config 获取其 merchantId 进行归属校验
        ReconciliationRuleConfig existing = ruleConfigService.getConfig(id)
                .orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        ownershipGuard.requireOwned(callerMerchantId, existing.getMerchantId(), "rule-config", id);

        String effectiveOperator = operator != null ? operator : String.valueOf(callerMerchantId);

        ReconciliationRuleConfig update = new ReconciliationRuleConfig();
        update.setAmountTolerance(request.getAmountTolerance());
        update.setTimeWindowMinutes(request.getTimeWindowMinutes());
        update.setStatusMappingJson(request.getStatusMappingJson());

        try {
            ReconciliationRuleConfig saved = ruleConfigService.updateConfig(id, update, effectiveOperator);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除规则配置。
     *
     * <p>P0-1：规则配置必须属于当前认证商户。</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id,
                                             HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);

        // 先加载现有 config 获取其 merchantId 进行归属校验
        ReconciliationRuleConfig existing = ruleConfigService.getConfig(id)
                .orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        ownershipGuard.requireOwned(callerMerchantId, existing.getMerchantId(), "rule-config", id);

        ruleConfigService.deleteConfig(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 验证状态映射 JSON 格式。
     *
     * <p>纯验证端点，无商户数据，不需要归属校验。</p>
     */
    @PostMapping("/validate-status-mapping")
    public ResponseEntity<Map<String, Object>> validateStatusMapping(
            @RequestBody Map<String, String> statusMapping) {
        String json = statusMappingService.serializeStatusMapping(statusMapping);
        boolean valid = json != null;
        return ResponseEntity.ok(Map.of(
                "valid", valid,
                "serialized", json != null ? json : ""
        ));
    }

    // === Request DTOs ===

    public static class CreateRuleConfigRequest {
        private Long merchantId;
        private String channelType;
        private BigDecimal amountTolerance;
        private Integer timeWindowMinutes;
        private String statusMappingJson;

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

        public String getChannelType() { return channelType; }
        public void setChannelType(String channelType) { this.channelType = channelType; }

        public BigDecimal getAmountTolerance() { return amountTolerance; }
        public void setAmountTolerance(BigDecimal amountTolerance) { this.amountTolerance = amountTolerance; }

        public Integer getTimeWindowMinutes() { return timeWindowMinutes; }
        public void setTimeWindowMinutes(Integer timeWindowMinutes) { this.timeWindowMinutes = timeWindowMinutes; }

        public String getStatusMappingJson() { return statusMappingJson; }
        public void setStatusMappingJson(String statusMappingJson) { this.statusMappingJson = statusMappingJson; }
    }

    public static class UpdateRuleConfigRequest {
        private BigDecimal amountTolerance;
        private Integer timeWindowMinutes;
        private String statusMappingJson;

        public BigDecimal getAmountTolerance() { return amountTolerance; }
        public void setAmountTolerance(BigDecimal amountTolerance) { this.amountTolerance = amountTolerance; }

        public Integer getTimeWindowMinutes() { return timeWindowMinutes; }
        public void setTimeWindowMinutes(Integer timeWindowMinutes) { this.timeWindowMinutes = timeWindowMinutes; }

        public String getStatusMappingJson() { return statusMappingJson; }
        public void setStatusMappingJson(String statusMappingJson) { this.statusMappingJson = statusMappingJson; }
    }
}