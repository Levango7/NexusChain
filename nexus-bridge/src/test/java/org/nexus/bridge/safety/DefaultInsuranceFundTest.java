package org.nexus.bridge.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.bridge.model.InsuranceFundLedgerEntry;
import org.nexus.bridge.repository.InsuranceFundLedgerRepository;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultInsuranceFund} 单元测试：覆盖存入、补偿、提现、余额查询、流水恢复。
 */
@ExtendWith(MockitoExtension.class)
class DefaultInsuranceFundTest {

    @Mock
    private InsuranceFundLedgerRepository ledgerRepository;

    private DefaultInsuranceFund fund;

    @BeforeEach
    void setUp() {
        when(ledgerRepository.findAll()).thenReturn(Collections.emptyList());
        fund = new DefaultInsuranceFund(ledgerRepository);
    }

    @Test
    @DisplayName("构造时从 DB 重放流水恢复余额")
    void constructor_restoresBalanceFromDb() {
        InsuranceFundLedgerEntry dep1 = new InsuranceFundLedgerEntry(
                "DEPOSIT", new BigDecimal("1000"), new BigDecimal("1000"), null, "d1");
        InsuranceFundLedgerEntry comp1 = new InsuranceFundLedgerEntry(
                "COMPENSATE", new BigDecimal("300"), new BigDecimal("700"), "v1", "c1");
        InsuranceFundLedgerEntry dep2 = new InsuranceFundLedgerEntry(
                "DEPOSIT", new BigDecimal("500"), new BigDecimal("1200"), null, "d2");
        when(ledgerRepository.findAll()).thenReturn(Arrays.asList(dep1, comp1, dep2));

        DefaultInsuranceFund f = new DefaultInsuranceFund(ledgerRepository);

        assertEquals(0, new BigDecimal("1200").compareTo(f.getBalance()));
    }

    @Test
    @DisplayName("构造时 DB 异常应被捕获，余额为 0")
    void constructor_dbExceptionHandled() {
        when(ledgerRepository.findAll()).thenThrow(new RuntimeException("DB not ready"));
        DefaultInsuranceFund f = new DefaultInsuranceFund(ledgerRepository);
        assertEquals(0, BigDecimal.ZERO.compareTo(f.getBalance()));
    }

    @Test
    @DisplayName("deposit: 正金额存入应增加余额")
    void deposit_positiveAmountIncreasesBalance() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        fund.deposit(new BigDecimal("1000"));

        assertEquals(0, new BigDecimal("1000").compareTo(fund.getBalance()));
        verify(ledgerRepository).save(any());
    }

    @Test
    @DisplayName("deposit: null 金额应抛 NullPointerException")
    void deposit_nullThrows() {
        assertThrows(NullPointerException.class, () -> fund.deposit(null));
    }

    @Test
    @DisplayName("deposit: 零或负金额应抛 IllegalArgumentException")
    void deposit_nonPositiveThrows() {
        assertThrows(IllegalArgumentException.class, () -> fund.deposit(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> fund.deposit(new BigDecimal("-100")));
    }

    @Test
    @DisplayName("compensate: 余额充足时补偿成功")
    void compensate_sufficientBalance() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        fund.deposit(new BigDecimal("1000"));

        fund.compensate("victim-1", new BigDecimal("300"), "loss");

        assertEquals(0, new BigDecimal("700").compareTo(fund.getBalance()));
    }

    @Test
    @DisplayName("compensate: null victimId 应抛 NullPointerException")
    void compensate_nullVictimThrows() {
        assertThrows(NullPointerException.class,
                () -> fund.compensate(null, new BigDecimal("100"), "reason"));
    }

    @Test
    @DisplayName("compensate: null 金额应抛 NullPointerException")
    void compensate_nullAmountThrows() {
        assertThrows(NullPointerException.class,
                () -> fund.compensate("victim-1", null, "reason"));
    }

    @Test
    @DisplayName("compensate: 空 victimId 应抛 IllegalArgumentException")
    void compensate_emptyVictimThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> fund.compensate("", new BigDecimal("100"), "reason"));
    }

    @Test
    @DisplayName("compensate: 零或负金额应抛 IllegalArgumentException")
    void compensate_nonPositiveAmountThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> fund.compensate("victim-1", BigDecimal.ZERO, "reason"));
        assertThrows(IllegalArgumentException.class,
                () -> fund.compensate("victim-1", new BigDecimal("-1"), "reason"));
    }

    @Test
    @DisplayName("compensate: 余额不足应抛 IllegalStateException")
    void compensate_insufficientBalanceThrows() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        fund.deposit(new BigDecimal("100"));

        assertThrows(IllegalStateException.class,
                () -> fund.compensate("victim-1", new BigDecimal("200"), "reason"));
    }

    @Test
    @DisplayName("withdraw: 余额充足时提现成功")
    void withdraw_sufficientBalance() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        fund.deposit(new BigDecimal("1000"));

        fund.withdraw(new BigDecimal("400"), "approver-1", "governance");

        assertEquals(0, new BigDecimal("600").compareTo(fund.getBalance()));
    }

    @Test
    @DisplayName("withdraw: null 参数应抛 NullPointerException")
    void withdraw_nullThrows() {
        assertThrows(NullPointerException.class,
                () -> fund.withdraw(null, "approver", "reason"));
        assertThrows(NullPointerException.class,
                () -> fund.withdraw(new BigDecimal("100"), null, "reason"));
    }

    @Test
    @DisplayName("withdraw: 零或负金额应抛 IllegalArgumentException")
    void withdraw_nonPositiveThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> fund.withdraw(BigDecimal.ZERO, "approver", "reason"));
        assertThrows(IllegalArgumentException.class,
                () -> fund.withdraw(new BigDecimal("-1"), "approver", "reason"));
    }

    @Test
    @DisplayName("withdraw: 余额不足应抛 IllegalStateException")
    void withdraw_insufficientBalanceThrows() {
        when(ledgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        fund.deposit(new BigDecimal("100"));

        assertThrows(IllegalStateException.class,
                () -> fund.withdraw(new BigDecimal("200"), "approver", "reason"));
    }

    @Test
    @DisplayName("getLedgerEntries: 返回所有流水")
    void getLedgerEntries_returnsAll() {
        InsuranceFundLedgerEntry entry = new InsuranceFundLedgerEntry(
                "DEPOSIT", new BigDecimal("100"), new BigDecimal("100"), null, "r");
        when(ledgerRepository.findAll()).thenReturn(Arrays.asList(entry));

        List<InsuranceFundLedgerEntry> entries = fund.getLedgerEntries();
        assertEquals(1, entries.size());
    }

    @Test
    @DisplayName("getBalance: 初始余额为 0")
    void getBalance_initialZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(fund.getBalance()));
    }
}