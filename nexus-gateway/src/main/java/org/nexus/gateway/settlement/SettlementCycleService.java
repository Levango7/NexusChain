package org.nexus.gateway.settlement;

import org.nexus.gateway.clearing.SettlementPeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 结算周期服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>解析商户结算周期配置（无配置默认 T1）</li>
 *   <li>判断支付是否已达结算条件</li>
 *   <li>计算最早可结算时间</li>
 *   <li>创建/更新商户结算配置</li>
 * </ul></p>
 *
 * <p>默认值从 T0 改为 T1，更安全的结算策略——未配置的商户默认次日结算，
 * 而非即时结算，降低资金风险。</p>
 */
@Service
public class SettlementCycleService {

    private static final Logger log = LoggerFactory.getLogger(SettlementCycleService.class);

    /** 默认结算周期（无配置时使用） */
    private static final SettlementPeriod DEFAULT_PERIOD = SettlementPeriod.T1;

    /** CUSTOM 模式 customDays 最小值 */
    private static final int CUSTOM_DAYS_MIN = 1;

    /** CUSTOM 模式 customDays 最大值 */
    private static final int CUSTOM_DAYS_MAX = 90;

    private final MerchantSettlementConfigRepository configRepository;

    public SettlementCycleService(MerchantSettlementConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /**
     * 解析商户结算周期字符串。
     *
     * <p>返回格式：
     * <ul>
     *   <li>T0/T1/T2/T3/WEEKLY/MONTHLY → 枚举名</li>
     *   <li>CUSTOM → "CUSTOM_{customDays}"（如 "CUSTOM_5"）</li>
     *   <li>无配置 → "T1"（默认值）</li>
     * </ul></p>
     *
     * @param merchantId 商户 ID（null 时返回默认 "T1"）
     * @return 结算周期字符串
     */
    public String resolveSettlementCycle(Long merchantId) {
        if (merchantId == null) {
            return DEFAULT_PERIOD.name();
        }
        Optional<MerchantSettlementConfig> configOpt = configRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return DEFAULT_PERIOD.name();
        }
        MerchantSettlementConfig config = configOpt.get();
        if (config.getSettlementPeriod() == SettlementPeriod.CUSTOM) {
            Integer days = config.getCustomDays();
            if (days != null) {
                return "CUSTOM_" + days;
            }
            // CUSTOM 但 customDays 为 null，回退默认
            return DEFAULT_PERIOD.name();
        }
        return config.getSettlementPeriod().name();
    }

    /**
     * 解析商户结算周期枚举值。
     *
     * @param merchantId 商户 ID（null 时返回默认 T1）
     * @return 结算周期枚举
     */
    public SettlementPeriod resolveSettlementPeriod(Long merchantId) {
        if (merchantId == null) {
            return DEFAULT_PERIOD;
        }
        Optional<MerchantSettlementConfig> configOpt = configRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return DEFAULT_PERIOD;
        }
        return configOpt.get().getSettlementPeriod();
    }

    /**
     * 判断该笔支付是否已达到结算条件。
     *
     * <p>各周期判断逻辑：
     * <ul>
     *   <li>T0: 总是返回 true（即时结算）</li>
     *   <li>T1: paidAt + 1天 <= now</li>
     *   <li>T2: paidAt + 2天 <= now</li>
     *   <li>T3: paidAt + 3天 <= now</li>
     *   <li>WEEKLY: paidAt + 7天 <= now</li>
     *   <li>MONTHLY: paidAt + 30天 <= now</li>
     *   <li>CUSTOM: paidAt + customDays天 <= now</li>
     * </ul></p>
     *
     * @param paidAt 支付完成时间
     * @param merchantId 商户 ID
     * @return 是否可结算
     */
    public boolean isEligibleForSettlement(LocalDateTime paidAt, Long merchantId) {
        if (paidAt == null) {
            return false;
        }
        SettlementPeriod period = resolveSettlementPeriod(merchantId);
        Integer customDays = null;
        if (period == SettlementPeriod.CUSTOM) {
            Optional<MerchantSettlementConfig> configOpt = configRepository.findByMerchantId(merchantId);
            if (configOpt.isPresent()) {
                customDays = configOpt.get().getCustomDays();
            }
        }
        LocalDateTime settlementTime = computeSettlementTime(paidAt, period, customDays);
        return !settlementTime.isAfter(LocalDateTime.now());
    }

    /**
     * 计算支付的最早可结算时间。
     *
     * @param paidAt 支付完成时间
     * @param period 结算周期
     * @param customDays 自定义天数（仅 CUSTOM 模式使用）
     * @return 最早可结算时间
     */
    public LocalDateTime computeSettlementTime(LocalDateTime paidAt, SettlementPeriod period, Integer customDays) {
        if (paidAt == null || period == null) {
            return paidAt;
        }
        return switch (period) {
            case T0 -> paidAt;
            case T1 -> paidAt.plusDays(1);
            case T2 -> paidAt.plusDays(2);
            case T3 -> paidAt.plusDays(3);
            case WEEKLY -> paidAt.plusDays(7);
            case MONTHLY -> paidAt.plusDays(30);
            case CUSTOM -> {
                if (customDays == null || customDays < CUSTOM_DAYS_MIN || customDays > CUSTOM_DAYS_MAX) {
                    yield paidAt.plusDays(1); // 无效 customDays 回退 T1
                }
                yield paidAt.plusDays(customDays);
            }
        };
    }

    /**
     * 创建或更新商户结算周期配置。
     *
     * <p>CUSTOM 模式校验 customDays 范围（1-90），非 CUSTOM 模式忽略 customDays。</p>
     *
     * @param merchantId 商户 ID（非空）
     * @param period 结算周期（非空）
     * @param customDays 自定义天数（仅 CUSTOM 模式使用）
     * @param autoSettleEnabled 是否自动结算（null 时默认 true）
     * @return 保存后的配置
     * @throws IllegalArgumentException merchantId/period 为空，或 CUSTOM 模式 customDays 无效
     */
    public MerchantSettlementConfig createOrUpdateConfig(Long merchantId, SettlementPeriod period,
                                                          Integer customDays, Boolean autoSettleEnabled) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
        if (period == null) {
            throw new IllegalArgumentException("period is required");
        }
        if (period == SettlementPeriod.CUSTOM) {
            if (customDays == null) {
                throw new IllegalArgumentException("customDays is required for CUSTOM period");
            }
            if (customDays < CUSTOM_DAYS_MIN || customDays > CUSTOM_DAYS_MAX) {
                throw new IllegalArgumentException(
                        "customDays must be between " + CUSTOM_DAYS_MIN + " and " + CUSTOM_DAYS_MAX);
            }
        }

        Optional<MerchantSettlementConfig> existingOpt = configRepository.findByMerchantId(merchantId);
        MerchantSettlementConfig config;
        if (existingOpt.isPresent()) {
            config = existingOpt.get();
            log.info("Updating settlement config for merchant={}: period={} -> {}",
                    merchantId, config.getSettlementPeriod(), period);
        } else {
            config = new MerchantSettlementConfig();
            config.setMerchantId(merchantId);
            log.info("Creating settlement config for merchant={}: period={}", merchantId, period);
        }

        config.setSettlementPeriod(period);
        // 非 CUSTOM 模式忽略 customDays，设为 null
        config.setCustomDays(period == SettlementPeriod.CUSTOM ? customDays : null);
        config.setAutoSettleEnabled(autoSettleEnabled != null ? autoSettleEnabled : true);

        return configRepository.save(config);
    }

    /**
     * 获取商户结算配置。
     *
     * @param merchantId 商户 ID
     * @return 结算配置（无配置返回 null）
     */
    public MerchantSettlementConfig getConfig(Long merchantId) {
        if (merchantId == null) {
            return null;
        }
        return configRepository.findByMerchantId(merchantId).orElse(null);
    }
}