package org.nexus.gateway.reconciliation.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 对账规则配置服务 — 管理多层级对账规则的 CRUD 和配置解析。
 *
 * <p>配置优先级（从高到低）：</p>
 * <ol>
 *   <li>商户+渠道级：merchantId != null && channelType != null</li>
 *   <li>商户级默认：merchantId != null && channelType == null</li>
 *   <li>渠道级默认：merchantId == null && channelType != null</li>
 *   <li>全局默认：merchantId == null && channelType == null</li>
 * </ol>
 *
 * <p>当低优先级配置不存在时，自动回退到更高层级的默认配置。
 * 如果所有层级都不存在，返回内置默认值（金额容差 0.01，时间窗口 5 分钟）。</p>
 */
@Service
public class ReconciliationRuleConfigService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRuleConfigService.class);

    /** 内置默认金额容差 */
    private static final BigDecimal DEFAULT_AMOUNT_TOLERANCE = new BigDecimal("0.01");

    /** 内置默认时间窗口（分钟） */
    private static final int DEFAULT_TIME_WINDOW_MINUTES = 5;

    private final ReconciliationRuleConfigRepository repository;

    public ReconciliationRuleConfigService(ReconciliationRuleConfigRepository repository) {
        this.repository = repository;
    }

    // ==================== 配置解析 ====================

    /**
     * 按优先级解析对账规则配置。
     *
     * <p>查找顺序：商户+渠道 → 商户默认 → 渠道默认 → 全局默认 → 内置默认</p>
     *
     * @param merchantId  商户 ID（可为 null）
     * @param channelType 渠道类型（可为 null）
     * @return 匹配到的规则配置，如果数据库中没有则返回包含内置默认值的配置
     */
    public ReconciliationRuleConfig resolveConfig(Long merchantId, String channelType) {
        // 1. 商户+渠道级
        if (merchantId != null && channelType != null) {
            Optional<ReconciliationRuleConfig> config =
                    repository.findByMerchantIdAndChannelType(merchantId, channelType);
            if (config.isPresent()) {
                log.debug("命中商户+渠道级配置: merchantId={}, channelType={}", merchantId, channelType);
                return config.get();
            }
        }

        // 2. 商户级默认
        if (merchantId != null) {
            Optional<ReconciliationRuleConfig> config =
                    repository.findByMerchantIdAndChannelTypeIsNull(merchantId);
            if (config.isPresent()) {
                log.debug("命中商户级默认配置: merchantId={}", merchantId);
                return config.get();
            }
        }

        // 3. 渠道级默认
        if (channelType != null) {
            Optional<ReconciliationRuleConfig> config =
                    repository.findByMerchantIdIsNullAndChannelType(channelType);
            if (config.isPresent()) {
                log.debug("命中渠道级默认配置: channelType={}", channelType);
                return config.get();
            }
        }

        // 4. 全局默认
        Optional<ReconciliationRuleConfig> globalConfig =
                repository.findByMerchantIdIsNullAndChannelTypeIsNull();
        if (globalConfig.isPresent()) {
            log.debug("命中全局默认配置");
            return globalConfig.get();
        }

        // 5. 内置默认
        log.debug("未找到任何配置，使用内置默认值");
        return buildDefaultConfig();
    }

    /**
     * 获取金额容差。
     */
    public BigDecimal getAmountTolerance(Long merchantId, String channelType) {
        return resolveConfig(merchantId, channelType).getAmountTolerance();
    }

    /**
     * 获取时间窗口（分钟）。
     */
    public int getTimeWindowMinutes(Long merchantId, String channelType) {
        Integer minutes = resolveConfig(merchantId, channelType).getTimeWindowMinutes();
        return minutes != null ? minutes : DEFAULT_TIME_WINDOW_MINUTES;
    }

    /**
     * 获取状态映射 JSON。
     */
    public String getStatusMappingJson(Long merchantId, String channelType) {
        return resolveConfig(merchantId, channelType).getStatusMappingJson();
    }

    // ==================== CRUD 操作 ====================

    /**
     * 创建规则配置。
     */
    @Transactional
    public ReconciliationRuleConfig createConfig(ReconciliationRuleConfig config, String operator) {
        config.setCreatedBy(operator);
        config.setUpdatedBy(operator);
        ReconciliationRuleConfig saved = repository.save(config);
        log.info("创建对账规则配置: id={}, merchantId={}, channelType={}",
                saved.getId(), saved.getMerchantId(), saved.getChannelType());
        return saved;
    }

    /**
     * 更新规则配置。
     */
    @Transactional
    public ReconciliationRuleConfig updateConfig(Long id, ReconciliationRuleConfig update, String operator) {
        ReconciliationRuleConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规则配置不存在: id=" + id));

        if (update.getAmountTolerance() != null) {
            existing.setAmountTolerance(update.getAmountTolerance());
        }
        if (update.getTimeWindowMinutes() != null) {
            existing.setTimeWindowMinutes(update.getTimeWindowMinutes());
        }
        if (update.getStatusMappingJson() != null) {
            existing.setStatusMappingJson(update.getStatusMappingJson());
        }
        existing.setUpdatedBy(operator);

        ReconciliationRuleConfig saved = repository.save(existing);
        log.info("更新对账规则配置: id={}, operator={}", id, operator);
        return saved;
    }

    /**
     * 删除规则配置。
     */
    @Transactional
    public void deleteConfig(Long id) {
        repository.deleteById(id);
        log.info("删除对账规则配置: id={}", id);
    }

    /**
     * 查询单个规则配置。
     */
    public Optional<ReconciliationRuleConfig> getConfig(Long id) {
        return repository.findById(id);
    }

    /**
     * 查询商户的所有规则配置。
     */
    public List<ReconciliationRuleConfig> getConfigsByMerchant(Long merchantId) {
        return repository.findByMerchantId(merchantId);
    }

    /**
     * 查询所有规则配置。
     */
    public List<ReconciliationRuleConfig> getAllConfigs() {
        return repository.findAll();
    }

    // ==================== 内部方法 ====================

    /**
     * 构建内置默认配置（不持久化）。
     */
    private ReconciliationRuleConfig buildDefaultConfig() {
        ReconciliationRuleConfig config = new ReconciliationRuleConfig();
        config.setAmountTolerance(DEFAULT_AMOUNT_TOLERANCE);
        config.setTimeWindowMinutes(DEFAULT_TIME_WINDOW_MINUTES);
        return config;
    }
}