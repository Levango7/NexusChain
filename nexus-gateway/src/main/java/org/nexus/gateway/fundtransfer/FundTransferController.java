package org.nexus.gateway.fundtransfer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.nexus.gateway.account.AccountType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 资金调拨 REST API — 调拨规则管理、手动调拨、归集策略管理。
 *
 * <p>路径前缀：{@code /api/v1/fund-transfers}</p>
 *
 * <p>提供以下功能：</p>
 * <ul>
 *   <li>调拨规则 CRUD（创建/更新/查询）</li>
 *   <li>手动调拨执行</li>
 *   <li>规则触发调拨执行</li>
 *   <li>归集策略 CRUD 和执行</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/fund-transfers")
@Tag(name = "FundTransfer", description = "资金调拨管理：调拨规则/手动调拨/归集策略")
public class FundTransferController {

    private static final Logger log = LoggerFactory.getLogger(FundTransferController.class);

    private final FundTransferService fundTransferService;
    private final CollectionStrategyService collectionStrategyService;

    public FundTransferController(FundTransferService fundTransferService,
                                   CollectionStrategyService collectionStrategyService) {
        this.fundTransferService = fundTransferService;
        this.collectionStrategyService = collectionStrategyService;
    }

    // === 调拨规则管理 ===

    /**
     * 创建调拨规则。
     *
     * @param body 规则配置请求体
     * @return 201 + 创建后的规则信息
     */
    @Operation(summary = "创建调拨规则")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/rules")
    public ResponseEntity<Map<String, Object>> createTransferRule(@RequestBody TransferRuleRequest body) {
        TransferRule rule = buildTransferRule(body);
        rule = fundTransferService.createTransferRule(rule);

        return ResponseEntity.status(HttpStatus.CREATED).body(toRuleMap(rule));
    }

    /**
     * 更新调拨规则。
     *
     * @param ruleCode 规则编号
     * @param body 更新请求体
     * @return 200 + 更新后的规则信息
     */
    @Operation(summary = "更新调拨规则")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/rules/{ruleCode}")
    public ResponseEntity<Map<String, Object>> updateTransferRule(
            @PathVariable String ruleCode,
            @RequestBody TransferRuleRequest body) {
        TransferRule updates = buildTransferRule(body);
        TransferRule rule = fundTransferService.updateTransferRule(ruleCode, updates);

        return ResponseEntity.ok(toRuleMap(rule));
    }

    /**
     * 查询调拨规则列表。
     *
     * @param triggerType 触发类型（可选）
     * @param enabled 启用状态（可选）
     * @return 200 + 规则列表
     */
    @Operation(summary = "查询调拨规则列表")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MERCHANT')")
    @GetMapping("/rules")
    public ResponseEntity<Map<String, Object>> getTransferRules(
            @RequestParam(required = false) TriggerType triggerType,
            @RequestParam(required = false) Boolean enabled) {
        List<TransferRule> rules = fundTransferService.getTransferRules(triggerType, enabled);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", rules.size());
        result.put("rules", rules.stream().map(this::toRuleMap).collect(Collectors.toList()));

        return ResponseEntity.ok(result);
    }

    /**
     * 查询单条调拨规则。
     *
     * @param ruleCode 规则编号
     * @return 200 + 规则信息
     */
    @Operation(summary = "查询单条调拨规则")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MERCHANT')")
    @GetMapping("/rules/{ruleCode}")
    public ResponseEntity<Map<String, Object>> getTransferRule(@PathVariable String ruleCode) {
        TransferRule rule = fundTransferService.getTransferRule(ruleCode)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + ruleCode));

        return ResponseEntity.ok(toRuleMap(rule));
    }

    // === 手动调拨 ===

    /**
     * 手动调拨 — 从商户的指定类型账户调拨资金到另一类型账户。
     *
     * @param body 调拨请求体
     * @return 200 + 调拨结果
     */
    @Operation(summary = "手动调拨")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/manual")
    public ResponseEntity<Map<String, Object>> manualTransfer(@RequestBody ManualTransferRequest body) {
        BigDecimal amount = new BigDecimal(body.getAmount());
        var account = fundTransferService.manualTransfer(
                body.getMerchantId(),
                AccountType.valueOf(body.getFromAccountType()),
                AccountType.valueOf(body.getToAccountType()),
                amount,
                body.getReference());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", body.getMerchantId());
        result.put("accountId", account.getAccountId());
        result.put("balance", account.getBalance());
        result.put("status", account.getStatus().name());

        return ResponseEntity.ok(result);
    }

    /**
     * 触发规则调拨 — 检查所有余额阈值规则并自动执行。
     *
     * @param merchantId 商户ID（可选，为空时检查所有商户）
     * @return 200 + 执行结果
     */
    @Operation(summary = "触发规则调拨")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/rules/execute")
    public ResponseEntity<Map<String, Object>> executeTransferRules(
            @RequestParam(required = false) Long merchantId) {
        int executedCount = fundTransferService.executeTransferRules(merchantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("executedCount", executedCount);

        return ResponseEntity.ok(result);
    }

    // === 归集策略管理 ===

    /**
     * 创建归集策略。
     *
     * @param body 策略配置请求体
     * @return 201 + 创建后的策略信息
     */
    @Operation(summary = "创建归集策略")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/collection-strategies")
    public ResponseEntity<Map<String, Object>> createCollectionStrategy(@RequestBody CollectionStrategyRequest body) {
        CollectionStrategy strategy = buildCollectionStrategy(body);
        strategy = collectionStrategyService.createStrategy(strategy);

        return ResponseEntity.status(HttpStatus.CREATED).body(toStrategyMap(strategy));
    }

    /**
     * 更新归集策略。
     *
     * @param strategyCode 策略编号
     * @param body 更新请求体
     * @return 200 + 更新后的策略信息
     */
    @Operation(summary = "更新归集策略")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/collection-strategies/{strategyCode}")
    public ResponseEntity<Map<String, Object>> updateCollectionStrategy(
            @PathVariable String strategyCode,
            @RequestBody CollectionStrategyRequest body) {
        CollectionStrategy updates = buildCollectionStrategy(body);
        CollectionStrategy strategy = collectionStrategyService.updateStrategy(strategyCode, updates);

        return ResponseEntity.ok(toStrategyMap(strategy));
    }

    /**
     * 查询归集策略列表。
     *
     * @param enabled 启用状态（可选）
     * @return 200 + 策略列表
     */
    @Operation(summary = "查询归集策略列表")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MERCHANT')")
    @GetMapping("/collection-strategies")
    public ResponseEntity<Map<String, Object>> getCollectionStrategies(
            @RequestParam(required = false) Boolean enabled) {
        List<CollectionStrategy> strategies = collectionStrategyService.getStrategies(enabled);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", strategies.size());
        result.put("strategies", strategies.stream().map(this::toStrategyMap).collect(Collectors.toList()));

        return ResponseEntity.ok(result);
    }

    /**
     * 执行归集策略。
     *
     * @param strategyCode 策略编号
     * @return 200 + 执行结果
     */
    @Operation(summary = "执行归集策略")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/collection-strategies/{strategyCode}/execute")
    public ResponseEntity<Map<String, Object>> executeCollectionStrategy(@PathVariable String strategyCode) {
        CollectionStrategyService.CollectionResult result =
                collectionStrategyService.executeCollectionStrategy(strategyCode);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("strategyCode", result.getStrategyCode());
        response.put("successCount", result.getSuccessCount());
        response.put("failCount", result.getFailCount());
        response.put("totalCollected", result.getTotalCollected());

        return ResponseEntity.ok(response);
    }

    // === 异常处理 ===

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_PARAM");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("操作失败: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "OPERATION_FAILED");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Map<String, Object>> handleNumberFormat(NumberFormatException e) {
        log.warn("金额格式错误: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_AMOUNT_FORMAT");
        error.put("message", "金额格式不正确");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // === 内部方法 ===

    private TransferRule buildTransferRule(TransferRuleRequest body) {
        TransferRule rule = new TransferRule();
        rule.setRuleName(body.getRuleName());
        rule.setRuleCode(body.getRuleCode());
        if (body.getFromAccountType() != null) {
            rule.setFromAccountType(AccountType.valueOf(body.getFromAccountType()));
        }
        if (body.getToAccountType() != null) {
            rule.setToAccountType(AccountType.valueOf(body.getToAccountType()));
        }
        if (body.getTriggerType() != null) {
            rule.setTriggerType(TriggerType.valueOf(body.getTriggerType()));
        }
        if (body.getThresholdAmount() != null) {
            rule.setThresholdAmount(new BigDecimal(body.getThresholdAmount()));
        }
        rule.setThresholdDirection(body.getThresholdDirection());
        if (body.getTransferAmountType() != null) {
            rule.setTransferAmountType(TransferAmountType.valueOf(body.getTransferAmountType()));
        }
        if (body.getTransferAmount() != null) {
            rule.setTransferAmount(new BigDecimal(body.getTransferAmount()));
        }
        if (body.getTransferPercentage() != null) {
            rule.setTransferPercentage(new BigDecimal(body.getTransferPercentage()));
        }
        rule.setCronExpression(body.getCronExpression());
        if (body.getEnabled() != null) {
            rule.setEnabled(body.getEnabled());
        }
        rule.setDescription(body.getDescription());
        return rule;
    }

    private CollectionStrategy buildCollectionStrategy(CollectionStrategyRequest body) {
        CollectionStrategy strategy = new CollectionStrategy();
        strategy.setStrategyName(body.getStrategyName());
        strategy.setStrategyCode(body.getStrategyCode());
        strategy.setSourceMerchantIds(body.getSourceMerchantIds());
        strategy.setTargetMerchantId(body.getTargetMerchantId());
        if (body.getSourceAccountType() != null) {
            strategy.setSourceAccountType(AccountType.valueOf(body.getSourceAccountType()));
        }
        if (body.getTargetAccountType() != null) {
            strategy.setTargetAccountType(AccountType.valueOf(body.getTargetAccountType()));
        }
        strategy.setCollectionType(body.getCollectionType());
        if (body.getCollectionAmount() != null) {
            strategy.setCollectionAmount(new BigDecimal(body.getCollectionAmount()));
        }
        if (body.getCollectionPercentage() != null) {
            strategy.setCollectionPercentage(new BigDecimal(body.getCollectionPercentage()));
        }
        if (body.getMinRetainAmount() != null) {
            strategy.setMinRetainAmount(new BigDecimal(body.getMinRetainAmount()));
        }
        strategy.setCronExpression(body.getCronExpression());
        if (body.getEnabled() != null) {
            strategy.setEnabled(body.getEnabled());
        }
        strategy.setDescription(body.getDescription());
        return strategy;
    }

    private Map<String, Object> toRuleMap(TransferRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rule.getId());
        map.put("ruleName", rule.getRuleName());
        map.put("ruleCode", rule.getRuleCode());
        map.put("fromAccountType", rule.getFromAccountType());
        map.put("toAccountType", rule.getToAccountType());
        map.put("triggerType", rule.getTriggerType());
        map.put("thresholdAmount", rule.getThresholdAmount());
        map.put("thresholdDirection", rule.getThresholdDirection());
        map.put("transferAmountType", rule.getTransferAmountType());
        map.put("transferAmount", rule.getTransferAmount());
        map.put("transferPercentage", rule.getTransferPercentage());
        map.put("cronExpression", rule.getCronExpression());
        map.put("enabled", rule.getEnabled());
        map.put("description", rule.getDescription());
        return map;
    }

    private Map<String, Object> toStrategyMap(CollectionStrategy strategy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", strategy.getId());
        map.put("strategyName", strategy.getStrategyName());
        map.put("strategyCode", strategy.getStrategyCode());
        map.put("sourceMerchantIds", strategy.getSourceMerchantIds());
        map.put("targetMerchantId", strategy.getTargetMerchantId());
        map.put("sourceAccountType", strategy.getSourceAccountType());
        map.put("targetAccountType", strategy.getTargetAccountType());
        map.put("collectionType", strategy.getCollectionType());
        map.put("collectionAmount", strategy.getCollectionAmount());
        map.put("collectionPercentage", strategy.getCollectionPercentage());
        map.put("minRetainAmount", strategy.getMinRetainAmount());
        map.put("cronExpression", strategy.getCronExpression());
        map.put("enabled", strategy.getEnabled());
        map.put("description", strategy.getDescription());
        return map;
    }

    // === DTO ===

    public static class TransferRuleRequest {
        private String ruleName;
        private String ruleCode;
        private String fromAccountType;
        private String toAccountType;
        private String triggerType;
        private String thresholdAmount;
        private String thresholdDirection;
        private String transferAmountType;
        private String transferAmount;
        private String transferPercentage;
        private String cronExpression;
        private Boolean enabled;
        private String description;

        public String getRuleName() { return ruleName; }
        public void setRuleName(String ruleName) { this.ruleName = ruleName; }

        public String getRuleCode() { return ruleCode; }
        public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }

        public String getFromAccountType() { return fromAccountType; }
        public void setFromAccountType(String fromAccountType) { this.fromAccountType = fromAccountType; }

        public String getToAccountType() { return toAccountType; }
        public void setToAccountType(String toAccountType) { this.toAccountType = toAccountType; }

        public String getTriggerType() { return triggerType; }
        public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

        public String getThresholdAmount() { return thresholdAmount; }
        public void setThresholdAmount(String thresholdAmount) { this.thresholdAmount = thresholdAmount; }

        public String getThresholdDirection() { return thresholdDirection; }
        public void setThresholdDirection(String thresholdDirection) { this.thresholdDirection = thresholdDirection; }

        public String getTransferAmountType() { return transferAmountType; }
        public void setTransferAmountType(String transferAmountType) { this.transferAmountType = transferAmountType; }

        public String getTransferAmount() { return transferAmount; }
        public void setTransferAmount(String transferAmount) { this.transferAmount = transferAmount; }

        public String getTransferPercentage() { return transferPercentage; }
        public void setTransferPercentage(String transferPercentage) { this.transferPercentage = transferPercentage; }

        public String getCronExpression() { return cronExpression; }
        public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class ManualTransferRequest {
        private Long merchantId;
        private String fromAccountType;
        private String toAccountType;
        private String amount;
        private String reference;

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

        public String getFromAccountType() { return fromAccountType; }
        public void setFromAccountType(String fromAccountType) { this.fromAccountType = fromAccountType; }

        public String getToAccountType() { return toAccountType; }
        public void setToAccountType(String toAccountType) { this.toAccountType = toAccountType; }

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }

        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
    }

    public static class CollectionStrategyRequest {
        private String strategyName;
        private String strategyCode;
        private String sourceMerchantIds;
        private Long targetMerchantId;
        private String sourceAccountType;
        private String targetAccountType;
        private String collectionType;
        private String collectionAmount;
        private String collectionPercentage;
        private String minRetainAmount;
        private String cronExpression;
        private Boolean enabled;
        private String description;

        public String getStrategyName() { return strategyName; }
        public void setStrategyName(String strategyName) { this.strategyName = strategyName; }

        public String getStrategyCode() { return strategyCode; }
        public void setStrategyCode(String strategyCode) { this.strategyCode = strategyCode; }

        public String getSourceMerchantIds() { return sourceMerchantIds; }
        public void setSourceMerchantIds(String sourceMerchantIds) { this.sourceMerchantIds = sourceMerchantIds; }

        public Long getTargetMerchantId() { return targetMerchantId; }
        public void setTargetMerchantId(Long targetMerchantId) { this.targetMerchantId = targetMerchantId; }

        public String getSourceAccountType() { return sourceAccountType; }
        public void setSourceAccountType(String sourceAccountType) { this.sourceAccountType = sourceAccountType; }

        public String getTargetAccountType() { return targetAccountType; }
        public void setTargetAccountType(String targetAccountType) { this.targetAccountType = targetAccountType; }

        public String getCollectionType() { return collectionType; }
        public void setCollectionType(String collectionType) { this.collectionType = collectionType; }

        public String getCollectionAmount() { return collectionAmount; }
        public void setCollectionAmount(String collectionAmount) { this.collectionAmount = collectionAmount; }

        public String getCollectionPercentage() { return collectionPercentage; }
        public void setCollectionPercentage(String collectionPercentage) { this.collectionPercentage = collectionPercentage; }

        public String getMinRetainAmount() { return minRetainAmount; }
        public void setMinRetainAmount(String minRetainAmount) { this.minRetainAmount = minRetainAmount; }

        public String getCronExpression() { return cronExpression; }
        public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}