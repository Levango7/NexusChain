package org.nexus.gateway.risk.link;

import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.AccountStatus;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 风控联动服务 — 执行风控事件触发的账户联动操作。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #executeFreeze} — 执行冻结联动：幂等检查 + AccountService.freeze + 记录联动</li>
 *   <li>{@link #executeUnfreeze} — 执行解冻联动：幂等检查 + AccountService.unfreeze + 记录联动</li>
 *   <li>{@link #executeStatusChange} — 执行状态变更联动：STATUS_FROZEN/STATUS_CLOSED/STATUS_RESTORED</li>
 * </ul>
 *
 * <p>幂等保证：同一 riskEventId + linkAction 不重复执行。
 * 通过 {@link RiskAccountLinkRecordRepository#findByRiskEventIdAndLinkAction} 检查是否已存在记录。</p>
 *
 * <p>事务边界设计（来源经验：2026-09-25-optimistic-lock-retry-transactional-boundary-conflict）：
 * execute* 方法不带 @Transactional，内部调用带 @Transactional 的 doExecute* 方法。
 * 若 doExecute* 抛出异常，外层 catch 通过 REQUIRES_NEW 事务保存 FAILED 记录，
 * 避免 @Transactional 方法内 catch 异常后继续 save 导致 UnexpectedRollbackException。</p>
 */
@Service
public class RiskAccountLinkService {

    private static final Logger log = LoggerFactory.getLogger(RiskAccountLinkService.class);

    private final RiskAccountLinkRecordRepository linkRecordRepository;
    private final AccountService accountService;
    private final MerchantAccountRepository accountRepository;

    /** 自注入代理，确保 @Transactional 方法通过代理调用生效 */
    @Lazy
    @Autowired
    private RiskAccountLinkService self;

    public RiskAccountLinkService(RiskAccountLinkRecordRepository linkRecordRepository,
                                   AccountService accountService,
                                   MerchantAccountRepository accountRepository) {
        this.linkRecordRepository = linkRecordRepository;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
    }

    // ==================== 冻结联动 ====================

    /**
     * 执行冻结联动 — 幂等检查 + AccountService.freeze + RiskAccountLinkRecord。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>幂等检查：同一 riskEventId + FREEZE 已有成功记录则跳过</li>
     *   <li>调用 AccountService.freeze 冻结金额</li>
     *   <li>记录联动执行结果（SUCCESS/FAILED）</li>
     * </ol>
     *
     * <p>事务边界：本方法不带 @Transactional，内部调用 {@link #doExecuteFreeze}（带 @Transactional）。
     * 若 doExecuteFreeze 抛出异常，通过 {@link #saveFailedRecord}（REQUIRES_NEW）保存 FAILED 记录。</p>
     *
     * @param riskEventId 风控事件 ID
     * @param merchantId  商户 ID
     * @param amount      冻结金额
     * @param reason      冻结原因
     * @return 联动记录
     */
    public RiskAccountLinkRecord executeFreeze(String riskEventId, Long merchantId,
                                                 BigDecimal amount, String reason) {
        try {
            return self.doExecuteFreeze(riskEventId, merchantId, amount, reason);
        } catch (Exception e) {
            log.error("冻结联动失败: riskEventId={}, merchantId={}, amount={}, error={}",
                    riskEventId, merchantId, amount, e.getMessage(), e);
            return self.saveFailedRecord(riskEventId, LinkAction.FREEZE, merchantId, amount, reason, e.getMessage());
        }
    }

    /**
     * 执行冻结联动核心逻辑（带 @Transactional）。
     *
     * <p>异常直接传播到外层 {@link #executeFreeze} 处理，不在事务内 catch。</p>
     */
    @Transactional
    public RiskAccountLinkRecord doExecuteFreeze(String riskEventId, Long merchantId,
                                                   BigDecimal amount, String reason) {
        // 幂等检查
        Optional<RiskAccountLinkRecord> existing = linkRecordRepository
                .findByRiskEventIdAndLinkAction(riskEventId, LinkAction.FREEZE);
        if (existing.isPresent()) {
            RiskAccountLinkRecord record = existing.get();
            if (record.getExecutionStatus() == ExecutionStatus.SUCCESS) {
                log.info("冻结联动已执行（幂等跳过）: riskEventId={}, merchantId={}", riskEventId, merchantId);
                record.setExecutionStatus(ExecutionStatus.SKIPPED);
                return linkRecordRepository.save(record);
            }
        }

        RiskAccountLinkRecord record = existing.orElseGet(() -> {
            RiskAccountLinkRecord r = new RiskAccountLinkRecord();
            r.setRiskEventId(riskEventId);
            r.setLinkAction(LinkAction.FREEZE);
            r.setMerchantId(merchantId);
            r.setAmount(amount);
            r.setReason(reason);
            r.setExecutionStatus(ExecutionStatus.PENDING);
            return linkRecordRepository.save(r);
        });

        record.setExecutedAt(LocalDateTime.now());

        // 异常直接传播，不在事务内 catch
        accountService.freeze(merchantId, amount, reason);

        record.setExecutionStatus(ExecutionStatus.SUCCESS);
        record.setCompletedAt(LocalDateTime.now());
        log.info("冻结联动成功: riskEventId={}, merchantId={}, amount={}", riskEventId, merchantId, amount);
        return linkRecordRepository.save(record);
    }

    // ==================== 解冻联动 ====================

    /**
     * 执行解冻联动 — 幂等检查 + AccountService.unfreeze + RiskAccountLinkRecord。
     *
     * <p>事务边界：本方法不带 @Transactional，内部调用 {@link #doExecuteUnfreeze}（带 @Transactional）。
     * 若 doExecuteUnfreeze 抛出异常，通过 {@link #saveFailedRecord}（REQUIRES_NEW）保存 FAILED 记录。</p>
     *
     * @param riskEventId 风控事件 ID
     * @param merchantId  商户 ID
     * @param amount      解冻金额
     * @param reason      解冻原因
     * @return 联动记录
     */
    public RiskAccountLinkRecord executeUnfreeze(String riskEventId, Long merchantId,
                                                   BigDecimal amount, String reason) {
        try {
            return self.doExecuteUnfreeze(riskEventId, merchantId, amount, reason);
        } catch (Exception e) {
            log.error("解冻联动失败: riskEventId={}, merchantId={}, amount={}, error={}",
                    riskEventId, merchantId, amount, e.getMessage(), e);
            return self.saveFailedRecord(riskEventId, LinkAction.UNFREEZE, merchantId, amount, reason, e.getMessage());
        }
    }

    /**
     * 执行解冻联动核心逻辑（带 @Transactional）。
     *
     * <p>异常直接传播到外层 {@link #executeUnfreeze} 处理，不在事务内 catch。</p>
     */
    @Transactional
    public RiskAccountLinkRecord doExecuteUnfreeze(String riskEventId, Long merchantId,
                                                     BigDecimal amount, String reason) {
        // 幂等检查
        Optional<RiskAccountLinkRecord> existing = linkRecordRepository
                .findByRiskEventIdAndLinkAction(riskEventId, LinkAction.UNFREEZE);
        if (existing.isPresent()) {
            RiskAccountLinkRecord record = existing.get();
            if (record.getExecutionStatus() == ExecutionStatus.SUCCESS) {
                log.info("解冻联动已执行（幂等跳过）: riskEventId={}, merchantId={}", riskEventId, merchantId);
                record.setExecutionStatus(ExecutionStatus.SKIPPED);
                return linkRecordRepository.save(record);
            }
        }

        RiskAccountLinkRecord record = existing.orElseGet(() -> {
            RiskAccountLinkRecord r = new RiskAccountLinkRecord();
            r.setRiskEventId(riskEventId);
            r.setLinkAction(LinkAction.UNFREEZE);
            r.setMerchantId(merchantId);
            r.setAmount(amount);
            r.setReason(reason);
            r.setExecutionStatus(ExecutionStatus.PENDING);
            return linkRecordRepository.save(r);
        });

        record.setExecutedAt(LocalDateTime.now());

        // 异常直接传播，不在事务内 catch
        accountService.unfreeze(merchantId, amount);

        record.setExecutionStatus(ExecutionStatus.SUCCESS);
        record.setCompletedAt(LocalDateTime.now());
        log.info("解冻联动成功: riskEventId={}, merchantId={}, amount={}", riskEventId, merchantId, amount);
        return linkRecordRepository.save(record);
    }

    // ==================== 状态变更联动 ====================

    /**
     * 执行状态变更联动 — STATUS_FROZEN/STATUS_CLOSED/STATUS_RESTORED。
     *
     * <p>根据 linkAction 参数将商户账户状态变更为对应状态：</p>
     * <ul>
     *   <li>STATUS_FROZEN → AccountStatus.FROZEN</li>
     *   <li>STATUS_CLOSED → AccountStatus.CLOSED（终态）</li>
     *   <li>STATUS_RESTORED → AccountStatus.ACTIVE</li>
     * </ul>
     *
     * <p>事务边界：本方法不带 @Transactional，内部调用 {@link #doExecuteStatusChange}（带 @Transactional）。
     * 若 doExecuteStatusChange 抛出异常，通过 {@link #saveFailedRecord}（REQUIRES_NEW）保存 FAILED 记录。</p>
     *
     * @param riskEventId 风控事件 ID
     * @param merchantId  商户 ID
     * @param linkAction  状态变更动作（STATUS_FROZEN/STATUS_CLOSED/STATUS_RESTORED）
     * @param reason      变更原因
     * @return 联动记录
     */
    public RiskAccountLinkRecord executeStatusChange(String riskEventId, Long merchantId,
                                                       LinkAction linkAction, String reason) {
        try {
            return self.doExecuteStatusChange(riskEventId, merchantId, linkAction, reason);
        } catch (Exception e) {
            log.error("状态变更联动失败: riskEventId={}, merchantId={}, action={}, error={}",
                    riskEventId, merchantId, linkAction, e.getMessage(), e);
            return self.saveFailedRecord(riskEventId, linkAction, merchantId, null, reason, e.getMessage());
        }
    }

    /**
     * 执行状态变更联动核心逻辑（带 @Transactional）。
     *
     * <p>异常直接传播到外层 {@link #executeStatusChange} 处理，不在事务内 catch。</p>
     */
    @Transactional
    public RiskAccountLinkRecord doExecuteStatusChange(String riskEventId, Long merchantId,
                                                         LinkAction linkAction, String reason) {
        // 幂等检查
        Optional<RiskAccountLinkRecord> existing = linkRecordRepository
                .findByRiskEventIdAndLinkAction(riskEventId, linkAction);
        if (existing.isPresent()) {
            RiskAccountLinkRecord record = existing.get();
            if (record.getExecutionStatus() == ExecutionStatus.SUCCESS) {
                log.info("状态变更联动已执行（幂等跳过）: riskEventId={}, action={}", riskEventId, linkAction);
                record.setExecutionStatus(ExecutionStatus.SKIPPED);
                return linkRecordRepository.save(record);
            }
        }

        RiskAccountLinkRecord record = existing.orElseGet(() -> {
            RiskAccountLinkRecord r = new RiskAccountLinkRecord();
            r.setRiskEventId(riskEventId);
            r.setLinkAction(linkAction);
            r.setMerchantId(merchantId);
            r.setReason(reason);
            r.setExecutionStatus(ExecutionStatus.PENDING);
            return linkRecordRepository.save(r);
        });

        record.setExecutedAt(LocalDateTime.now());

        // 异常直接传播，不在事务内 catch
        AccountStatus targetStatus = mapLinkActionToStatus(linkAction);
        // 变更 BALANCE 账户状态（通过 AccountService 统一管理）
        accountService.changeStatus(merchantId, AccountType.BALANCE, targetStatus);

        // 变更 FROZEN 账户状态（如果存在）
        Optional<MerchantAccount> frozenAccount = accountRepository
                .findByMerchantIdAndAccountType(merchantId, AccountType.FROZEN);
        frozenAccount.ifPresent(account ->
                accountService.changeStatus(merchantId, AccountType.FROZEN, targetStatus));

        record.setExecutionStatus(ExecutionStatus.SUCCESS);
        record.setCompletedAt(LocalDateTime.now());
        log.info("状态变更联动成功: riskEventId={}, merchantId={}, action={}, targetStatus={}",
                riskEventId, merchantId, linkAction, targetStatus);
        return linkRecordRepository.save(record);
    }

    // ==================== 失败记录保存 ====================

    /**
     * 在新事务中保存 FAILED 状态的联动记录。
     *
     * <p>使用 REQUIRES_NEW 传播级别，确保即使外层事务已回滚，
     * FAILED 记录仍能独立提交到数据库。</p>
     *
     * @param riskEventId  风控事件 ID
     * @param linkAction   联动动作
     * @param merchantId   商户 ID
     * @param amount       金额（状态变更类联动可为 null）
     * @param reason       原因
     * @param errorMessage 错误信息
     * @return FAILED 状态的联动记录
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RiskAccountLinkRecord saveFailedRecord(String riskEventId, LinkAction linkAction,
                                                    Long merchantId, BigDecimal amount,
                                                    String reason, String errorMessage) {
        // 查找是否已有记录（doExecute* 中的 PENDING 记录可能已被回滚）
        Optional<RiskAccountLinkRecord> existing = linkRecordRepository
                .findByRiskEventIdAndLinkAction(riskEventId, linkAction);
        RiskAccountLinkRecord record;
        if (existing.isPresent()) {
            record = existing.get();
        } else {
            record = new RiskAccountLinkRecord();
            record.setRiskEventId(riskEventId);
            record.setLinkAction(linkAction);
            record.setMerchantId(merchantId);
            if (amount != null) {
                record.setAmount(amount);
            }
            record.setReason(reason);
            record.setExecutionStatus(ExecutionStatus.PENDING);
            record = linkRecordRepository.save(record);
        }

        record.setExecutionStatus(ExecutionStatus.FAILED);
        record.setErrorMessage(errorMessage);
        record.setExecutedAt(LocalDateTime.now());
        return linkRecordRepository.save(record);
    }

    // ==================== 内部方法 ====================

    /**
     * 将联动动作映射为账户状态。
     */
    private AccountStatus mapLinkActionToStatus(LinkAction linkAction) {
        return switch (linkAction) {
            case STATUS_FROZEN -> AccountStatus.FROZEN;
            case STATUS_CLOSED -> AccountStatus.CLOSED;
            case STATUS_RESTORED -> AccountStatus.ACTIVE;
            default -> throw new IllegalArgumentException(
                    "非状态变更类联动动作: " + linkAction + ", 请使用 STATUS_FROZEN/STATUS_CLOSED/STATUS_RESTORED");
        };
    }
}