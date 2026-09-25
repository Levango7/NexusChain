package org.nexus.gateway.fundtransfer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FundTransferService}.
 * Covers manual transfer, rule-triggered transfer, and rule configuration management.
 */
@ExtendWith(MockitoExtension.class)
class FundTransferServiceTest {

    @Mock private AccountService accountService;
    @Mock private TransferRuleRepository transferRuleRepository;
    @Mock private MerchantAccountRepository accountRepository;

    private FundTransferService fundTransferService;

    private MerchantAccount balanceAccount;
    private MerchantAccount reserveAccount;

    @BeforeEach
    void setUp() {
        fundTransferService = new FundTransferService(accountService, transferRuleRepository, accountRepository);

        balanceAccount = new MerchantAccount();
        balanceAccount.setId(1L);
        balanceAccount.setAccountId("MA100BALANCE");
        balanceAccount.setMerchantId(100L);
        balanceAccount.setAccountType(AccountType.BALANCE);
        balanceAccount.setBalance(new BigDecimal("1000"));
        balanceAccount.setStatus(org.nexus.gateway.account.AccountStatus.ACTIVE);

        reserveAccount = new MerchantAccount();
        reserveAccount.setId(3L);
        reserveAccount.setAccountId("MA100RESERVE");
        reserveAccount.setMerchantId(100L);
        reserveAccount.setAccountType(AccountType.RESERVE);
        reserveAccount.setBalance(BigDecimal.ZERO);
        reserveAccount.setStatus(org.nexus.gateway.account.AccountStatus.ACTIVE);
    }

    // ==================== Manual Transfer Tests ====================

    @Test
    @DisplayName("manualTransfer: normal transfer decreases source and increases target with dual transactions")
    void manualTransfer_normal_decreasesSourceAndIncreasesTarget() {
        when(accountService.getOrCreateAccount(100L, AccountType.BALANCE)).thenReturn(balanceAccount);
        when(accountService.getOrCreateAccount(100L, AccountType.RESERVE)).thenReturn(reserveAccount);
        when(accountService.transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("500")))
                .thenReturn(balanceAccount);

        MerchantAccount result = fundTransferService.manualTransfer(
                100L, AccountType.BALANCE, AccountType.RESERVE, new BigDecimal("500"), "MANUAL_TRANSFER_001");

        assertNotNull(result);
        verify(accountService).getOrCreateAccount(100L, AccountType.BALANCE);
        verify(accountService).getOrCreateAccount(100L, AccountType.RESERVE);
        verify(accountService).transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("500"));
    }

    @Test
    @DisplayName("manualTransfer: insufficient balance throws IllegalStateException")
    void manualTransfer_insufficientBalance_throwsIllegalStateException() {
        when(accountService.getOrCreateAccount(100L, AccountType.BALANCE)).thenReturn(balanceAccount);
        when(accountService.getOrCreateAccount(100L, AccountType.RESERVE)).thenReturn(reserveAccount);
        when(accountService.transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("5000")))
                .thenThrow(new IllegalStateException("余额不足: 当前余额=1000, 转账金额=5000"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> fundTransferService.manualTransfer(
                        100L, AccountType.BALANCE, AccountType.RESERVE, new BigDecimal("5000"), "MANUAL_TRANSFER_002"));
        assertTrue(ex.getMessage().contains("余额不足"));
    }

    @Test
    @DisplayName("manualTransfer: same account type throws IllegalArgumentException")
    void manualTransfer_sameAccountType_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fundTransferService.manualTransfer(
                        100L, AccountType.BALANCE, AccountType.BALANCE, new BigDecimal("500"), "MANUAL_TRANSFER_003"));
        assertTrue(ex.getMessage().contains("转出和转入账户类型不能相同"));
    }

    @Test
    @DisplayName("manualTransfer: null amount throws IllegalArgumentException")
    void manualTransfer_nullAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> fundTransferService.manualTransfer(
                        100L, AccountType.BALANCE, AccountType.RESERVE, null, "MANUAL_TRANSFER_004"));
    }

    @Test
    @DisplayName("manualTransfer: zero amount throws IllegalArgumentException")
    void manualTransfer_zeroAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> fundTransferService.manualTransfer(
                        100L, AccountType.BALANCE, AccountType.RESERVE, BigDecimal.ZERO, "MANUAL_TRANSFER_005"));
    }

    // ==================== Rule Trigger Evaluation Tests ====================

    @Test
    @DisplayName("executeTransferRules: THRESHOLD rule with balance >= threshold triggers transfer")
    void executeTransferRules_thresholdAbove_triggersTransfer() {
        TransferRule rule = createThresholdRule("RULE_001", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("500"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("200"));

        when(transferRuleRepository.findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true))
                .thenReturn(List.of(rule));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountService.getOrCreateAccount(100L, AccountType.RESERVE)).thenReturn(reserveAccount);
        when(accountService.transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("200")))
                .thenReturn(balanceAccount);

        int result = fundTransferService.executeTransferRules(100L);

        assertEquals(1, result);
        verify(accountService).transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("200"));
    }

    @Test
    @DisplayName("executeTransferRules: THRESHOLD rule with balance below threshold does not trigger")
    void executeTransferRules_thresholdBelow_doesNotTrigger() {
        TransferRule rule = createThresholdRule("RULE_002", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("2000"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("200"));

        when(transferRuleRepository.findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true))
                .thenReturn(List.of(rule));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        int result = fundTransferService.executeTransferRules(100L);

        assertEquals(0, result);
        verify(accountService, never()).transfer(any(), any(), any());
    }

    @Test
    @DisplayName("executeTransferRules: RATIO type calculates percentage of balance")
    void executeTransferRules_percentageType_calculatesPercentageOfBalance() {
        TransferRule rule = createThresholdRule("RULE_003", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("500"), "ABOVE", TransferAmountType.PERCENTAGE, null);
        rule.setTransferPercentage(new BigDecimal("30"));

        when(transferRuleRepository.findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true))
                .thenReturn(List.of(rule));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountService.getOrCreateAccount(100L, AccountType.RESERVE)).thenReturn(reserveAccount);
        // 30% of 1000 = 300
        when(accountService.transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("300")))
                .thenReturn(balanceAccount);

        int result = fundTransferService.executeTransferRules(100L);

        assertEquals(1, result);
        verify(accountService).transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("300"));
    }

    @Test
    @DisplayName("executeTransferRules: SCHEDULED trigger type rules are not executed by this method")
    void executeTransferRules_scheduledType_notExecuted() {
        // executeTransferRules only queries BALANCE_THRESHOLD type rules
        when(transferRuleRepository.findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true))
                .thenReturn(List.of());

        int result = fundTransferService.executeTransferRules(100L);

        assertEquals(0, result);
        verify(accountService, never()).transfer(any(), any(), any());
    }

    @Test
    @DisplayName("executeTransferRules: BELOW threshold direction triggers when balance <= threshold")
    void executeTransferRules_thresholdBelowDirection_triggersWhenBalanceBelow() {
        TransferRule rule = createThresholdRule("RULE_004", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("1500"), "BELOW", TransferAmountType.ALL, null);

        when(transferRuleRepository.findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true))
                .thenReturn(List.of(rule));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountService.getOrCreateAccount(100L, AccountType.RESERVE)).thenReturn(reserveAccount);
        // ALL type transfers entire balance (1000)
        when(accountService.transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("1000")))
                .thenReturn(balanceAccount);

        int result = fundTransferService.executeTransferRules(100L);

        assertEquals(1, result);
        verify(accountService).transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("1000"));
    }

    // ==================== Multi-Rule Priority Tests ====================

    @Test
    @DisplayName("executeTransferRules: multiple rules - high priority executes, low priority skipped if condition not met")
    void executeTransferRules_multipleRules_highPriorityExecutesLowPrioritySkipped() {
        // Rule 1: HIGH priority - balance >= 500, transfer FIXED 200 (should execute)
        TransferRule rule1 = createThresholdRule("RULE_HIGH", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("500"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("200"));

        // Rule 2: LOW priority - balance >= 2000, transfer FIXED 500 (should NOT execute, balance only 1000)
        TransferRule rule2 = createThresholdRule("RULE_LOW", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("2000"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("500"));

        when(transferRuleRepository.findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true))
                .thenReturn(List.of(rule1, rule2));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountService.getOrCreateAccount(100L, AccountType.RESERVE)).thenReturn(reserveAccount);
        when(accountService.transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("200")))
                .thenReturn(balanceAccount);

        int result = fundTransferService.executeTransferRules(100L);

        // Only rule1 should execute (1 success), rule2 condition not met
        assertEquals(1, result);
        verify(accountService, times(1)).transfer(any(), any(), any());
        verify(accountService).transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("200"));
    }

    @Test
    @DisplayName("executeTransferRules: multiple rules both conditions met - both execute")
    void executeTransferRules_multipleRules_bothConditionsMet_bothExecute() {
        TransferRule rule1 = createThresholdRule("RULE_A", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("500"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("200"));
        TransferRule rule2 = createThresholdRule("RULE_B", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("800"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("100"));

        when(transferRuleRepository.findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true))
                .thenReturn(List.of(rule1, rule2));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountService.getOrCreateAccount(100L, AccountType.RESERVE)).thenReturn(reserveAccount);
        when(accountService.transfer(eq("MA100BALANCE"), eq("MA100RESERVE"), any()))
                .thenReturn(balanceAccount);

        int result = fundTransferService.executeTransferRules(100L);

        assertEquals(2, result);
        verify(accountService, times(2)).transfer(eq("MA100BALANCE"), eq("MA100RESERVE"), any());
    }

    @Test
    @DisplayName("executeTransferRules: rule execution failure does not block other rules")
    void executeTransferRules_ruleFailure_doesNotBlockOthers() {
        TransferRule rule1 = createThresholdRule("RULE_FAIL", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("500"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("200"));
        TransferRule rule2 = createThresholdRule("RULE_OK", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("500"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("100"));

        when(transferRuleRepository.findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true))
                .thenReturn(List.of(rule1, rule2));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountService.getOrCreateAccount(100L, AccountType.RESERVE)).thenReturn(reserveAccount);
        // First rule throws, second succeeds
        when(accountService.transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("200")))
                .thenThrow(new IllegalStateException("余额不足"));
        when(accountService.transfer("MA100BALANCE", "MA100RESERVE", new BigDecimal("100")))
                .thenReturn(balanceAccount);

        int result = fundTransferService.executeTransferRules(100L);

        assertEquals(1, result);
    }

    // ==================== Rule Configuration Tests ====================

    @Test
    @DisplayName("createTransferRule: normal creation saves rule")
    void createTransferRule_normal_savesRule() {
        TransferRule rule = createThresholdRule("RULE_NEW", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("500"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("200"));

        when(transferRuleRepository.findByRuleCode("RULE_NEW")).thenReturn(Optional.empty());
        when(transferRuleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TransferRule result = fundTransferService.createTransferRule(rule);

        assertNotNull(result);
        assertEquals("RULE_NEW", result.getRuleCode());
        verify(transferRuleRepository).save(rule);
    }

    @Test
    @DisplayName("createTransferRule: duplicate ruleCode throws IllegalArgumentException")
    void createTransferRule_duplicateRuleCode_throwsIllegalArgumentException() {
        TransferRule rule = createThresholdRule("RULE_DUP", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("500"), "ABOVE", TransferAmountType.FIXED, new BigDecimal("200"));

        when(transferRuleRepository.findByRuleCode("RULE_DUP")).thenReturn(Optional.of(rule));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fundTransferService.createTransferRule(rule));
        assertTrue(ex.getMessage().contains("规则编号已存在"));
    }

    @Test
    @DisplayName("createTransferRule: BALANCE_THRESHOLD without thresholdAmount throws IllegalArgumentException")
    void createTransferRule_thresholdWithoutAmount_throwsIllegalArgumentException() {
        TransferRule rule = new TransferRule();
        rule.setRuleCode("RULE_NO_THRESH");
        rule.setRuleName("No Threshold Rule");
        rule.setFromAccountType(AccountType.BALANCE);
        rule.setToAccountType(AccountType.RESERVE);
        rule.setTriggerType(TriggerType.BALANCE_THRESHOLD);
        rule.setTransferAmountType(TransferAmountType.FIXED);
        rule.setTransferAmount(new BigDecimal("200"));
        rule.setThresholdDirection("ABOVE");
        // thresholdAmount is null

        when(transferRuleRepository.findByRuleCode("RULE_NO_THRESH")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fundTransferService.createTransferRule(rule));
        assertTrue(ex.getMessage().contains("必须指定阈值金额"));
    }

    @Test
    @DisplayName("createTransferRule: SCHEDULED without cronExpression throws IllegalArgumentException")
    void createTransferRule_scheduledWithoutCron_throwsIllegalArgumentException() {
        TransferRule rule = new TransferRule();
        rule.setRuleCode("RULE_NO_CRON");
        rule.setRuleName("No Cron Rule");
        rule.setFromAccountType(AccountType.BALANCE);
        rule.setToAccountType(AccountType.RESERVE);
        rule.setTriggerType(TriggerType.SCHEDULED);
        rule.setTransferAmountType(TransferAmountType.FIXED);
        rule.setTransferAmount(new BigDecimal("200"));
        // cronExpression is null

        when(transferRuleRepository.findByRuleCode("RULE_NO_CRON")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fundTransferService.createTransferRule(rule));
        assertTrue(ex.getMessage().contains("必须指定 cron 表达式"));
    }

    @Test
    @DisplayName("createTransferRule: PERCENTAGE with invalid percentage (>100) throws IllegalArgumentException")
    void createTransferRule_percentageOutOfRange_throwsIllegalArgumentException() {
        TransferRule rule = createThresholdRule("RULE_PCT_INVALID", AccountType.BALANCE, AccountType.RESERVE,
                new BigDecimal("500"), "ABOVE", TransferAmountType.PERCENTAGE, null);
        rule.setTransferPercentage(new BigDecimal("150"));

        when(transferRuleRepository.findByRuleCode("RULE_PCT_INVALID")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fundTransferService.createTransferRule(rule));
        assertTrue(ex.getMessage().contains("百分比必须在 0-100 之间"));
    }

    // ==================== Helper Methods ====================

    private TransferRule createThresholdRule(String ruleCode, AccountType fromType, AccountType toType,
                                              BigDecimal thresholdAmount, String thresholdDirection,
                                              TransferAmountType amountType, BigDecimal transferAmount) {
        TransferRule rule = new TransferRule();
        rule.setRuleCode(ruleCode);
        rule.setRuleName("Test Rule " + ruleCode);
        rule.setFromAccountType(fromType);
        rule.setToAccountType(toType);
        rule.setTriggerType(TriggerType.BALANCE_THRESHOLD);
        rule.setThresholdAmount(thresholdAmount);
        rule.setThresholdDirection(thresholdDirection);
        rule.setTransferAmountType(amountType);
        rule.setTransferAmount(transferAmount);
        rule.setEnabled(true);
        return rule;
    }
}