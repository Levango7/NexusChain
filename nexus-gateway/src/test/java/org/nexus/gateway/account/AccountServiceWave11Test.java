package org.nexus.gateway.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Wave 11 基础设施层单元测试 — 覆盖新增的 creditOnClearing、depositWithType、withdrawWithType 方法。
 *
 * <p>测试场景：</p>
 * <ul>
 *   <li>creditOnClearing：正常入账 + 幂等跳过 + 账户冻结拒绝</li>
 *   <li>depositWithType：正常操作 + 余额变更流水验证</li>
 *   <li>withdrawWithType：正常操作 + 余额不足拒绝</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceWave11Test {

    @Mock private MerchantAccountRepository accountRepository;
    @Mock private AccountTransactionRepository transactionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private AccountService accountService;

    private MerchantAccount balanceAccount;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, transactionRepository, eventPublisher, transactionManager);

        balanceAccount = new MerchantAccount();
        balanceAccount.setId(1L);
        balanceAccount.setAccountId("MA100BALANCE");
        balanceAccount.setMerchantId(100L);
        balanceAccount.setAccountType(AccountType.BALANCE);
        balanceAccount.setBalance(BigDecimal.ZERO);
        balanceAccount.setStatus(AccountStatus.ACTIVE);
    }

    // ==================== creditOnClearing Tests ====================

    @Test
    @DisplayName("creditOnClearing: 正常清算入账增加余额")
    void creditOnClearing_normalCase_increasesBalance() {
        balanceAccount.setBalance(new BigDecimal("100"));
        when(transactionRepository.findByReference("CLEARING-001"))
                .thenReturn(Collections.emptyList());
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.creditOnClearing(100L, new BigDecimal("50"), "CLEARING-001");

        assertNotNull(result);
        assertEquals(new BigDecimal("150"), result.getBalance());

        ArgumentCaptor<AccountTransaction> txCaptor = ArgumentCaptor.forClass(AccountTransaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertEquals(AccountOperationType.CLEARING_SETTLE, txCaptor.getValue().getOperationType());
        assertEquals(TransactionDirection.CREDIT, txCaptor.getValue().getDirection());
        assertEquals("CLEARING-001", txCaptor.getValue().getReference());
        verify(eventPublisher).publishEvent(any(AccountBalanceChangedEvent.class));
    }

    @Test
    @DisplayName("creditOnClearing: 重复清算订单号跳过（幂等）")
    void creditOnClearing_duplicateClearingOrderId_skips() {
        AccountTransaction existingTx = new AccountTransaction();
        existingTx.setReference("CLEARING-001");
        when(transactionRepository.findByReference("CLEARING-001"))
                .thenReturn(List.of(existingTx));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        MerchantAccount result = accountService.creditOnClearing(100L, new BigDecimal("50"), "CLEARING-001");

        assertNotNull(result);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("creditOnClearing: 账户冻结时拒绝入账")
    void creditOnClearing_frozenAccount_throwsIllegalStateException() {
        balanceAccount.setStatus(AccountStatus.FROZEN);
        when(transactionRepository.findByReference("CLEARING-002"))
                .thenReturn(Collections.emptyList());
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        assertThrows(IllegalStateException.class,
                () -> accountService.creditOnClearing(100L, new BigDecimal("50"), "CLEARING-002"));
    }

    @Test
    @DisplayName("creditOnClearing: 金额 <= 0 抛出 IllegalArgumentException")
    void creditOnClearing_zeroAmount_throwsIllegalArgumentException() {
        when(transactionRepository.findByReference("CLEARING-003"))
                .thenReturn(Collections.emptyList());

        assertThrows(IllegalArgumentException.class,
                () -> accountService.creditOnClearing(100L, BigDecimal.ZERO, "CLEARING-003"));
    }

    // ==================== depositWithType Tests ====================

    @Test
    @DisplayName("depositWithType: 正常存款增加余额并记录流水")
    void depositWithType_normalCase_increasesBalanceAndRecordsTransaction() {
        balanceAccount.setBalance(new BigDecimal("100"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.depositWithType(
                100L, new BigDecimal("50"), "RECON-001", AccountOperationType.RECON_ADJUST);

        assertNotNull(result);
        assertEquals(new BigDecimal("150"), result.getBalance());

        ArgumentCaptor<AccountTransaction> txCaptor = ArgumentCaptor.forClass(AccountTransaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertEquals(AccountOperationType.RECON_ADJUST, txCaptor.getValue().getOperationType());
        assertEquals(TransactionDirection.CREDIT, txCaptor.getValue().getDirection());
        assertEquals("RECON-001", txCaptor.getValue().getReference());
        assertEquals(new BigDecimal("100"), txCaptor.getValue().getBalanceBefore());
        assertEquals(new BigDecimal("150"), txCaptor.getValue().getBalanceAfter());
        verify(eventPublisher).publishEvent(any(AccountBalanceChangedEvent.class));
    }

    @Test
    @DisplayName("depositWithType: 金额 <= 0 抛出 IllegalArgumentException")
    void depositWithType_zeroAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.depositWithType(
                        100L, BigDecimal.ZERO, "REF", AccountOperationType.RECON_ADJUST));
    }

    @Test
    @DisplayName("depositWithType: 账户冻结时拒绝存款")
    void depositWithType_frozenAccount_throwsIllegalStateException() {
        balanceAccount.setStatus(AccountStatus.FROZEN);
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        assertThrows(IllegalStateException.class,
                () -> accountService.depositWithType(
                        100L, new BigDecimal("50"), "REF", AccountOperationType.RECON_ADJUST));
    }

    @Test
    @DisplayName("depositWithType: 支持 SUSPENSE_WRITEOFF 操作类型")
    void depositWithType_suspenseWriteoff_recordsCorrectType() {
        balanceAccount.setBalance(new BigDecimal("200"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.depositWithType(
                100L, new BigDecimal("30"), "SUSPENSE-001", AccountOperationType.SUSPENSE_WRITEOFF);

        assertEquals(new BigDecimal("230"), result.getBalance());

        ArgumentCaptor<AccountTransaction> txCaptor = ArgumentCaptor.forClass(AccountTransaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertEquals(AccountOperationType.SUSPENSE_WRITEOFF, txCaptor.getValue().getOperationType());
    }

    // ==================== withdrawWithType Tests ====================

    @Test
    @DisplayName("withdrawWithType: 正常取款减少余额并记录流水")
    void withdrawWithType_normalCase_decreasesBalanceAndRecordsTransaction() {
        balanceAccount.setBalance(new BigDecimal("100"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.withdrawWithType(
                100L, new BigDecimal("50"), "AUTO-WD-001", AccountOperationType.AUTO_WITHDRAW);

        assertNotNull(result);
        assertEquals(new BigDecimal("50"), result.getBalance());

        ArgumentCaptor<AccountTransaction> txCaptor = ArgumentCaptor.forClass(AccountTransaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertEquals(AccountOperationType.AUTO_WITHDRAW, txCaptor.getValue().getOperationType());
        assertEquals(TransactionDirection.DEBIT, txCaptor.getValue().getDirection());
        assertEquals("AUTO-WD-001", txCaptor.getValue().getReference());
        assertEquals(new BigDecimal("100"), txCaptor.getValue().getBalanceBefore());
        assertEquals(new BigDecimal("50"), txCaptor.getValue().getBalanceAfter());
        verify(eventPublisher).publishEvent(any(AccountBalanceChangedEvent.class));
    }

    @Test
    @DisplayName("withdrawWithType: 余额不足时拒绝取款")
    void withdrawWithType_insufficientBalance_throwsIllegalStateException() {
        balanceAccount.setBalance(new BigDecimal("30"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> accountService.withdrawWithType(
                        100L, new BigDecimal("50"), "AUTO-WD-002", AccountOperationType.AUTO_WITHDRAW));
        assertTrue(ex.getMessage().contains("余额不足"));
    }

    @Test
    @DisplayName("withdrawWithType: 金额 <= 0 抛出 IllegalArgumentException")
    void withdrawWithType_zeroAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.withdrawWithType(
                        100L, BigDecimal.ZERO, "REF", AccountOperationType.AUTO_WITHDRAW));
    }

    @Test
    @DisplayName("withdrawWithType: 账户冻结时拒绝取款")
    void withdrawWithType_frozenAccount_throwsIllegalStateException() {
        balanceAccount.setStatus(AccountStatus.FROZEN);
        balanceAccount.setBalance(new BigDecimal("100"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        assertThrows(IllegalStateException.class,
                () -> accountService.withdrawWithType(
                        100L, new BigDecimal("50"), "REF", AccountOperationType.AUTO_WITHDRAW));
    }

    @Test
    @DisplayName("withdrawWithType: 支持 RESERVE_REPLENISH 操作类型")
    void withdrawWithType_reserveReplenish_recordsCorrectType() {
        balanceAccount.setBalance(new BigDecimal("200"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.withdrawWithType(
                100L, new BigDecimal("80"), "RESERVE-001", AccountOperationType.RESERVE_REPLENISH);

        assertEquals(new BigDecimal("120"), result.getBalance());

        ArgumentCaptor<AccountTransaction> txCaptor = ArgumentCaptor.forClass(AccountTransaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertEquals(AccountOperationType.RESERVE_REPLENISH, txCaptor.getValue().getOperationType());
    }

    // ==================== AccountOperationType Enum Tests ====================

    @Test
    @DisplayName("AccountOperationType: 包含所有 8 种新增操作类型")
    void accountOperationType_containsAllNewTypes() {
        assertNotNull(AccountOperationType.valueOf("CLEARING_SETTLE"));
        assertNotNull(AccountOperationType.valueOf("SETTLEMENT_TRANSFER"));
        assertNotNull(AccountOperationType.valueOf("RECON_ADJUST"));
        assertNotNull(AccountOperationType.valueOf("SUSPENSE_WRITEOFF"));
        assertNotNull(AccountOperationType.valueOf("RISK_FREEZE"));
        assertNotNull(AccountOperationType.valueOf("RISK_UNFREEZE"));
        assertNotNull(AccountOperationType.valueOf("AUTO_WITHDRAW"));
        assertNotNull(AccountOperationType.valueOf("RESERVE_REPLENISH"));
    }

    // ==================== MerchantAccount alertFlag Tests ====================

    @Test
    @DisplayName("MerchantAccount: alertFlag 字段默认为 null")
    void merchantAccount_alertFlag_defaultsToNull() {
        MerchantAccount account = new MerchantAccount();
        assertNull(account.getAlertFlag());
    }

    @Test
    @DisplayName("MerchantAccount: alertFlag 可设置和读取")
    void merchantAccount_alertFlag_canBeSetAndRead() {
        MerchantAccount account = new MerchantAccount();
        account.setAlertFlag("WARNING");
        assertEquals("WARNING", account.getAlertFlag());

        account.setAlertFlag("CRITICAL");
        assertEquals("CRITICAL", account.getAlertFlag());

        account.setAlertFlag("EMERGENCY");
        assertEquals("EMERGENCY", account.getAlertFlag());
    }
}