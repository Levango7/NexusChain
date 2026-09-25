package org.nexus.gateway.risk.link;

import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 余额预警服务 — 配置预警阈值、检查余额并触发预警通知。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #configureAlert} — 配置预警阈值（校验 warning > critical > emergency > 0）</li>
 *   <li>{@link #checkBalanceAndAlert} — 检查余额并触发预警级别跃迁 + alertFlag 更新 + 通知发送</li>
 * </ul>
 *
 * <p>预警级别跃迁规则：只允许级别升级（WARNING → CRITICAL → EMERGENCY），
 * 不允许降级。降级需通过人工干预恢复为 NULL（正常）。</p>
 *
 * <p>预警通知中余额数据脱敏（仅保留后 4 位），防止敏感信息泄露。
 * （来源经验：2026-09-25-jpa-optimistic-lock-account-system）</p>
 */
@Service
public class BalanceAlertService {

    private static final Logger log = LoggerFactory.getLogger(BalanceAlertService.class);

    private final BalanceAlertConfigRepository configRepository;
    private final MerchantAccountRepository accountRepository;
    private final AccountService accountService;
    private final BalanceAlertNotifier notifier;

    public BalanceAlertService(BalanceAlertConfigRepository configRepository,
                                MerchantAccountRepository accountRepository,
                                AccountService accountService,
                                BalanceAlertNotifier notifier) {
        this.configRepository = configRepository;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.notifier = notifier;
    }

    // ==================== 预警配置 ====================

    /**
     * 配置预警阈值 — 阈值校验（warning > critical > emergency > 0）。
     *
     * <p>如果商户已有配置则更新，否则创建新配置。</p>
     *
     * @param merchantId         商户 ID
     * @param warningThreshold   预警阈值
     * @param criticalThreshold  严重阈值
     * @param emergencyThreshold 紧急阈值
     * @param notifyChannels     通知渠道（逗号分隔）
     * @return 预警配置
     * @throws IllegalArgumentException 阈值不满足 warning > critical > emergency > 0
     */
    @Transactional
    public BalanceAlertConfig configureAlert(Long merchantId, BigDecimal warningThreshold,
                                              BigDecimal criticalThreshold, BigDecimal emergencyThreshold,
                                              String notifyChannels) {
        // 阈值校验：warning > critical > emergency > 0
        if (warningThreshold == null || criticalThreshold == null || emergencyThreshold == null) {
            throw new IllegalArgumentException("阈值不能为空");
        }
        if (warningThreshold.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("预警阈值必须大于 0");
        }
        if (criticalThreshold.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("严重阈值必须大于 0");
        }
        if (emergencyThreshold.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("紧急阈值必须大于 0");
        }
        if (warningThreshold.compareTo(criticalThreshold) <= 0) {
            throw new IllegalArgumentException("预警阈值必须大于严重阈值: warning=" + warningThreshold
                    + ", critical=" + criticalThreshold);
        }
        if (criticalThreshold.compareTo(emergencyThreshold) <= 0) {
            throw new IllegalArgumentException("严重阈值必须大于紧急阈值: critical=" + criticalThreshold
                    + ", emergency=" + emergencyThreshold);
        }

        BalanceAlertConfig config = configRepository.findByMerchantId(merchantId)
                .orElseGet(() -> {
                    BalanceAlertConfig c = new BalanceAlertConfig();
                    c.setMerchantId(merchantId);
                    return c;
                });

        config.setWarningThreshold(warningThreshold);
        config.setCriticalThreshold(criticalThreshold);
        config.setEmergencyThreshold(emergencyThreshold);
        config.setEnabled(true);
        if (notifyChannels != null && !notifyChannels.isBlank()) {
            config.setNotifyChannels(notifyChannels);
        }

        config = configRepository.save(config);
        log.info("余额预警配置已更新: merchantId={}, warning={}, critical={}, emergency={}",
                merchantId, warningThreshold, criticalThreshold, emergencyThreshold);
        return config;
    }

    // ==================== 余额检查与预警 ====================

    /**
     * 检查余额并触发预警 — 预警级别跃迁 + alertFlag 更新 + 通知发送。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>查询商户预警配置（无配置则跳过）</li>
     *   <li>查询商户 BALANCE 账户余额</li>
     *   <li>根据余额与阈值比较，确定当前预警级别</li>
     *   <li>预警级别跃迁检查（只允许升级，不允许降级）</li>
     *   <li>更新 MerchantAccount.alertFlag</li>
     *   <li>发送预警通知（余额脱敏）</li>
     * </ol>
     *
     * @param merchantId 商户 ID
     * @return 当前预警级别（null 表示正常）
     */
    @Transactional
    public AlertLevel checkBalanceAndAlert(Long merchantId) {
        Optional<BalanceAlertConfig> configOpt = configRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty() || !configOpt.get().getEnabled()) {
            return null;
        }

        BalanceAlertConfig config = configOpt.get();

        Optional<MerchantAccount> accountOpt = accountRepository
                .findByMerchantIdAndAccountType(merchantId, AccountType.BALANCE);
        if (accountOpt.isEmpty()) {
            log.debug("商户账户不存在，跳过预警检查: merchantId={}", merchantId);
            return null;
        }

        MerchantAccount account = accountOpt.get();
        BigDecimal balance = account.getBalance();

        // 确定当前余额对应的预警级别
        AlertLevel currentLevel = determineAlertLevel(balance, config);

        // 预警级别跃迁检查：只允许升级，不允许降级
        AlertLevel previousLevel = parseAlertFlag(account.getAlertFlag());
        if (previousLevel != null && currentLevel != null) {
            if (currentLevel.ordinal() < previousLevel.ordinal()) {
                log.info("预警级别不允许降级（需人工恢复）: merchantId={}, previous={}, current={}",
                        merchantId, previousLevel, currentLevel);
                currentLevel = previousLevel; // 保持原级别
            }
        }

        // 更新 alertFlag
        String newAlertFlag = currentLevel != null ? currentLevel.name() : null;
        if (!java.util.Objects.equals(account.getAlertFlag(), newAlertFlag)) {
            account.setAlertFlag(newAlertFlag);
            accountRepository.save(account);
            log.info("预警级别更新: merchantId={}, previous={}, current={}, balance={}",
                    merchantId, previousLevel, currentLevel, balance);

            // 发送预警通知（余额脱敏）
            if (currentLevel != null) {
                notifier.sendAlertNotification(merchantId, currentLevel, balance, config);
            }
        }

        return currentLevel;
    }

    // ==================== 内部方法 ====================

    /**
     * 根据余额和阈值确定预警级别。
     *
     * @param balance 商户余额
     * @param config  预警配置
     * @return 预警级别（null 表示正常）
     */
    private AlertLevel determineAlertLevel(BigDecimal balance, BalanceAlertConfig config) {
        if (balance.compareTo(config.getEmergencyThreshold()) < 0) {
            return AlertLevel.EMERGENCY;
        }
        if (balance.compareTo(config.getCriticalThreshold()) < 0) {
            return AlertLevel.CRITICAL;
        }
        if (balance.compareTo(config.getWarningThreshold()) < 0) {
            return AlertLevel.WARNING;
        }
        return null; // 正常
    }

    /**
     * 解析 alertFlag 字符串为 AlertLevel 枚举。
     *
     * @param alertFlag alertFlag 字符串
     * @return 预警级别（null 表示正常或无法解析）
     */
    private AlertLevel parseAlertFlag(String alertFlag) {
        if (alertFlag == null || alertFlag.isBlank()) {
            return null;
        }
        try {
            return AlertLevel.valueOf(alertFlag);
        } catch (IllegalArgumentException e) {
            log.warn("无法解析 alertFlag: {}", alertFlag);
            return null;
        }
    }
}