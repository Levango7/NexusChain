package org.nexus.gateway.reconciliation.rule;

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
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/reconciliation/rules} — 查询所有规则配置</li>
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

    public ReconciliationRuleController(ReconciliationRuleConfigService ruleConfigService,
                                         StatusMappingService statusMappingService) {
        this.ruleConfigService = ruleConfigService;
        this.statusMappingService = statusMappingService;
    }

    /**
     * 查询所有规则配置。
     */
    @GetMapping
    public ResponseEntity<List<ReconciliationRuleConfig>> getAllConfigs() {
        List<ReconciliationRuleConfig> configs = ruleConfigService.getAllConfigs();
        return ResponseEntity.ok(configs);
    }

    /**
     * 查询单个规则配置。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ReconciliationRuleConfig> getConfig(@PathVariable Long id) {
        return ruleConfigService.getConfig(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查询商户的所有规则配置。
     */
    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<ReconciliationRuleConfig>> getConfigsByMerchant(
            @PathVariable Long merchantId) {
        List<ReconciliationRuleConfig> configs = ruleConfigService.getConfigsByMerchant(merchantId);
        return ResponseEntity.ok(configs);
    }

    /**
     * 按优先级解析规则配置。
     *
     * <p>查询参数：merchantId（可选）、channelType（可选）</p>
     */
    @GetMapping("/resolve")
    public ResponseEntity<ReconciliationRuleConfig> resolveConfig(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) String channelType) {
        ReconciliationRuleConfig config = ruleConfigService.resolveConfig(merchantId, channelType);
        return ResponseEntity.ok(config);
    }

    /**
     * 创建规则配置。
     */
    @PostMapping
    public ResponseEntity<ReconciliationRuleConfig> createConfig(
            @RequestBody CreateRuleConfigRequest request,
            @RequestHeader(value = "X-Operator", defaultValue = "system") String operator) {
        ReconciliationRuleConfig config = new ReconciliationRuleConfig();
        config.setMerchantId(request.getMerchantId());
        config.setChannelType(request.getChannelType());
        config.setAmountTolerance(request.getAmountTolerance() != null
                ? request.getAmountTolerance() : new BigDecimal("0.01"));
        config.setTimeWindowMinutes(request.getTimeWindowMinutes() != null
                ? request.getTimeWindowMinutes() : 5);
        config.setStatusMappingJson(request.getStatusMappingJson());

        ReconciliationRuleConfig saved = ruleConfigService.createConfig(config, operator);
        return ResponseEntity.ok(saved);
    }

    /**
     * 更新规则配置。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ReconciliationRuleConfig> updateConfig(
            @PathVariable Long id,
            @RequestBody UpdateRuleConfigRequest request,
            @RequestHeader(value = "X-Operator", defaultValue = "system") String operator) {
        ReconciliationRuleConfig update = new ReconciliationRuleConfig();
        update.setAmountTolerance(request.getAmountTolerance());
        update.setTimeWindowMinutes(request.getTimeWindowMinutes());
        update.setStatusMappingJson(request.getStatusMappingJson());

        try {
            ReconciliationRuleConfig saved = ruleConfigService.updateConfig(id, update, operator);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除规则配置。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        ruleConfigService.deleteConfig(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 验证状态映射 JSON 格式。
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