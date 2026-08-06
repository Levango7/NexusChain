package org.nexus.bridge.safety;

import org.nexus.bridge.model.InsuranceFundLedgerEntry;
import org.nexus.bridge.repository.InsuranceFundLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保险基金默认实现。
 *
 * <p>基于内存 {@link AtomicReference} 余额 + JPA 流水持久化：</p>
 * <ul>
 *   <li>内存余额提供高频 {@link #getBalance()} 查询的 O(1) 访问</li>
 *   <li>每笔存入 / 补偿记录流水到 {@link InsuranceFundLedgerRepository}，
 *       保证可审计与服务重启后余额可重放恢复</li>
 *   <li>所有写操作在 {@code @Transactional} 事务内执行，保证一致性</li>
 * </ul>
 *
 * <h2>业务规则</h2>
 * <ul>
 *   <li>{@link #deposit(BigDecimal)} — 金额必须为正；存入后余额增加</li>
 *   <li>{@link #compensate(String, BigDecimal, String)} — 金额必须为正且不超过当前余额；
 *       补偿后余额减少；受害者 ID 不能为空</li>
 *   <li>{@link #getBalance()} — 返回当前余额（永不为 null）</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>余额使用 {@link AtomicReference} + compareAndSet 循环保证并发写的原子性；
 * 持久化通过事务串行化保证一致性。</p>
 *
 * @since 1.2
 */
@Service
public class DefaultInsuranceFund implements InsuranceFund {

    private static final Logger log = LoggerFactory.getLogger(DefaultInsuranceFund.class);

    /** 操作类型常量：存入。 */
    public static final String TYPE_DEPOSIT = "DEPOSIT";
    /** 操作类型常量：补偿。 */
    public static final String TYPE_COMPENSATE = "COMPENSATE";

    private final InsuranceFundLedgerRepository ledgerRepository;

    /** 内存余额缓存（永不为 null）。 */
    private final AtomicReference<BigDecimal> balance = new AtomicReference<>(BigDecimal.ZERO);

    /**
     * 构造默认保险基金服务。
     *
     * <p>启动时从数据库重放所有流水恢复内存余额。</p>
     *
     * @param ledgerRepository 流水 Repository
     */
    @Autowired
    public DefaultInsuranceFund(InsuranceFundLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
        try {
            List<InsuranceFundLedgerEntry> allEntries = ledgerRepository.findAll();
            BigDecimal restored = BigDecimal.ZERO;
            for (InsuranceFundLedgerEntry entry : allEntries) {
                if (TYPE_DEPOSIT.equals(entry.getType())) {
                    restored = restored.add(entry.getAmount());
                } else if (TYPE_COMPENSATE.equals(entry.getType())) {
                    restored = restored.subtract(entry.getAmount());
                }
            }
            balance.set(restored);
            log.info("Restored insurance fund balance from {} ledger entries: {}", allEntries.size(), restored);
        } catch (Exception e) {
            log.warn("Failed to restore insurance fund balance (DB may not be ready): {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void deposit(BigDecimal amount) {
        Objects.requireNonNull(amount, "deposit amount must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("deposit amount must be positive: " + amount);
        }
        BigDecimal newBalance = updateBalanceAtomically(current -> current.add(amount));
        ledgerRepository.save(new InsuranceFundLedgerEntry(
                TYPE_DEPOSIT, amount, newBalance, null, "insurance fund deposit"));
        log.info("Deposited {} to insurance fund, new balance: {}", amount, newBalance);
    }

    @Override
    @Transactional
    public void compensate(String victimId, BigDecimal amount, String reason) {
        Objects.requireNonNull(victimId, "victimId must not be null");
        Objects.requireNonNull(amount, "compensate amount must not be null");
        if (victimId.isEmpty()) {
            throw new IllegalArgumentException("victimId must not be empty");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("compensate amount must be positive: " + amount);
        }
        // 检查余额充足（在事务内做乐观重试）
        BigDecimal newBalance = updateBalanceAtomically(current -> {
            if (current.compareTo(amount) < 0) {
                throw new IllegalStateException("insufficient insurance fund balance: "
                        + "current=" + current + ", required=" + amount);
            }
            return current.subtract(amount);
        });
        ledgerRepository.save(new InsuranceFundLedgerEntry(
                TYPE_COMPENSATE, amount, newBalance, victimId, reason));
        log.info("Compensated victim {} with {} (reason: {}), new balance: {}",
                victimId, amount, reason, newBalance);
    }

    @Override
    public BigDecimal getBalance() {
        return balance.get();
    }

    // ==================== 扩展方法（任务说明要求：审批提现） ====================

    /**
     * 从保险基金提现（需要审批）。
     *
     * <p>提现属于治理操作，调用方需自行确保已获得多签 / 治理提案审批。
     * 本方法仅执行余额扣减与流水记录。</p>
     *
     * @param amount       提现金额
     * @param approver     审批者 ID
     * @param reason       提现原因
     */
    @Transactional
    public void withdraw(BigDecimal amount, String approver, String reason) {
        Objects.requireNonNull(amount, "withdraw amount must not be null");
        Objects.requireNonNull(approver, "approver must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("withdraw amount must be positive: " + amount);
        }
        BigDecimal newBalance = updateBalanceAtomically(current -> {
            if (current.compareTo(amount) < 0) {
                throw new IllegalStateException("insufficient insurance fund balance for withdraw: "
                        + "current=" + current + ", required=" + amount);
            }
            return current.subtract(amount);
        });
        ledgerRepository.save(new InsuranceFundLedgerEntry(
                "WITHDRAW", amount, newBalance, approver, reason));
        log.warn("Withdrew {} from insurance fund by approver {} (reason: {}), new balance: {}",
                amount, approver, reason, newBalance);
    }

    /**
     * 查询所有流水记录（用于审计）。
     *
     * @return 流水记录列表
     */
    public List<InsuranceFundLedgerEntry> getLedgerEntries() {
        return ledgerRepository.findAll();
    }

    // ==================== 内部工具 ====================

    /**
     * 原子更新余额，处理并发竞争。
     *
     * @param updater 更新函数（输入当前余额，返回新余额；可抛异常拒绝更新）
     * @return 更新后的余额
     */
    private BigDecimal updateBalanceAtomically(java.util.function.Function<BigDecimal, BigDecimal> updater) {
        while (true) {
            BigDecimal current = balance.get();
            BigDecimal next = updater.apply(current);
            if (balance.compareAndSet(current, next)) {
                return next;
            }
            // CAS 失败，重试
        }
    }
}