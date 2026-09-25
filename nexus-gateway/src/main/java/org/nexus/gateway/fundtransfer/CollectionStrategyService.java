package org.nexus.gateway.fundtransfer;

import org.nexus.gateway.account.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 归集策略服务 — 执行资金归集策略，将多个商户账户资金归集到目标账户。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #executeCollectionStrategy} — 执行指定归集策略</li>
 *   <li>{@link #executeAllEnabledStrategies} — 执行所有启用的归集策略</li>
 *   <li>{@link #createStrategy} — 创建归集策略</li>
 *   <li>{@link #updateStrategy} — 更新归集策略</li>
 *   <li>{@link #getStrategies} — 查询归集策略列表</li>
 * </ul>
 *
 * <p>所有资金操作通过 {@link AccountService} 执行。</p>
 */
@Service
public class CollectionStrategyService {

    private static final Logger log = LoggerFactory.getLogger(CollectionStrategyService.class);

    private final CollectionStrategyRepository strategyRepository;
    private final AccountService accountService;
    private final MerchantAccountRepository accountRepository;

    public CollectionStrategyService(CollectionStrategyRepository strategyRepository,
                                     AccountService accountService,
                                     MerchantAccountRepository accountRepository) {
        this.strategyRepository = strategyRepository;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
    }

    // === 归集策略执行 ===

    /**
     * 执行指定归集策略 — 将源商户账户资金归集到目标商户账户。
     *
     * <p>对每个源商户：</p>
     * <ol>
     *   <li>获取源账户余额</li>
     *   <li>根据归集类型计算归集金额（扣除最低保留金额）</li>
     *   <li>通过 AccountService.transfer 执行转账</li>
     * </ol>
     *
     * @param strategyCode 策略编号
     * @return 归集结果摘要
     * @throws IllegalArgumentException 策略不存在
     */
    @Transactional
    public CollectionResult executeCollectionStrategy(String strategyCode) {
        CollectionStrategy strategy = strategyRepository.findByStrategyCode(strategyCode)
                .orElseThrow(() -> new IllegalArgumentException("归集策略不存在: " + strategyCode));

        if (!Boolean.TRUE.equals(strategy.getEnabled())) {
            throw new IllegalStateException("归集策略已禁用: " + strategyCode);
        }

        log.info("执行归集策略: strategyCode={}, targetMerchantId={}",
                strategyCode, strategy.getTargetMerchantId());

        // 确保目标账户存在
        MerchantAccount targetAccount = accountService.getOrCreateAccount(
                strategy.getTargetMerchantId(), strategy.getTargetAccountType());

        List<Long> sourceMerchantIds = strategy.getSourceMerchantIdList();
        int successCount = 0;
        int failCount = 0;
        BigDecimal totalCollected = BigDecimal.ZERO;

        for (Long sourceMerchantId : sourceMerchantIds) {
            try {
                BigDecimal collected = collectFromMerchant(strategy, sourceMerchantId, targetAccount);
                if (collected.compareTo(BigDecimal.ZERO) > 0) {
                    successCount++;
                    totalCollected = totalCollected.add(collected);
                }
            } catch (Exception e) {
                log.warn("归集失败: strategyCode={}, sourceMerchantId={}, error={}",
                        strategyCode, sourceMerchantId, e.getMessage());
                failCount++;
            }
        }

        log.info("归集策略执行完成: strategyCode={}, success={}, fail={}, totalCollected={}",
                strategyCode, successCount, failCount, totalCollected);

        return new CollectionResult(strategyCode, successCount, failCount, totalCollected);
    }

    /**
     * 执行所有启用的归集策略。
     *
     * @return 各策略执行结果列表
     */
    @Transactional
    public List<CollectionResult> executeAllEnabledStrategies() {
        List<CollectionStrategy> strategies = strategyRepository.findByEnabled(true);
        List<CollectionResult> results = new ArrayList<>();

        for (CollectionStrategy strategy : strategies) {
            try {
                results.add(executeCollectionStrategy(strategy.getStrategyCode()));
            } catch (Exception e) {
                log.warn("归集策略执行失败: strategyCode={}, error={}",
                        strategy.getStrategyCode(), e.getMessage());
            }
        }

        return results;
    }

    /**
     * 从单个源商户归集资金。
     */
    private BigDecimal collectFromMerchant(CollectionStrategy strategy, Long sourceMerchantId,
                                            MerchantAccount targetAccount) {
        MerchantAccount sourceAccount = accountRepository
                .findByMerchantIdAndAccountType(sourceMerchantId, strategy.getSourceAccountType())
                .orElse(null);

        if (sourceAccount == null) {
            log.debug("源商户账户不存在: merchantId={}, accountType={}",
                    sourceMerchantId, strategy.getSourceAccountType());
            return BigDecimal.ZERO;
        }

        BigDecimal balance = sourceAccount.getBalance();
        BigDecimal collectAmount = calculateCollectionAmount(balance, strategy);

        if (collectAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("归集金额为0，跳过: merchantId={}, balance={}, minRetain={}",
                    sourceMerchantId, balance, strategy.getMinRetainAmount());
            return BigDecimal.ZERO;
        }

        accountService.transfer(sourceAccount.getAccountId(), targetAccount.getAccountId(), collectAmount);

        log.debug("归集成功: sourceMerchantId={}, amount={}", sourceMerchantId, collectAmount);
        return collectAmount;
    }

    /**
     * 根据归集类型计算归集金额。
     */
    private BigDecimal calculateCollectionAmount(BigDecimal balance, CollectionStrategy strategy) {
        BigDecimal minRetain = strategy.getMinRetainAmount() != null
                ? strategy.getMinRetainAmount() : BigDecimal.ZERO;

        return switch (strategy.getCollectionType()) {
            case "FULL" -> {
                BigDecimal available = balance.subtract(minRetain);
                yield available.compareTo(BigDecimal.ZERO) > 0 ? available : BigDecimal.ZERO;
            }
            case "PERCENTAGE" -> {
                if (strategy.getCollectionPercentage() == null) {
                    yield BigDecimal.ZERO;
                }
                BigDecimal amount = balance.multiply(strategy.getCollectionPercentage())
                        .divide(new BigDecimal("100"), 0, RoundingMode.DOWN);
                BigDecimal afterCollection = balance.subtract(amount);
                if (afterCollection.compareTo(minRetain) < 0) {
                    amount = balance.subtract(minRetain);
                }
                yield amount.compareTo(BigDecimal.ZERO) > 0 ? amount : BigDecimal.ZERO;
            }
            case "FIXED" -> {
                if (strategy.getCollectionAmount() == null) {
                    yield BigDecimal.ZERO;
                }
                BigDecimal afterCollection = balance.subtract(strategy.getCollectionAmount());
                if (afterCollection.compareTo(minRetain) < 0) {
                    yield BigDecimal.ZERO;
                }
                yield strategy.getCollectionAmount();
            }
            default -> BigDecimal.ZERO;
        };
    }

    // === 策略配置管理 ===

    /**
     * 创建归集策略。
     *
     * @param strategy 归集策略配置
     * @return 创建后的策略
     * @throws IllegalArgumentException 策略编号已存在
     */
    @Transactional
    public CollectionStrategy createStrategy(CollectionStrategy strategy) {
        if (strategy.getStrategyCode() == null || strategy.getStrategyCode().isBlank()) {
            throw new IllegalArgumentException("策略编号不能为空");
        }
        if (strategyRepository.findByStrategyCode(strategy.getStrategyCode()).isPresent()) {
            throw new IllegalArgumentException("策略编号已存在: " + strategy.getStrategyCode());
        }

        validateStrategy(strategy);
        strategy = strategyRepository.save(strategy);
        log.info("创建归集策略: strategyCode={}", strategy.getStrategyCode());
        return strategy;
    }

    /**
     * 更新归集策略。
     *
     * @param strategyCode 策略编号
     * @param updates 更新内容
     * @return 更新后的策略
     * @throws IllegalArgumentException 策略不存在
     */
    @Transactional
    public CollectionStrategy updateStrategy(String strategyCode, CollectionStrategy updates) {
        CollectionStrategy existing = strategyRepository.findByStrategyCode(strategyCode)
                .orElseThrow(() -> new IllegalArgumentException("策略不存在: " + strategyCode));

        if (updates.getStrategyName() != null) {
            existing.setStrategyName(updates.getStrategyName());
        }
        if (updates.getSourceMerchantIds() != null) {
            existing.setSourceMerchantIds(updates.getSourceMerchantIds());
        }
        if (updates.getTargetMerchantId() != null) {
            existing.setTargetMerchantId(updates.getTargetMerchantId());
        }
        if (updates.getSourceAccountType() != null) {
            existing.setSourceAccountType(updates.getSourceAccountType());
        }
        if (updates.getTargetAccountType() != null) {
            existing.setTargetAccountType(updates.getTargetAccountType());
        }
        if (updates.getCollectionType() != null) {
            existing.setCollectionType(updates.getCollectionType());
        }
        if (updates.getCollectionAmount() != null) {
            existing.setCollectionAmount(updates.getCollectionAmount());
        }
        if (updates.getCollectionPercentage() != null) {
            existing.setCollectionPercentage(updates.getCollectionPercentage());
        }
        if (updates.getMinRetainAmount() != null) {
            existing.setMinRetainAmount(updates.getMinRetainAmount());
        }
        if (updates.getCronExpression() != null) {
            existing.setCronExpression(updates.getCronExpression());
        }
        if (updates.getEnabled() != null) {
            existing.setEnabled(updates.getEnabled());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }

        validateStrategy(existing);
        existing = strategyRepository.save(existing);
        log.info("更新归集策略: strategyCode={}", strategyCode);
        return existing;
    }

    /**
     * 查询归集策略列表。
     *
     * @param enabled 启用状态（为 null 时查询全部）
     * @return 策略列表
     */
    @Transactional(readOnly = true)
    public List<CollectionStrategy> getStrategies(Boolean enabled) {
        if (enabled != null) {
            return strategyRepository.findByEnabled(enabled);
        }
        return strategyRepository.findAll();
    }

    /**
     * 按策略编号查询。
     *
     * @param strategyCode 策略编号
     * @return 归集策略（可能为空）
     */
    @Transactional(readOnly = true)
    public Optional<CollectionStrategy> getStrategy(String strategyCode) {
        return strategyRepository.findByStrategyCode(strategyCode);
    }

    // === 校验 ===

    private void validateStrategy(CollectionStrategy strategy) {
        if (strategy.getCollectionType() == null) {
            throw new IllegalArgumentException("归集类型不能为空");
        }
        if (!"FULL".equals(strategy.getCollectionType())
                && !"PERCENTAGE".equals(strategy.getCollectionType())
                && !"FIXED".equals(strategy.getCollectionType())) {
            throw new IllegalArgumentException("归集类型必须为 FULL/PERCENTAGE/FIXED");
        }
        if ("FIXED".equals(strategy.getCollectionType()) && strategy.getCollectionAmount() == null) {
            throw new IllegalArgumentException("FIXED 类型必须指定固定金额");
        }
        if ("PERCENTAGE".equals(strategy.getCollectionType()) && strategy.getCollectionPercentage() == null) {
            throw new IllegalArgumentException("PERCENTAGE 类型必须指定百分比");
        }
    }

    // === 归集结果 DTO ===

    /**
     * 归集策略执行结果。
     */
    public static class CollectionResult {
        private final String strategyCode;
        private final int successCount;
        private final int failCount;
        private final BigDecimal totalCollected;

        public CollectionResult(String strategyCode, int successCount, int failCount, BigDecimal totalCollected) {
            this.strategyCode = strategyCode;
            this.successCount = successCount;
            this.failCount = failCount;
            this.totalCollected = totalCollected;
        }

        public String getStrategyCode() { return strategyCode; }
        public int getSuccessCount() { return successCount; }
        public int getFailCount() { return failCount; }
        public BigDecimal getTotalCollected() { return totalCollected; }
    }
}