package org.nexus.gateway.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccountService}.
 * Covers deposit/withdraw/freeze/unfreeze/transfer/getBalance and payment/refund linkage.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private MerchantAccountRepository accountRepository;
    @Mock private AccountTransactionRepository transactionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AccountService accountService;

    private MerchantAccount balanceAccount;
    private MerchantAccount frozenAccount;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, transactionRepository, eventPublisher);

        balanceAccount = new MerchantAccount();
        balanceAccount.setId(1L);
        balanceAccount.setAccountId("MA100BALANCE");
        balanceAccount.setMerchantId(100L);
        balanceAccount.setAccountType(AccountType.BALANCE);
        balanceAccount.setBalance(BigDecimal.ZERO);
        balanceAccount.setStatus(AccountStatus.ACTIVE);

        frozenAccount = new MerchantAccount();
        frozenAccount.setId(2L);
        frozenAccount.setAccountId("MA100FROZEN");
        frozenAccount.setMerchantId(100L);
        frozenAccount.setAccountType(AccountType.FROZEN);
        frozenAccount.setBalance(BigDecimal.ZERO);
        frozenAccount.setStatus(AccountStatus.ACTIVE);
    }

    // ==================== Exception Scenario Tests (Highest Priority) ====================

    @Test
    @DisplayName("deposit: null amount throws IllegalArgumentException")
    void deposit_nullAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.deposit(100L, null, "DEPOSIT"));
    }

    @Test
    @DisplayName("deposit: zero amount throws IllegalArgumentException")
    void deposit_zeroAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.deposit(100L, BigDecimal.ZERO, "DEPOSIT"));
    }

    @Test
    @DisplayName("deposit: negative amount throws IllegalArgumentException")
    void deposit_negativeAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.deposit(100L, new BigDecimal("-100"), "DEPOSIT"));
    }

    @Test
    @DisplayName("deposit: FROZEN account throws IllegalStateException")
    void deposit_frozenAccount_throwsIllegalStateException() {
        balanceAccount.setStatus(AccountStatus.FROZEN);
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        assertThrows(IllegalStateException.class,
                () -> accountService.deposit(100L, new BigDecimal("100"), "DEPOSIT"));
    }

    @Test
    @DisplayName("deposit: CLOSED account throws IllegalStateException")
    void deposit_closedAccount_throwsIllegalStateException() {
        balanceAccount.setStatus(AccountStatus.CLOSED);
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        assertThrows(IllegalStateException.class,
                () -> accountService.deposit(100L, new BigDecimal("100"), "DEPOSIT"));
    }

    @Test
    @DisplayName("withdraw: null amount throws IllegalArgumentException")
    void withdraw_nullAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.withdraw(100L, null));
    }

    @Test
    @DisplayName("withdraw: zero amount throws IllegalArgumentException")
    void withdraw_zeroAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.withdraw(100L, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("withdraw: insufficient balance throws IllegalStateException")
    void withdraw_insufficientBalance_throwsIllegalStateException() {
        balanceAccount.setBalance(new BigDecimal("50"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> accountService.withdraw(100L, new BigDecimal("100")));
        assertTrue(ex.getMessage().contains("余额不足"));
    }

    @Test
    @DisplayName("withdraw: FROZEN account throws IllegalStateException")
    void withdraw_frozenAccount_throwsIllegalStateException() {
        balanceAccount.setStatus(AccountStatus.FROZEN);
        balanceAccount.setBalance(new BigDecimal("1000"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        assertThrows(IllegalStateException.class,
                () -> accountService.withdraw(100L, new BigDecimal("100")));
    }

    @Test
    @DisplayName("freeze: null amount throws IllegalArgumentException")
    void freeze_nullAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.freeze(100L, null, "reason"));
    }

    @Test
    @DisplayName("freeze: insufficient balance throws IllegalStateException")
    void freeze_insufficientBalance_throwsIllegalStateException() {
        balanceAccount.setBalance(new BigDecimal("50"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> accountService.freeze(100L, new BigDecimal("100"), "test"));
        assertTrue(ex.getMessage().contains("余额不足"));
    }

    @Test
    @DisplayName("unfreeze: null amount throws IllegalArgumentException")
    void unfreeze_nullAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.unfreeze(100L, null));
    }

    @Test
    @DisplayName("unfreeze: insufficient frozen balance throws IllegalStateException")
    void unfreeze_insufficientFrozenBalance_throwsIllegalStateException() {
        frozenAccount.setBalance(new BigDecimal("50"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> accountService.unfreeze(100L, new BigDecimal("100")));
        assertTrue(ex.getMessage().contains("冻结余额不足"));
    }

    @Test
    @DisplayName("transfer: null amount throws IllegalArgumentException")
    void transfer_nullAmount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.transfer("MA100BALANCE", "MA200BALANCE", null));
    }

    @Test
    @DisplayName("transfer: same account throws IllegalArgumentException")
    void transfer_sameAccount_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.transfer("MA100BALANCE", "MA100BALANCE", new BigDecimal("100")));
    }

    @Test
    @DisplayName("transfer: from account not found throws IllegalArgumentException")
    void transfer_fromAccountNotFound_throwsIllegalArgumentException() {
        when(accountRepository.findByAccountId("MA999"))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accountService.transfer("MA999", "MA100BALANCE", new BigDecimal("100")));
        assertTrue(ex.getMessage().contains("转出账户不存在"));
    }

    @Test
    @DisplayName("transfer: to account not found throws IllegalArgumentException")
    void transfer_toAccountNotFound_throwsIllegalArgumentException() {
        when(accountRepository.findByAccountId("MA100BALANCE"))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.findByAccountId("MA999"))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accountService.transfer("MA100BALANCE", "MA999", new BigDecimal("100")));
        assertTrue(ex.getMessage().contains("转入账户不存在"));
    }

    @Test
    @DisplayName("transfer: insufficient balance throws IllegalStateException")
    void transfer_insufficientBalance_throwsIllegalStateException() {
        MerchantAccount toAccount = new MerchantAccount();
        toAccount.setAccountId("MA200BALANCE");
        toAccount.setMerchantId(200L);
        toAccount.setAccountType(AccountType.BALANCE);
        toAccount.setBalance(BigDecimal.ZERO);
        toAccount.setStatus(AccountStatus.ACTIVE);

        balanceAccount.setBalance(new BigDecimal("50"));
        when(accountRepository.findByAccountId("MA100BALANCE"))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.findByAccountId("MA200BALANCE"))
                .thenReturn(Optional.of(toAccount));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> accountService.transfer("MA100BALANCE", "MA200BALANCE", new BigDecimal("100")));
        assertTrue(ex.getMessage().contains("余额不足"));
    }

    // ==================== Normal Business Flow Tests ====================

    @Test
    @DisplayName("getOrCreateAccount: existing account returned without creation")
    void getOrCreateAccount_existingAccount_returnedWithoutCreation() {
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        MerchantAccount result = accountService.getOrCreateAccount(100L, AccountType.BALANCE);

        assertNotNull(result);
        assertEquals("MA100BALANCE", result.getAccountId());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("getOrCreateAccount: new account created when not found")
    void getOrCreateAccount_newAccount_created() {
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.empty());
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.getOrCreateAccount(100L, AccountType.BALANCE);

        assertNotNull(result);
        assertEquals("MA100BALANCE", result.getAccountId());
        assertEquals(AccountType.BALANCE, result.getAccountType());
        assertEquals(BigDecimal.ZERO, result.getBalance());
        assertEquals(AccountStatus.ACTIVE, result.getStatus());
        verify(accountRepository).save(any());
    }

    @Test
    @DisplayName("deposit: normal deposit increases balance and records transaction")
    void deposit_normalCase_increasesBalance() {
        balanceAccount.setBalance(new BigDecimal("100"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.deposit(100L, new BigDecimal("50"), "ORDER-001");

        assertNotNull(result);
        assertEquals(new BigDecimal("150"), result.getBalance());
        verify(transactionRepository).save(any(AccountTransaction.class));
        verify(eventPublisher).publishEvent(any(AccountBalanceChangedEvent.class));
    }

    @Test
    @DisplayName("deposit: null relatedOrderId uses DEPOSIT as reference")
    void deposit_nullOrderId_usesDefaultReference() {
        balanceAccount.setBalance(new BigDecimal("100"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.deposit(100L, new BigDecimal("50"), null);

        assertEquals(new BigDecimal("150"), result.getBalance());
        ArgumentCaptor<AccountTransaction> txCaptor = ArgumentCaptor.forClass(AccountTransaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertEquals("DEPOSIT", txCaptor.getValue().getReference());
    }

    @Test
    @DisplayName("withdraw: normal withdraw decreases balance")
    void withdraw_normalCase_decreasesBalance() {
        balanceAccount.setBalance(new BigDecimal("100"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.withdraw(100L, new BigDecimal("50"));

        assertNotNull(result);
        assertEquals(new BigDecimal("50"), result.getBalance());
        verify(transactionRepository).save(any(AccountTransaction.class));
        verify(eventPublisher).publishEvent(any(AccountBalanceChangedEvent.class));
    }

    @Test
    @DisplayName("freeze: normal freeze moves amount from BALANCE to FROZEN")
    void freeze_normalCase_movesAmountToFrozen() {
        balanceAccount.setBalance(new BigDecimal("100"));
        frozenAccount.setBalance(new BigDecimal("0"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.freeze(100L, new BigDecimal("50"), "TEST_FREEZE");

        assertNotNull(result);
        assertEquals(new BigDecimal("50"), result.getBalance());
        assertEquals(new BigDecimal("50"), frozenAccount.getBalance());
        // Two transactions: DEBIT from BALANCE, CREDIT to FROZEN
        verify(transactionRepository, times(2)).save(any(AccountTransaction.class));
        verify(eventPublisher).publishEvent(any(AccountBalanceChangedEvent.class));
    }

    @Test
    @DisplayName("unfreeze: normal unfreeze moves amount from FROZEN to BALANCE")
    void unfreeze_normalCase_movesAmountToBalance() {
        frozenAccount.setBalance(new BigDecimal("50"));
        balanceAccount.setBalance(new BigDecimal("100"));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.unfreeze(100L, new BigDecimal("50"));

        assertNotNull(result);
        assertEquals(new BigDecimal("150"), result.getBalance());
        assertEquals(new BigDecimal("0"), frozenAccount.getBalance());
        verify(transactionRepository, times(2)).save(any(AccountTransaction.class));
        verify(eventPublisher).publishEvent(any(AccountBalanceChangedEvent.class));
    }

    @Test
    @DisplayName("transfer: normal transfer moves amount between accounts")
    void transfer_normalCase_movesAmount() {
        MerchantAccount toAccount = new MerchantAccount();
        toAccount.setId(3L);
        toAccount.setAccountId("MA200BALANCE");
        toAccount.setMerchantId(200L);
        toAccount.setAccountType(AccountType.BALANCE);
        toAccount.setBalance(new BigDecimal("0"));
        toAccount.setStatus(AccountStatus.ACTIVE);

        balanceAccount.setBalance(new BigDecimal("100"));
        when(accountRepository.findByAccountId("MA100BALANCE"))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.findByAccountId("MA200BALANCE"))
                .thenReturn(Optional.of(toAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.transfer("MA100BALANCE", "MA200BALANCE", new BigDecimal("50"));

        assertNotNull(result);
        assertEquals(new BigDecimal("50"), result.getBalance());
        assertEquals(new BigDecimal("50"), toAccount.getBalance());
        verify(transactionRepository, times(2)).save(any(AccountTransaction.class));
        verify(eventPublisher).publishEvent(any(AccountBalanceChangedEvent.class));
    }

    @Test
    @DisplayName("getBalance: returns balance/frozen/reserve for existing accounts")
    void getBalance_existingAccounts_returnsAllBalances() {
        MerchantAccount reserveAccount = new MerchantAccount();
        reserveAccount.setId(3L);
        reserveAccount.setAccountId("MA100RESERVE");
        reserveAccount.setMerchantId(100L);
        reserveAccount.setAccountType(AccountType.RESERVE);
        reserveAccount.setBalance(new BigDecimal("200"));
        reserveAccount.setStatus(AccountStatus.ACTIVE);

        balanceAccount.setBalance(new BigDecimal("100"));
        frozenAccount.setBalance(new BigDecimal("50"));

        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.RESERVE))
                .thenReturn(Optional.of(reserveAccount));

        Map<String, Object> result = accountService.getBalance(100L);

        assertEquals(100L, result.get("merchantId"));
        assertEquals(new BigDecimal("100"), result.get("balance"));
        assertEquals(new BigDecimal("50"), result.get("frozen"));
        assertEquals(new BigDecimal("200"), result.get("reserve"));
        assertEquals("ACTIVE", result.get("balanceStatus"));
    }

    @Test
    @DisplayName("getBalance: non-existent accounts return zero and NOT_CREATED status")
    void getBalance_nonExistentAccounts_returnsZero() {
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.empty());
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.FROZEN))
                .thenReturn(Optional.empty());
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.RESERVE))
                .thenReturn(Optional.empty());

        Map<String, Object> result = accountService.getBalance(100L);

        assertEquals(BigDecimal.ZERO, result.get("balance"));
        assertEquals(BigDecimal.ZERO, result.get("frozen"));
        assertEquals(BigDecimal.ZERO, result.get("reserve"));
        assertEquals("NOT_CREATED", result.get("balanceStatus"));
    }

    // ==================== Payment/Refund Idempotency Tests ====================

    @Test
    @DisplayName("creditOnPayment: normal credit increases balance")
    void creditOnPayment_normalCase_increasesBalance() {
        balanceAccount.setBalance(new BigDecimal("100"));
        when(transactionRepository.findByReference("ORDER-001"))
                .thenReturn(Collections.emptyList());
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.creditOnPayment(100L, new BigDecimal("50"), "ORDER-001");

        assertNotNull(result);
        assertEquals(new BigDecimal("150"), result.getBalance());
        verify(transactionRepository).save(any(AccountTransaction.class));
    }

    @Test
    @DisplayName("creditOnPayment: duplicate orderNo skips (idempotency)")
    void creditOnPayment_duplicateOrderNo_skips() {
        AccountTransaction existingTx = new AccountTransaction();
        existingTx.setReference("ORDER-001");
        when(transactionRepository.findByReference("ORDER-001"))
                .thenReturn(List.of(existingTx));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        MerchantAccount result = accountService.creditOnPayment(100L, new BigDecimal("50"), "ORDER-001");

        // Idempotency: returns existing account without modification
        assertNotNull(result);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("debitOnRefund: normal debit decreases balance")
    void debitOnRefund_normalCase_decreasesBalance() {
        balanceAccount.setBalance(new BigDecimal("100"));
        when(transactionRepository.findByReference("REFUND-001"))
                .thenReturn(Collections.emptyList());
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.debitOnRefund(100L, new BigDecimal("50"), "REFUND-001");

        assertNotNull(result);
        assertEquals(new BigDecimal("50"), result.getBalance());
        verify(transactionRepository).save(any(AccountTransaction.class));
    }

    @Test
    @DisplayName("debitOnRefund: duplicate refundNo skips (idempotency)")
    void debitOnRefund_duplicateRefundNo_skips() {
        AccountTransaction existingTx = new AccountTransaction();
        existingTx.setReference("REFUND-001");
        when(transactionRepository.findByReference("REFUND-001"))
                .thenReturn(List.of(existingTx));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        MerchantAccount result = accountService.debitOnRefund(100L, new BigDecimal("50"), "REFUND-001");

        assertNotNull(result);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("debitOnRefund: allows negative balance (refund exceeds balance)")
    void debitOnRefund_exceedsBalance_allowsNegative() {
        balanceAccount.setBalance(new BigDecimal("30"));
        when(transactionRepository.findByReference("REFUND-002"))
                .thenReturn(Collections.emptyList());
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MerchantAccount result = accountService.debitOnRefund(100L, new BigDecimal("50"), "REFUND-002");

        assertNotNull(result);
        assertEquals(new BigDecimal("-20"), result.getBalance());
        verify(transactionRepository).save(any(AccountTransaction.class));
    }
}