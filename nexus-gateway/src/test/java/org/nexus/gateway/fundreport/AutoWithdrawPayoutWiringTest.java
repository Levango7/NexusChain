package org.nexus.gateway.fundreport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AutoWithdrawService 出款去向接线 + 出款认领协议门禁测试。
 *
 * <p>契约一（P0 修复 2026-10-11）："自动提现"不能只扣账本余额——余额被扣但钱没离开平台；
 * 没有生效出款账户就必须 fail-closed 不扣账。</p>
 *
 * <p>契约二（批次⑤ P0 加固 2026-10-18）：出款调用必须在 DB 事务外，且以"认领先提交"
 * 协议保证任何崩溃/落库失败窗口都不发生二次出款。</p>
 *
 * <p>本测试钉住的契约：</p>
 * <ol>
 *   <li>没有生效出款账户 → <b>不扣账</b>（fail-closed）；</li>
 *   <li>出款失败 → 扣账后<b>退回余额</b>（补偿），规则不推进（lastExecutedAt 不变）、认领清除；</li>
 *   <li>正常路径 → 认领入库 + 扣账 → 出款（事务外）→ 规则推进 + 记凭据 + 清认领；</li>
 *   <li>存在未决认领 → <b>拒绝重复执行</b>（不扣账、不出款）；</li>
 *   <li>出款成功但结果落库失败 → 执行结果可见（PAYOUT_DONE_FINALIZE_FAILED），认领保留待人工核对。</li>
 * </ol>
 *
 * <p>契约三（P0 修复 2026-10-27）：出款结果三态。{@code PayoutOutcome.unverified}
 * （已提交渠道但应答丢失）与执行器返回 null（违反契约）都必须 fail-closed——
 * <b>不退还余额、不清认领、不推进周期</b>，下一轮被认领闸门拒跑交人工核对；
 * 只有抛 {@link PayoutException}（确定未出款）才允许补偿退账 +
 * 清认领重试。旧 {@code String pay(...)} 签名表达不了"结果未知"，
 * 契约仅停留在注释里——这正是本次修复要消除的缺口。</p>
 */
class AutoWithdrawPayoutWiringTest {

    private AutoWithdrawRuleRepository ruleRepository;
    private AccountService accountService;
    private MerchantAccountRepository accountRepository;
    private MerchantPayoutAccountService payoutAccountService;
    private PayoutExecutor payoutExecutor;
    private PlatformTransactionManager transactionManager;
    private AutoWithdrawService service;

    private static final Long MERCHANT_ID = 800L;
    private static final Long RULE_ID = 11L;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(AutoWithdrawRuleRepository.class);
        accountService = mock(AccountService.class);
        accountRepository = mock(MerchantAccountRepository.class);
        payoutAccountService = mock(MerchantPayoutAccountService.class);
        payoutExecutor = mock(PayoutExecutor.class);
        transactionManager = mock(PlatformTransactionManager.class);
        // 单测让事务边界"透传"（回调同步执行）；真实提交/回滚语义由集成环境验证
        lenient().when(transactionManager.getTransaction(any()))
                .thenAnswer(inv -> new SimpleTransactionStatus());
        service = new AutoWithdrawService(ruleRepository, accountService, accountRepository,
                payoutAccountService, payoutExecutor, transactionManager);
    }

    private AutoWithdrawRule rule() {
        AutoWithdrawRule r = new AutoWithdrawRule();
        r.setId(RULE_ID);
        r.setMerchantId(MERCHANT_ID);
        r.setThreshold(new BigDecimal("100.00"));
        r.setMinRetain(BigDecimal.ZERO);
        r.setFrequency(WithdrawFrequency.DAILY);
        r.setEnabled(true);
        return r;
    }

    private MerchantPayoutAccount activeAccount() {
        MerchantPayoutAccount a = new MerchantPayoutAccount();
        a.setId(5L);
        a.setMerchantId(MERCHANT_ID);
        a.setAccountType(MerchantPayoutAccount.AccountType.BANK_CARD);
        a.setAccountNo("6222****8888");
        a.setAccountName("商户口径公司");
        a.setStatus(MerchantPayoutAccount.AccountStatus.ACTIVE);
        return a;
    }

    private void stubBalance(BigDecimal balance) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("balance", balance);
        info.put("frozen", BigDecimal.ZERO);
        info.put("balanceStatus", "NORMAL");
        when(accountService.getBalance(MERCHANT_ID)).thenReturn(info);
        MerchantAccount acc = new MerchantAccount();
        acc.setBalance(balance);
        when(accountRepository.findByMerchantIdAndAccountType(eq(MERCHANT_ID), any()))
                .thenReturn(Optional.of(acc));
    }

    private void stubLockedRule(AutoWithdrawRule r) {
        when(ruleRepository.findByIdForUpdate(RULE_ID)).thenReturn(Optional.of(r));
    }

    @Test
    @DisplayName("无生效出款账户 → 不扣账（fail-closed：不再扣了钱却不出去）")
    void noActiveAccount_noDebit() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID)).thenReturn(Optional.empty());

        Map<String, Object> result = service.executeAutoWithdraw(r);

        assertEquals("NO_PAYOUT_ACCOUNT", result.get("reason"));
        assertEquals(false, result.get("executed"));
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
        verify(ruleRepository, never()).save(any());
    }

    @Test
    @DisplayName("出款失败 → 扣账后把余额退回商户（补偿），规则不推进且认领清除")
    void payoutFailed_refundBack() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        stubLockedRule(r);
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID))
                .thenReturn(Optional.of(activeAccount()));

        MerchantAccount after = new MerchantAccount();
        after.setBalance(new BigDecimal("0.00"));
        when(accountService.withdrawWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.AUTO_WITHDRAW))).thenReturn(after);
        when(payoutExecutor.payWithOutcome(eq(MERCHANT_ID), any(), any(), anyString()))
                .thenThrow(new PayoutException("代付通道不可用"));

        Map<String, Object> result = service.executeAutoWithdraw(r);

        assertEquals("PAYOUT_FAILED_REFUNDED", result.get("reason"));
        assertEquals(true, result.get("refunded"));
        // 必须把已扣的钱退回
        verify(accountService).depositWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.RECON_ADJUST));
        // 规则不推进（下周期可重试）：lastExecutedAt 不变；认领已清除（可重试）
        assertNull(r.getLastExecutedAt());
        assertNull(r.getPayoutClaimReference());
    }

    @Test
    @DisplayName("正常路径：认领入库 + 扣账 → 出款（事务外）→ 规则推进 + 记凭据 + 清认领")
    void happyPath_debitThenPayout() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        stubLockedRule(r);
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID))
                .thenReturn(Optional.of(activeAccount()));
        MerchantAccount after = new MerchantAccount();
        after.setBalance(new BigDecimal("900.00"));
        when(accountService.withdrawWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.AUTO_WITHDRAW))).thenReturn(after);
        when(payoutExecutor.payWithOutcome(eq(MERCHANT_ID), any(), eq(new BigDecimal("1000.00")),
                anyString())).thenReturn(PayoutOutcome.executed("MANUAL_PAYOUT_TICKET_1"));
        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.executeAutoWithdraw(r);

        assertEquals(true, result.get("executed"));
        assertEquals("MANUAL_PAYOUT_TICKET_1", result.get("payoutRef"));
        assertEquals("BANK_CARD", result.get("payoutAccountType"));
        // 规则推进：lastExecutedAt + 凭据 + 认领清除
        assertNotNull(r.getLastExecutedAt());
        assertEquals("MANUAL_PAYOUT_TICKET_1", r.getLastPayoutRef());
        assertNull(r.getPayoutClaimReference());
        verify(ruleRepository, times(2)).save(r);
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
    }

    @Test
    @DisplayName("认领先于出款提交：规则保存（认领段）必须发生在 payout 调用之前")
    void claimPersistedBeforePayout() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        stubLockedRule(r);
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID))
                .thenReturn(Optional.of(activeAccount()));
        MerchantAccount after = new MerchantAccount();
        after.setBalance(new BigDecimal("900.00"));
        when(accountService.withdrawWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.AUTO_WITHDRAW))).thenReturn(after);
        when(payoutExecutor.payWithOutcome(eq(MERCHANT_ID), any(), any(), anyString()))
                .thenReturn(PayoutOutcome.executed("MANUAL_PAYOUT_TICKET_1"));
        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeAutoWithdraw(r);

        InOrder inOrder = inOrder(ruleRepository, payoutExecutor);
        inOrder.verify(ruleRepository).save(any());
        inOrder.verify(payoutExecutor).payWithOutcome(eq(MERCHANT_ID), any(), any(), anyString());
    }

    @Test
    @DisplayName("存在未决认领 → 拒绝重复执行（不扣账、不出款）")
    void activeClaim_skipsWithoutDebit() {
        AutoWithdrawRule r = rule();
        r.setPayoutClaimReference("AUTO_WITHDRAW_2026-10-18_11");
        r.setPayoutClaimAt(LocalDateTime.now().minusHours(1));
        stubBalance(new BigDecimal("1000.00"));
        stubLockedRule(r);

        Map<String, Object> result = service.executeAutoWithdraw(r);

        assertEquals("PAYOUT_CLAIM_ACTIVE", result.get("reason"));
        assertEquals("AUTO_WITHDRAW_2026-10-18_11", result.get("claimReference"));
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
        verify(payoutExecutor, never()).payWithOutcome(any(), any(), any(), any());
        verify(ruleRepository, never()).save(any());
    }

    @Test
    @DisplayName("出款成功但结果落库失败 → 可见失败（PAYOUT_DONE_FINALIZE_FAILED），认领保留")
    void finalizeFailure_claimRetained() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        // 第一次（认领段）能锁到规则；第二次（落库段）规则不可读 → 落库失败
        when(ruleRepository.findByIdForUpdate(RULE_ID))
                .thenReturn(Optional.of(r), Optional.empty());
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID))
                .thenReturn(Optional.of(activeAccount()));
        MerchantAccount after = new MerchantAccount();
        after.setBalance(new BigDecimal("900.00"));
        when(accountService.withdrawWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.AUTO_WITHDRAW))).thenReturn(after);
        when(payoutExecutor.payWithOutcome(eq(MERCHANT_ID), any(), any(), anyString()))
                .thenReturn(PayoutOutcome.executed("MANUAL_PAYOUT_TICKET_1"));
        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.executeAutoWithdraw(r);

        assertEquals("PAYOUT_DONE_FINALIZE_FAILED", result.get("reason"));
        assertEquals("MANUAL_PAYOUT_TICKET_1", result.get("payoutRef"));
        // 认领保留（未清）→ 下轮拒跑并告警；lastExecutedAt 未推进
        assertEquals("AUTO_WITHDRAW_" + LocalDate.now() + "_11", r.getPayoutClaimReference());
        assertNull(r.getLastExecutedAt());
    }

    @Test
    @DisplayName("执行协议未装配事务 → fail-closed 拒绝执行（不退化回事务内出款）")
    void withoutTransactionSupport_failsClosed() {
        AutoWithdrawService noTxService = new AutoWithdrawService(ruleRepository, accountService,
                accountRepository, payoutAccountService, payoutExecutor, null);

        assertThrows(IllegalStateException.class, () -> noTxService.executeAutoWithdraw(rule()));
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
        verify(payoutExecutor, never()).payWithOutcome(any(), any(), any(), any());
    }

    // ==================== 出款三态：结果未知必须 fail-closed（P0 修复 2026-10-27） ====================

    @Test
    @DisplayName("出款结果未知（可能已出款）→ 不退余额、不清认领、不推进（交人工核对）")
    void payoutUnverified_noRefund_claimRetained() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        stubLockedRule(r);
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID))
                .thenReturn(Optional.of(activeAccount()));
        MerchantAccount after = new MerchantAccount();
        after.setBalance(new BigDecimal("0.00"));
        when(accountService.withdrawWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.AUTO_WITHDRAW))).thenReturn(after);
        // 渠道已受理但响应丢失：正确实现必须回 unverified，而非抛异常/伪造凭据
        when(payoutExecutor.payWithOutcome(eq(MERCHANT_ID), any(), any(), anyString()))
                .thenReturn(PayoutOutcome.unverified("channel timeout after submit"));

        Map<String, Object> result = service.executeAutoWithdraw(r);

        assertEquals("PAYOUT_UNVERIFIED_MANUAL_REQUIRED", result.get("reason"));
        assertEquals(false, result.get("executed"));
        assertEquals(true, result.get("payoutUnverified"));
        // 核心不变式：钱可能已经出去了 —— 绝不退余额（否则"出去了还退账"双重损失）
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        // 认领保留 ⇒ 后续轮次在认领闸门被拒（fail-closed），等人工核对后再处置
        assertEquals("AUTO_WITHDRAW_" + LocalDate.now() + "_11", r.getPayoutClaimReference());
        assertNotNull(r.getPayoutClaimAt());
        // 周期不推进（未拿到凭据，lastPayoutRef 不写入）
        assertNull(r.getLastExecutedAt());
        assertNull(r.getLastPayoutRef());
    }

    @Test
    @DisplayName("结果未知后的下一轮：认领闸门拦停（不扣账、不出款）——闭环验证 fail-closed")
    void afterUnverified_nextRunRefusedByClaimGate() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        stubLockedRule(r);
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID))
                .thenReturn(Optional.of(activeAccount()));
        MerchantAccount after = new MerchantAccount();
        after.setBalance(new BigDecimal("0.00"));
        when(accountService.withdrawWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.AUTO_WITHDRAW))).thenReturn(after);
        when(payoutExecutor.payWithOutcome(eq(MERCHANT_ID), any(), any(), anyString()))
                .thenReturn(PayoutOutcome.unverified("channel timeout after submit"));

        Map<String, Object> first = service.executeAutoWithdraw(r);
        assertEquals("PAYOUT_UNVERIFIED_MANUAL_REQUIRED", first.get("reason"));

        clearInvocations(accountService, payoutExecutor);
        Map<String, Object> second = service.executeAutoWithdraw(r);

        assertEquals("PAYOUT_CLAIM_ACTIVE", second.get("reason"),
                "结果未知必须留下认领，下一轮直接拒跑（宁滞留待人工核对，勿自动重试）");
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
        verify(payoutExecutor, never()).payWithOutcome(any(), any(), any(), any());
    }

    @Test
    @DisplayName("执行器违反契约返回 null → 按结果未知 fail-closed（不退余额、认领保留）")
    void payoutNullOutcome_failsClosedAsUnverified() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        stubLockedRule(r);
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID))
                .thenReturn(Optional.of(activeAccount()));
        MerchantAccount after = new MerchantAccount();
        after.setBalance(new BigDecimal("0.00"));
        when(accountService.withdrawWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.AUTO_WITHDRAW))).thenReturn(after);
        when(payoutExecutor.payWithOutcome(eq(MERCHANT_ID), any(), any(), anyString()))
                .thenReturn(null);

        Map<String, Object> result = service.executeAutoWithdraw(r);

        assertEquals("PAYOUT_UNVERIFIED_MANUAL_REQUIRED", result.get("reason"));
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        assertNotNull(r.getPayoutClaimReference());
    }

    @Test
    @DisplayName("PayoutOutcome 契约：成功必须携带凭据号；不携带即拒（不许伪造成功）")
    void payoutOutcome_executedRequiresReference() {
        assertThrows(IllegalArgumentException.class, () -> PayoutOutcome.executed(null));
        assertThrows(IllegalArgumentException.class, () -> PayoutOutcome.executed("  "));
        assertTrue(PayoutOutcome.unverified("timeout").isUnverified());
        assertFalse(PayoutOutcome.executed("TICKET-1").isUnverified());
        assertEquals("TICKET-1", PayoutOutcome.executed("TICKET-1").reference());
    }
}
