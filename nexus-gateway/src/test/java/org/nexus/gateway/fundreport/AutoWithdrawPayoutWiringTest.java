package org.nexus.gateway.fundreport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AutoWithdrawService 出款去向接线门禁测试（P0 修复 2026-10-11）。
 *
 * <p>此前"自动提现"只扣账本余额、没有任何出款动作——余额被扣但钱没离开平台。
 * 本测试钉住新契约：</p>
 * <ol>
 *   <li>没有生效出款账户 → <b>不扣账</b>（fail-closed）；</li>
 *   <li>出款失败 → 扣账后<b>退回余额</b>，规则不推进；</li>
 *   <li>正常路径 → 扣账 + 登记出款 + 规则推进。</li>
 * </ol>
 */
class AutoWithdrawPayoutWiringTest {

    private AutoWithdrawRuleRepository ruleRepository;
    private AccountService accountService;
    private MerchantAccountRepository accountRepository;
    private MerchantPayoutAccountService payoutAccountService;
    private PayoutExecutor payoutExecutor;
    private AutoWithdrawService service;

    private static final Long MERCHANT_ID = 800L;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(AutoWithdrawRuleRepository.class);
        accountService = mock(AccountService.class);
        accountRepository = mock(MerchantAccountRepository.class);
        payoutAccountService = mock(MerchantPayoutAccountService.class);
        payoutExecutor = mock(PayoutExecutor.class);
        service = new AutoWithdrawService(ruleRepository, accountService, accountRepository,
                payoutAccountService, payoutExecutor);
    }

    private AutoWithdrawRule rule() {
        AutoWithdrawRule r = new AutoWithdrawRule();
        r.setId(11L);
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
    @DisplayName("出款失败 → 扣账后把余额退回商户（补偿），规则不推进")
    void payoutFailed_refundBack() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID))
                .thenReturn(Optional.of(activeAccount()));

        MerchantAccount after = new MerchantAccount();
        after.setBalance(new BigDecimal("0.00"));
        when(accountService.withdrawWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.AUTO_WITHDRAW))).thenReturn(after);
        when(accountService.depositWithType(eq(MERCHANT_ID), any(),
                eq("AUTO_WITHDRAW_ROLLBACK_AUTO_WITHDRAW_2026-10-11_11"),
                eq(AccountOperationType.RECON_ADJUST))).thenReturn(after);
        when(payoutExecutor.pay(eq(MERCHANT_ID), any(), any(), anyString()))
                .thenThrow(new PayoutException("代付通道不可用"));

        Map<String, Object> result = service.executeAutoWithdraw(r);

        assertEquals("PAYOUT_FAILED_REFUNDED", result.get("reason"));
        // 必须把已扣的钱退回
        verify(accountService).depositWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.RECON_ADJUST));
        // 规则不推进（下周期可重试）
        verify(ruleRepository, never()).save(r);
    }

    @Test
    @DisplayName("正常路径：扣账 + 登记出款 + 规则推进")
    void happyPath_debitThenPayout() {
        AutoWithdrawRule r = rule();
        stubBalance(new BigDecimal("1000.00"));
        when(payoutAccountService.resolveActiveAccount(MERCHANT_ID))
                .thenReturn(Optional.of(activeAccount()));
        MerchantAccount after = new MerchantAccount();
        after.setBalance(new BigDecimal("900.00"));
        when(accountService.withdrawWithType(eq(MERCHANT_ID), any(), anyString(),
                eq(AccountOperationType.AUTO_WITHDRAW))).thenReturn(after);
        when(payoutExecutor.pay(eq(MERCHANT_ID), any(), eq(new BigDecimal("1000.00")),
                anyString())).thenReturn("MANUAL_PAYOUT_TICKET_1");
        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.executeAutoWithdraw(r);

        assertEquals(true, result.get("executed"));
        assertEquals("MANUAL_PAYOUT_TICKET_1", result.get("payoutRef"));
        assertEquals("BANK_CARD", result.get("payoutAccountType"));
        verify(ruleRepository).save(r);
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
    }
}
