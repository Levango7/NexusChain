package org.nexus.gateway.reserve;

import org.nexus.gateway.account.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 备付金服务 — 备付金配置管理、查询和自动补充。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #createOrUpdateConfig} — 创建或更新备付金配置</li>
 *   <li>{@link #getConfig} — 查询商户备付金配置</li>
 *   <li>{@link #getReserveBalance} — 查询商户备付金余额</li>
 *   <li>{@link #autoReplenish} — 自动补充备付金（从指定账户类型转入）</li>
 *   <li>{@link #manualReplenish} — 手动补充备付金</li>
 *   <li>{@link #checkAndAlert} — 检查备付金余额并设置预警级别</li>
 * </ul>
 *
 * <p>所有资金操作通过 {@link AccountService} 执行。</p>
 */
@Service
public class ReserveFundService {

    private static final Logger log = LoggerFactory.getLogger(ReserveFundService.class);

    /** 预警级别常量 */
    private static final String ALERT_WARNING = "WARNING";
    private static final String ALERT_CRITICAL = "CRITICAL";
    private static final String ALERT_EMERGENCY = "EMERGENCY";

    private final ReserveConfigRepository configRepository;
    private final AccountService accountService;
    private final MerchantAccountRepository accountRepository;

    public ReserveFundService(ReserveConfigRepository configRepository,
                               AccountService accountService,
                               MerchantAccountRepository accountRepository) {
        this.configRepository = configRepository;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
    }

    // === 配置管理 ===

    /**
     * 创建或更新备付金配置。
     *
     * @param merchantId 商户ID
     * @param config 配置内容
     * @return 保存后的配置
     * @throws IllegalArgumentException merchantId 为空
     */
    @Transactional
    public ReserveConfig createOrUpdateConfig(Long merchantId, ReserveConfig config) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId 不能为空");
        }

        config.setMerchantId(merchantId);

        Optional<ReserveConfig> existingOpt = configRepository.findByMerchantId(merchantId);
        if (existingOpt.isPresent()) {
            ReserveConfig existing = existingOpt.get();
            // 更新已有配置
            if (config.getMinReserveAmount() != null) {
                existing.setMinReserveAmount(config.getMinReserveAmount());
            }
            if (config.getMaxReserveAmount() != null) {
                existing.setMaxReserveAmount(config.getMaxReserveAmount());
            }
            if (config.getAutoReplenishEnabled() != null) {
                existing.setAutoReplenishEnabled(config.getAutoReplenishEnabled());
            }
            if (config.getReplenishThreshold() != null) {
                existing.setReplenishThreshold(config.getReplenishThreshold());
            }
            if (config.getReplenishAmount() != null) {
                existing.setReplenishAmount(config.getReplenishAmount());
            }
            if (config.getReplenishSourceAccountType() != null) {
                existing.setReplenishSourceAccountType(config.getReplenishSourceAccountType());
            }
            if (config.getAlertThreshold() != null) {
                existing.setAlertThreshold(config.getAlertThreshold());
            }
            if (config.getMonitoringEnabled() != null) {
                existing.setMonitoringEnabled(config.getMonitoringEnabled());
            }
            if (config.getDescription() != null) {
                existing.setDescription(config.getDescription());
            }
            config = configRepository.save(existing);
            log.info("更新备付金配置: merchantId={}", merchantId);
        } else {
            if (config.getConfigCode() == null) {
                config.setConfigCode("RC_" + merchantId);
            }
            config = configRepository.save(config);
            log.info("创建备付金配置: merchantId={}", merchantId);
        }

        return config;
    }

    /**
     * 查询商户备付金配置。
     *
     * @param merchantId 商户ID
     * @return 备付金配置（可能为空）
     */
    @Transactional(readOnly = true)
    public Optional<ReserveConfig> getConfig(Long merchantId) {
        return configRepository.findByMerchantId(merchantId);
    }

    /**
     * 查询所有启用监控的备付金配置。
     *
     * @return 备付金配置列表
     */
    @Transactional(readOnly = true)
    public List<ReserveConfig> getMonitoredConfigs() {
        return configRepository.findByMonitoringEnabled(true);
    }

    /**
     * 查询所有启用自动补充的备付金配置。
     *
     * @return 备付金配置列表
     */
    @Transactional(readOnly = true)
    public List<ReserveConfig> getAutoReplenishConfigs() {
        return configRepository.findByAutoReplenishEnabled(true);
    }

    // === 余额查询 ===

    /**
     * 查询商户备付金余额。
     *
     * @param merchantId 商户ID
     * @return 备付金余额（无备付金账户时返回 0）
     */
    @Transactional(readOnly = true)
    public BigDecimal getReserveBalance(Long merchantId) {
        return accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.RESERVE)
                .map(MerchantAccount::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    // === 自动补充 ===

    /**
     * 自动补充备付金 — 检查所有启用自动补充的配置，低于阈值时自动补充。
     *
     * <p>对每个启用自动补充的配置：</p>
     * <ol>
     *   <li>获取商户备付金余额</li>
     *   <li>判断是否低于补充阈值</li>
     *   <li>低于阈值时从指定账户类型转入补充金额</li>
     * </ol>
     *
     * @return 成功补充的次数
     */
    @Transactional
    public int autoReplenish() {
        List<ReserveConfig> configs = configRepository.findByAutoReplenishEnabled(true);
        int replenishCount = 0;

        for (ReserveConfig config : configs) {
            try {
                if (replenishSingleMerchant(config)) {
                    replenishCount++;
                }
            } catch (Exception e) {
                log.warn("备付金自动补充失败: merchantId={}, error={}",
                        config.getMerchantId(), e.getMessage());
            }
        }

        log.info("备付金自动补充完成: 共检查 {} 个配置, 成功补充 {} 个", configs.size(), replenishCount);
        return replenishCount;
    }

    /**
     * 为单个商户执行备付金自动补充。
     */
    private boolean replenishSingleMerchant(ReserveConfig config) {
        BigDecimal reserveBalance = getReserveBalance(config.getMerchantId());

        if (config.getReplenishThreshold() == null || config.getReplenishAmount() == null) {
            return false;
        }

        // 备付金余额低于阈值时触发补充
        if (reserveBalance.compareTo(config.getReplenishThreshold()) >= 0) {
            return false;
        }

        log.info("备付金自动补充: merchantId={}, reserveBalance={}, threshold={}, replenishAmount={}",
                config.getMerchantId(), reserveBalance, config.getReplenishThreshold(), config.getReplenishAmount());

        // 确保源账户和目标账户存在
        MerchantAccount sourceAccount = accountService.getOrCreateAccount(
                config.getMerchantId(), config.getReplenishSourceAccountType());
        MerchantAccount reserveAccount = accountService.getOrCreateAccount(
                config.getMerchantId(), AccountType.RESERVE);

        // 检查源账户余额是否充足
        if (sourceAccount.getBalance().compareTo(config.getReplenishAmount()) < 0) {
            log.warn("备付金补充源账户余额不足: merchantId={}, sourceBalance={}, replenishAmount={}",
                    config.getMerchantId(), sourceAccount.getBalance(), config.getReplenishAmount());
            return false;
        }

        // 执行转账
        accountService.transfer(sourceAccount.getAccountId(), reserveAccount.getAccountId(),
                config.getReplenishAmount());

        log.info("备付金自动补充成功: merchantId={}, amount={}",
                config.getMerchantId(), config.getReplenishAmount());
        return true;
    }

    // === 手动补充 ===

    /**
     * 手动补充备付金 — 从指定账户类型手动转入资金到备付金账户。
     *
     * @param merchantId 商户ID
     * @param amount 补充金额
     * @param fromAccountType 来源账户类型（默认 BALANCE）
     * @return 备付金账户（补充后）
     * @throws IllegalArgumentException 金额 <= 0
     * @throws IllegalStateException 余额不足
     */
    @Transactional
    public MerchantAccount manualReplenish(Long merchantId, BigDecimal amount, AccountType fromAccountType) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("补充金额必须大于 0");
        }

        log.info("手动补充备付金: merchantId={}, amount={}, fromAccountType={}",
                merchantId, amount, fromAccountType);

        MerchantAccount sourceAccount = accountService.getOrCreateAccount(merchantId, fromAccountType);
        MerchantAccount reserveAccount = accountService.getOrCreateAccount(merchantId, AccountType.RESERVE);

        return accountService.transfer(sourceAccount.getAccountId(), reserveAccount.getAccountId(), amount);
    }

    // === 监控预警 ===

    /**
     * 检查备付金余额并设置预警级别。
     *
     * <p>预警级别判断逻辑：</p>
     * <ul>
     *   <li>余额 < alertThreshold → EMERGENCY（紧急）</li>
     *   <li>余额 < replenishThreshold → CRITICAL（严重）</li>
     *   <li>余额 < minReserveAmount → WARNING（预警）</li>
     *   <li>余额 >= minReserveAmount → 清除预警（null）</li>
     * </ul>
     *
     * @param merchantId 商户ID
     * @return 当前预警级别（null 表示正常）
     */
    @Transactional
    public String checkAndAlert(Long merchantId) {
        Optional<ReserveConfig> configOpt = configRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return null;
        }

        ReserveConfig config = configOpt.get();
        BigDecimal reserveBalance = getReserveBalance(merchantId);

        String alertFlag = null;
        if (config.getAlertThreshold() != null
                && reserveBalance.compareTo(config.getAlertThreshold()) < 0) {
            alertFlag = ALERT_EMERGENCY;
        } else if (config.getReplenishThreshold() != null
                && reserveBalance.compareTo(config.getReplenishThreshold()) < 0) {
            alertFlag = ALERT_CRITICAL;
        } else if (reserveBalance.compareTo(config.getMinReserveAmount()) < 0) {
            alertFlag = ALERT_WARNING;
        }

        // 更新配置中的预警级别
        if (!java.util.Objects.equals(config.getAlertFlag(), alertFlag)) {
            config.setAlertFlag(alertFlag);
            configRepository.save(config);
            log.info("备付金预警级别变更: merchantId={}, alertFlag={}", merchantId, alertFlag);
        }

        // 更新账户的预警级别
        final String finalAlertFlag = alertFlag;
        accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.RESERVE)
                .ifPresent(account -> {
                    account.setAlertFlag(finalAlertFlag);
                    accountRepository.save(account);
                });

        return alertFlag;
    }

    /**
     * 检查所有启用监控的商户备付金余额并设置预警级别。
     *
     * @return 各商户预警结果列表
     */
    @Transactional
    public List<AlertResult> checkAllMonitored() {
        List<ReserveConfig> configs = configRepository.findByMonitoringEnabled(true);
        List<AlertResult> results = new java.util.ArrayList<>();

        for (ReserveConfig config : configs) {
            try {
                String alertFlag = checkAndAlert(config.getMerchantId());
                results.add(new AlertResult(config.getMerchantId(), alertFlag));
            } catch (Exception e) {
                log.warn("备付金监控检查失败: merchantId={}, error={}",
                        config.getMerchantId(), e.getMessage());
            }
        }

        return results;
    }

    // === 预警结果 DTO ===

    /**
     * 备付金预警检查结果。
     */
    public static class AlertResult {
        private final Long merchantId;
        private final String alertFlag;

        public AlertResult(Long merchantId, String alertFlag) {
            this.merchantId = merchantId;
            this.alertFlag = alertFlag;
        }

        public Long getMerchantId() { return merchantId; }
        public String getAlertFlag() { return alertFlag; }
    }
}