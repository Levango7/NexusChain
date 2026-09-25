package org.nexus.gateway.account;

import org.nexus.gateway.tenant.TenantContext;
import org.nexus.gateway.util.OptimisticLockRetryTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 账户服务 — 商户虚拟账户核心业务逻辑。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #getOrCreateAccount} — 获取或创建商户账户（BALANCE/FROZEN/RESERVE）</li>
 *   <li>{@link #deposit} — 充值（管理员操作，余额增加）</li>
 *   <li>{@link #withdraw} — 提现（余额减少）</li>
 *   <li>{@link #voidReverse} — 撤销扣减（撤销交易后余额回滚）</li>
 *   <li>{@link #reversalAdjust} — 冲正调整（冲正交易后余额调整）</li>
 *   <li>{@link #freeze} — 冻结金额（BALANCE → FROZEN）</li>
 *   <li>{@link #unfreeze} — 解冻金额（FROZEN → BALANCE）</li>
 *   <li>{@link #transfer} — 转账（账户间资金转移）</li>
 *   <li>{@link #getBalance} — 查询商户余额（可用/冻结/备付金）</li>
 *   <li>{@link #creditOnPayment} — 支付确认联动余额增加</li>
 *   <li>{@link #debitOnRefund} — 退款联动余额减少</li>
 * </ul>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁，余额变更冲突时自动重试（最多 3 次）。
 * 每次重试在独立的新事务中执行，避免 rollback-only 事务中重试失败的问题。
 * （来源经验：2026-09-25-optimistic-lock-retry-transactional-boundary-conflict）</p>
 *
 * <p>每个余额变更操作都生成 {@link AccountTransaction} 流水记录，
 * 并发布 {@link AccountBalanceChangedEvent} 事件供下游消费。
 * 退款导致余额为负时额外发布 {@link AccountBalanceNegativeEvent} 告警事件。</p>
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final MerchantAccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OptimisticLockRetryTemplate optimisticLockRetryTemplate;

    public AccountService(MerchantAccountRepository accountRepository,
                          AccountTransactionRepository transactionRepository,
                          ApplicationEventPublisher eventPublisher,
                          PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.eventPublisher = eventPublisher;
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        this.optimisticLockRetryTemplate = new OptimisticLockRetryTemplate(transactionTemplate, 3);
    }

    // === 账户管理 ===

    /**
     * 获取或创建商户账户。
     *
     * <p>如果商户尚未拥有指定类型的账户，则自动创建。初始余额为 0，状态为 ACTIVE。
     * 账户编号格式：{@code MA{merchantId}{accountType}}。</p>
     *
     * @param merchantId 商户 ID
     * @param accountType 账户类型
     * @return 商户账户
     */
    @Transactional
    public MerchantAccount getOrCreateAccount(Long merchantId, AccountType accountType) {
        return accountRepository.findByMerchantIdAndAccountType(merchantId, accountType)
                .orElseGet(() -> createAccount(merchantId, accountType));
    }

    /**
     * 创建商户账户（内部方法）。
     */
    private MerchantAccount createAccount(Long merchantId, AccountType accountType) {
        MerchantAccount account = new MerchantAccount();
        account.setAccountId(generateAccountId(merchantId, accountType));
        account.setMerchantId(merchantId);
        account.setAccountType(accountType);
        account.setBalance(BigDecimal.ZERO);
        account.setStatus(AccountStatus.ACTIVE);
        account.setTenantId(TenantContext.getCurrentTenantId());

        account = accountRepository.save(account);
        log.info("创建商户账户: merchantId={}, accountType={}, accountId={}",
                merchantId, accountType, account.getAccountId());
        return account;
    }

    /**
     * 生成账户编号：MA{merchantId}{accountType}。
     */
    private String generateAccountId(Long merchantId, AccountType accountType) {
        return "MA" + merchantId + accountType.name();
    }

    // === 充值 ===

    /**
     * 充值 — 管理员向商户账户充值，余额增加。
     *
     * @param merchantId 商户 ID
     * @param amount 充值金额（必须 > 0）
     * @param relatedOrderId 关联订单号（可为空）
     * @return 充值后的账户
     * @throws IllegalArgumentException 金额 <= 0
     * @throws IllegalStateException 账户已冻结或已关闭
     */
    public MerchantAccount deposit(Long merchantId, BigDecimal amount, String relatedOrderId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("充值金额必须大于 0");
        }

        return optimisticLockRetryTemplate.execute(() -> {
            MerchantAccount account = getOrCreateAccount(merchantId, AccountType.BALANCE);
            assertAccountOperable(account);

            BigDecimal balanceBefore = account.getBalance();
            BigDecimal balanceAfter = balanceBefore.add(amount);
            account.setBalance(balanceAfter);
            account = accountRepository.save(account);

            recordTransaction(account, AccountOperationType.DEPOSIT, TransactionDirection.CREDIT,
                    amount, balanceBefore, balanceAfter, relatedOrderId != null ? relatedOrderId : "DEPOSIT");

            publishBalanceChangedEvent(account, AccountOperationType.DEPOSIT, amount, balanceBefore, balanceAfter);

            log.debug("充值成功: merchantId={}, amount={}, balanceAfter={}",
                    merchantId, amount, balanceAfter);
            return account;
        });
    }

    // === 提现 ===

    /**
     * 提现 — 商户从账户提现，余额减少。
     *
     * @param merchantId 商户 ID
     * @param amount 提现金额（必须 > 0）
     * @return 提现后的账户
     * @throws IllegalArgumentException 金额 <= 0
     * @throws IllegalStateException 账户已冻结或已关闭
     * @throws IllegalStateException 余额不足
     */
    public MerchantAccount withdraw(Long merchantId, BigDecimal amount) {
        return debitAccount(merchantId, amount, "WITHDRAW", AccountOperationType.WITHDRAW);
    }

    // === 撤销扣减 ===

    /**
     * 撤销扣减 — 撤销交易后从商户账户扣减余额。
     *
     * <p>使用 {@link AccountOperationType#VOID_REVERSE} 操作类型，生成 DEBIT 方向流水。
     * 与 {@link #withdraw} 的区别仅在于 operationType 不同，便于审计追溯和风控规则区分。
     * （来源经验：2026-09-25-financial-operation-reuse-wrong-account-type-audit-trail）</p>
     *
     * @param merchantId 商户 ID
     * @param amount 扣减金额（必须 > 0）
     * @param voidNo 撤销编号（关联凭证）
     * @return 扣减后的账户
     * @throws IllegalArgumentException 金额 <= 0
     * @throws IllegalStateException 账户已冻结或已关闭
     * @throws IllegalStateException 余额不足
     */
    public MerchantAccount voidReverse(Long merchantId, BigDecimal amount, String voidNo) {
        return debitAccount(merchantId, amount, voidNo, AccountOperationType.VOID_REVERSE);
    }

    // === 冲正调整 ===

    /**
     * 冲正调整 — 冲正交易后对商户账户余额进行调整扣减。
     *
     * <p>使用 {@link AccountOperationType#REVERSAL_ADJUST} 操作类型，生成 DEBIT 方向流水。
     * 与 {@link #withdraw} 的区别仅在于 operationType 不同，便于审计追溯和风控规则区分。
     * （来源经验：2026-09-25-financial-operation-reuse-wrong-account-type-audit-trail）</p>
     *
     * @param merchantId 商户 ID
     * @param amount 调整金额（必须 > 0）
     * @param reversalNo 冲正编号（关联凭证）
     * @return 调整后的账户
     * @throws IllegalArgumentException 金额 <= 0
     * @throws IllegalStateException 账户已冻结或已关闭
     * @throws IllegalStateException 余额不足
     */
    public MerchantAccount reversalAdjust(Long merchantId, BigDecimal amount, String reversalNo) {
        return debitAccount(merchantId, amount, reversalNo, AccountOperationType.REVERSAL_ADJUST);
    }

    // === 扣减私有方法 ===

    /**
     * 扣减账户余额的通用私有方法 — 供 withdraw/voidReverse/reversalAdjust 共用。
     *
     * <p>逻辑：余额校验 → 乐观锁重试 → 扣减余额 → 记录 DEBIT 流水 → 发布事件。
     * 不同操作类型通过 operationType 参数区分，确保审计追溯和风控规则正确。
     * （来源经验：2026-09-25-financial-operation-reuse-wrong-account-type-audit-trail）</p>
     *
     * @param merchantId 商户 ID
     * @param amount 扣减金额（必须 > 0）
     * @param reference 关联业务凭证
     * @param operationType 操作类型（WITHDRAW/VOID_REVERSE/REVERSAL_ADJUST）
     * @return 扣减后的账户
     */
    private MerchantAccount debitAccount(Long merchantId, BigDecimal amount, String reference,
                                          AccountOperationType operationType) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("扣减金额必须大于 0");
        }

        return optimisticLockRetryTemplate.execute(() -> {
            MerchantAccount account = getOrCreateAccount(merchantId, AccountType.BALANCE);
            assertAccountOperable(account);

            BigDecimal balanceBefore = account.getBalance();
            if (balanceBefore.compareTo(amount) < 0) {
                throw new IllegalStateException(
                        "余额不足: 当前余额=" + balanceBefore + ", 扣减金额=" + amount);
            }

            BigDecimal balanceAfter = balanceBefore.subtract(amount);
            account.setBalance(balanceAfter);
            account = accountRepository.save(account);

            recordTransaction(account, operationType, TransactionDirection.DEBIT,
                    amount, balanceBefore, balanceAfter, reference);

            publishBalanceChangedEvent(account, operationType, amount, balanceBefore, balanceAfter);

            log.debug("扣减成功: merchantId={}, operationType={}, amount={}, balanceAfter={}",
                    merchantId, operationType, amount, balanceAfter);
            return account;
        });
    }

    // === 冻结 ===

    /**
     * 冻结金额 — 从 BALANCE 账户冻结指定金额到 FROZEN 账户。
     *
     * <p>操作步骤：</p>
     * <ol>
     *   <li>BALANCE 账户余额减少（DEBIT）</li>
     *   <li>FROZEN 账户余额增加（CREDIT）</li>
     *   <li>两个账户各生成一条流水记录</li>
     * </ol>
     *
     * @param merchantId 商户 ID
     * @param amount 冻结金额（必须 > 0）
     * @param reason 冻结原因（作为 reference 记录）
     * @return 冻结后的 BALANCE 账户
     * @throws IllegalArgumentException 金额 <= 0
     * @throws IllegalStateException 余额不足或账户不可操作
     */
    public MerchantAccount freeze(Long merchantId, BigDecimal amount, String reason) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("冻结金额必须大于 0");
        }

        return optimisticLockRetryTemplate.execute(() -> {
            MerchantAccount balanceAccount = getOrCreateAccount(merchantId, AccountType.BALANCE);
            MerchantAccount frozenAccount = getOrCreateAccount(merchantId, AccountType.FROZEN);
            assertAccountOperable(balanceAccount);

            BigDecimal balanceBefore = balanceAccount.getBalance();
            if (balanceBefore.compareTo(amount) < 0) {
                throw new IllegalStateException(
                        "余额不足: 当前余额=" + balanceBefore + ", 冻结金额=" + amount);
            }

            // BALANCE 账户扣减
            BigDecimal balanceAfter = balanceBefore.subtract(amount);
            balanceAccount.setBalance(balanceAfter);
            balanceAccount = accountRepository.save(balanceAccount);

            recordTransaction(balanceAccount, AccountOperationType.FREEZE, TransactionDirection.DEBIT,
                    amount, balanceBefore, balanceAfter, reason != null ? reason : "FREEZE");

            // FROZEN 账户增加
            BigDecimal frozenBefore = frozenAccount.getBalance();
            BigDecimal frozenAfter = frozenBefore.add(amount);
            frozenAccount.setBalance(frozenAfter);
            frozenAccount = accountRepository.save(frozenAccount);

            recordTransaction(frozenAccount, AccountOperationType.FREEZE, TransactionDirection.CREDIT,
                    amount, frozenBefore, frozenAfter, reason != null ? reason : "FREEZE");

            publishBalanceChangedEvent(balanceAccount, AccountOperationType.FREEZE, amount, balanceBefore, balanceAfter);

            log.debug("冻结成功: merchantId={}, amount={}, balanceAfter={}, frozenAfter={}",
                    merchantId, amount, balanceAfter, frozenAfter);
            return balanceAccount;
        });
    }

    // === 解冻 ===

    /**
     * 解冻金额 — 从 FROZEN 账户释放指定金额回 BALANCE 账户。
     *
     * @param merchantId 商户 ID
     * @param amount 解冻金额（必须 > 0）
     * @return 解冻后的 BALANCE 账户
     * @throws IllegalArgumentException 金额 <= 0
     * @throws IllegalStateException 冻结余额不足或账户不可操作
     */
    public MerchantAccount unfreeze(Long merchantId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("解冻金额必须大于 0");
        }

        return optimisticLockRetryTemplate.execute(() -> {
            MerchantAccount frozenAccount = getOrCreateAccount(merchantId, AccountType.FROZEN);
            MerchantAccount balanceAccount = getOrCreateAccount(merchantId, AccountType.BALANCE);
            assertAccountOperable(balanceAccount);

            BigDecimal frozenBefore = frozenAccount.getBalance();
            if (frozenBefore.compareTo(amount) < 0) {
                throw new IllegalStateException(
                        "冻结余额不足: 当前冻结余额=" + frozenBefore + ", 解冻金额=" + amount);
            }

            // FROZEN 账户扣减
            BigDecimal frozenAfter = frozenBefore.subtract(amount);
            frozenAccount.setBalance(frozenAfter);
            frozenAccount = accountRepository.save(frozenAccount);

            recordTransaction(frozenAccount, AccountOperationType.UNFREEZE, TransactionDirection.DEBIT,
                    amount, frozenBefore, frozenAfter, "UNFREEZE");

            // BALANCE 账户增加
            BigDecimal balanceBefore = balanceAccount.getBalance();
            BigDecimal balanceAfter = balanceBefore.add(amount);
            balanceAccount.setBalance(balanceAfter);
            balanceAccount = accountRepository.save(balanceAccount);

            recordTransaction(balanceAccount, AccountOperationType.UNFREEZE, TransactionDirection.CREDIT,
                    amount, balanceBefore, balanceAfter, "UNFREEZE");

            publishBalanceChangedEvent(balanceAccount, AccountOperationType.UNFREEZE, amount, balanceBefore, balanceAfter);

            log.debug("解冻成功: merchantId={}, amount={}, balanceAfter={}, frozenAfter={}",
                    merchantId, amount, balanceAfter, frozenAfter);
            return balanceAccount;
        });
    }

    // === 转账 ===

    /**
     * 转账 — 从一个账户转移到另一个账户。
     *
     * <p>支持同商户不同类型账户间转账，也支持不同商户间转账。
     * 转账操作在单个事务中完成，保证原子性。</p>
     *
     * @param fromAccountId 转出账户编号
     * @param toAccountId 转入账户编号
     * @param amount 转账金额（必须 > 0）
     * @return 转出账户（转账后）
     * @throws IllegalArgumentException 金额 <= 0 或账户不存在
     * @throws IllegalStateException 余额不足或账户不可操作
     */
    public MerchantAccount transfer(String fromAccountId, String toAccountId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("转账金额必须大于 0");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("转出和转入账户不能相同");
        }

        return optimisticLockRetryTemplate.execute(() -> {
            MerchantAccount fromAccount = accountRepository.findByAccountId(fromAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("转出账户不存在: " + fromAccountId));
            MerchantAccount toAccount = accountRepository.findByAccountId(toAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("转入账户不存在: " + toAccountId));
            assertAccountOperable(fromAccount);
            assertAccountOperable(toAccount);

            BigDecimal fromBefore = fromAccount.getBalance();
            if (fromBefore.compareTo(amount) < 0) {
                throw new IllegalStateException(
                        "余额不足: 当前余额=" + fromBefore + ", 转账金额=" + amount);
            }

            // 转出账户扣减
            BigDecimal fromAfter = fromBefore.subtract(amount);
            fromAccount.setBalance(fromAfter);
            fromAccount = accountRepository.save(fromAccount);

            recordTransaction(fromAccount, AccountOperationType.TRANSFER, TransactionDirection.DEBIT,
                    amount, fromBefore, fromAfter, "TRANSFER_TO_" + toAccountId);

            // 转入账户增加
            BigDecimal toBefore = toAccount.getBalance();
            BigDecimal toAfter = toBefore.add(amount);
            toAccount.setBalance(toAfter);
            toAccount = accountRepository.save(toAccount);

            recordTransaction(toAccount, AccountOperationType.TRANSFER, TransactionDirection.CREDIT,
                    amount, toBefore, toAfter, "TRANSFER_FROM_" + fromAccountId);

            publishBalanceChangedEvent(fromAccount, AccountOperationType.TRANSFER, amount, fromBefore, fromAfter);

            log.debug("转账成功: from={}, to={}, amount={}, fromAfter={}, toAfter={}",
                    fromAccountId, toAccountId, amount, fromAfter, toAfter);
            return fromAccount;
        });
    }

    // === 余额查询 ===

    /**
     * 查询商户余额 — 返回可用余额、冻结金额、备付金。
     *
     * @param merchantId 商户 ID
     * @return 余额信息 Map（balance, frozen, reserve）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getBalance(Long merchantId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Optional<MerchantAccount> balanceAccount =
                accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.BALANCE);
        Optional<MerchantAccount> frozenAccount =
                accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.FROZEN);
        Optional<MerchantAccount> reserveAccount =
                accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.RESERVE);

        result.put("merchantId", merchantId);
        result.put("balance", balanceAccount.map(MerchantAccount::getBalance).orElse(BigDecimal.ZERO));
        result.put("frozen", frozenAccount.map(MerchantAccount::getBalance).orElse(BigDecimal.ZERO));
        result.put("reserve", reserveAccount.map(MerchantAccount::getBalance).orElse(BigDecimal.ZERO));
        result.put("balanceStatus", balanceAccount.map(a -> a.getStatus().name()).orElse("NOT_CREATED"));
        result.put("frozenStatus", frozenAccount.map(a -> a.getStatus().name()).orElse("NOT_CREATED"));
        result.put("reserveStatus", reserveAccount.map(a -> a.getStatus().name()).orElse("NOT_CREATED"));

        return result;
    }

    // === 支付确认联动 ===

    /**
     * 支付确认联动 — 支付成功后商户余额增加。
     *
     * <p>由 {@code AccountEventListener} 监听 {@code PaymentConfirmedEvent} 后调用。
     * 使用 orderNo 作为关联凭证，确保幂等性。</p>
     *
     * @param merchantId 商户 ID
     * @param amount 支付金额
     * @param orderNo 订单号（幂等键）
     * @return 充值后的账户
     */
    public MerchantAccount creditOnPayment(Long merchantId, BigDecimal amount, String orderNo) {
        // 幂等检查：同一 orderNo 不重复入账
        List<AccountTransaction> existing = transactionRepository.findByReference(orderNo);
        if (!existing.isEmpty()) {
            log.info("支付入账已存在（幂等跳过）: orderNo={}", orderNo);
            return accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.BALANCE)
                    .orElse(null);
        }

        return optimisticLockRetryTemplate.execute(() -> {
            MerchantAccount account = getOrCreateAccount(merchantId, AccountType.BALANCE);
            assertAccountOperable(account);

            BigDecimal balanceBefore = account.getBalance();
            BigDecimal balanceAfter = balanceBefore.add(amount);
            account.setBalance(balanceAfter);
            account = accountRepository.save(account);

            recordTransaction(account, AccountOperationType.PAYMENT, TransactionDirection.CREDIT,
                    amount, balanceBefore, balanceAfter, orderNo);

            publishBalanceChangedEvent(account, AccountOperationType.PAYMENT, amount, balanceBefore, balanceAfter);

            log.debug("支付入账成功: merchantId={}, orderNo={}, amount={}, balanceAfter={}",
                    merchantId, orderNo, amount, balanceAfter);
            return account;
        });
    }

    // === 退款联动 ===

    /**
     * 退款联动 — 退款完成后商户余额减少。
     *
     * <p>由 {@code AccountEventListener} 监听 {@code RefundCompletedEvent} 后调用。
     * 使用 refundNo 作为关联凭证，确保幂等性。余额不足时允许暂时为负并触发预警。</p>
     *
     * @param merchantId 商户 ID
     * @param amount 退款金额
     * @param refundNo 退款编号（幂等键）
     * @return 扣减后的账户
     */
    public MerchantAccount debitOnRefund(Long merchantId, BigDecimal amount, String refundNo) {
        // 幂等检查：同一 refundNo 不重复扣减
        List<AccountTransaction> existing = transactionRepository.findByReference(refundNo);
        if (!existing.isEmpty()) {
            log.info("退款扣减已存在（幂等跳过）: refundNo={}", refundNo);
            return accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.BALANCE)
                    .orElse(null);
        }

        return optimisticLockRetryTemplate.execute(() -> {
            MerchantAccount account = getOrCreateAccount(merchantId, AccountType.BALANCE);
            assertAccountOperable(account);

            BigDecimal balanceBefore = account.getBalance();
            BigDecimal balanceAfter = balanceBefore.subtract(amount);
            // 退款允许余额暂时为负（触发预警），记录日志并发布告警事件
            if (balanceAfter.compareTo(BigDecimal.ZERO) < 0) {
                log.warn("退款后余额为负: merchantId={}, refundNo={}, balanceAfter={}",
                        merchantId, refundNo, balanceAfter);
                publishBalanceNegativeEvent(account, AccountOperationType.REFUND,
                        amount, balanceBefore, balanceAfter, refundNo);
            }
            account.setBalance(balanceAfter);
            account = accountRepository.save(account);

            recordTransaction(account, AccountOperationType.REFUND, TransactionDirection.DEBIT,
                    amount, balanceBefore, balanceAfter, refundNo);

            publishBalanceChangedEvent(account, AccountOperationType.REFUND, amount, balanceBefore, balanceAfter);

            log.debug("退款扣减成功: merchantId={}, refundNo={}, amount={}, balanceAfter={}",
                    merchantId, refundNo, amount, balanceAfter);
            return account;
        });
    }

    // === 内部方法 ===

    /**
     * 断言账户可操作 — 状态必须为 ACTIVE。
     *
     * @param account 账户
     * @throws IllegalStateException 账户已冻结或已关闭
     */
    private void assertAccountOperable(MerchantAccount account) {
        if (account.getStatus() == AccountStatus.FROZEN) {
            throw new IllegalStateException("账户已冻结: " + account.getAccountId());
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new IllegalStateException("账户已关闭: " + account.getAccountId());
        }
    }

    /**
     * 记录账户流水。
     *
     * @param account 账户
     * @param operationType 操作类型
     * @param direction 方向
     * @param amount 金额
     * @param balanceBefore 操作前余额
     * @param balanceAfter 操作后余额
     * @param reference 关联业务凭证
     */
    private void recordTransaction(MerchantAccount account, AccountOperationType operationType,
                                    TransactionDirection direction, BigDecimal amount,
                                    BigDecimal balanceBefore, BigDecimal balanceAfter, String reference) {
        AccountTransaction tx = new AccountTransaction();
        tx.setTxNo(generateTxNo());
        tx.setAccountId(account.getAccountId());
        tx.setMerchantId(account.getMerchantId());
        tx.setOperationType(operationType);
        tx.setDirection(direction);
        tx.setAmount(amount);
        tx.setBalanceBefore(balanceBefore);
        tx.setBalanceAfter(balanceAfter);
        tx.setReference(reference);
        tx.setTenantId(account.getTenantId());

        transactionRepository.save(tx);
    }

    /**
     * 生成流水编号：AT{timestamp}{full-uuid}。
     * 使用完整 32 字符 UUID（去掉连字符），避免短 UUID 碰撞。
     */
    private String generateTxNo() {
        return "AT" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 发布余额变更事件。
     */
    private void publishBalanceChangedEvent(MerchantAccount account, AccountOperationType operationType,
                                             BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter) {
        AccountBalanceChangedEvent event = new AccountBalanceChangedEvent(
                this, null, null, account.getMerchantId(),
                account.getAccountId(), operationType, amount, balanceBefore, balanceAfter);
        eventPublisher.publishEvent(event);
    }

    /**
     * 发布余额负数告警事件 — 供告警系统消费。
     */
    private void publishBalanceNegativeEvent(MerchantAccount account, AccountOperationType operationType,
                                              BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter,
                                              String triggerReference) {
        AccountBalanceNegativeEvent event = new AccountBalanceNegativeEvent(
                this, account.getMerchantId(), account.getAccountId(),
                operationType, amount, balanceBefore, balanceAfter, triggerReference);
        eventPublisher.publishEvent(event);
    }
}