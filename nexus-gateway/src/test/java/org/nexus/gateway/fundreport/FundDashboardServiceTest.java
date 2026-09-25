package org.nexus.gateway.fundreport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FundDashboardService}.
 * Covers fund overview, transaction trend, recent transactions, anomaly monitor, cache, and zero-data scenarios.
 */
@ExtendWith(MockitoExtension.class)
class FundDashboardServiceTest {

    @Mock private MerchantAccountRepository accountRepository;
    @Mock private AccountTransactionRepository transactionRepository;

    @InjectMocks private FundDashboardService fundDashboardService;

    private MerchantAccount balanceAccount;
    private MerchantAccount frozenAccount;
    private MerchantAccount reserveAccount;

    @BeforeEach
    void setUp() {
        balanceAccount = new MerchantAccount();
        balanceAccount.setId(1L);
        balanceAccount.setAccountId("MA100BALANCE");
        balanceAccount.setMerchantId(100L);
        balanceAccount.setAccountType(AccountType.BALANCE);
        balanceAccount.setBalance(new BigDecimal("1000"));
        balanceAccount.setStatus(AccountStatus.ACTIVE);
        balanceAccount.setAlertFlag("WARNING");

        frozenAccount = new MerchantAccount();
        frozenAccount.setId(2L);
        frozenAccount.setAccountId("MA100FROZEN");
        frozenAccount.setMerchantId(100L);
        frozenAccount.setAccountType(AccountType.FROZEN);
        frozenAccount.setBalance(new BigDecimal("200"));
        frozenAccount.setStatus(AccountStatus.ACTIVE);

        reserveAccount = new MerchantAccount();
        reserveAccount.setId(3L);
        reserveAccount.setAccountId("MA100RESERVE");
        reserveAccount.setMerchantId(100L);
        reserveAccount.setAccountType(AccountType.RESERVE);
        reserveAccount.setBalance(new BigDecimal("500"));
        reserveAccount.setStatus(AccountStatus.ACTIVE);
    }

    // ==================== 1. 资金概览 ====================

    @Test
    @DisplayName("getFundOverview: returns balance/frozen/reserve/alertFlag correctly")
    void getFundOverview_returnsAllFields() {
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.RESERVE))
                .thenReturn(Optional.of(reserveAccount));

        Map<String, Object> overview = fundDashboardService.getFundOverview(100L);

        assertEquals(new BigDecimal("1000"), overview.get("balance"));
        assertEquals(new BigDecimal("200"), overview.get("frozen"));
        assertEquals(new BigDecimal("500"), overview.get("reserve"));
        assertEquals(new BigDecimal("1700"), overview.get("totalAssets"));
        assertEquals("ACTIVE", overview.get("balanceStatus"));
        assertEquals("WARNING", overview.get("alertFlag"));
    }

    // ==================== 2. 流水趋势 — 7 日 dailySummary ====================

    @Test
    @DisplayName("getTransactionTrend: returns 7-day daily summary with correct credit/debit aggregation")
    void getTransactionTrend_returns7DayDailySummary() {
        LocalDate today = LocalDate.now();

        AccountTransaction creditTx = new AccountTransaction();
        creditTx.setTxNo("AT001");
        creditTx.setDirection(TransactionDirection.CREDIT);
        creditTx.setAmount(new BigDecimal("100"));
        creditTx.setCreatedAt(today.atTime(10, 0));

        AccountTransaction debitTx = new AccountTransaction();
        debitTx.setTxNo("AT002");
        debitTx.setDirection(TransactionDirection.DEBIT);
        debitTx.setAmount(new BigDecimal("50"));
        debitTx.setCreatedAt(today.atTime(14, 0));

        when(transactionRepository.findByMerchantIdAndCreatedAtBetween(eq(100L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(creditTx, debitTx));

        List<Map<String, Object>> trend = fundDashboardService.getTransactionTrend(100L, 7);

        assertEquals(7, trend.size());

        // 最后一天是今天，包含两条交易
        Map<String, Object> todayData = trend.get(6);
        assertEquals(today.toString(), todayData.get("date"));
        assertEquals(new BigDecimal("100"), todayData.get("totalCredit"));
        assertEquals(new BigDecimal("50"), todayData.get("totalDebit"));
        assertEquals(new BigDecimal("50"), todayData.get("netFlow"));
        assertEquals(1, todayData.get("creditCount"));
        assertEquals(1, todayData.get("debitCount"));

        // 其他天应该为零值
        Map<String, Object> firstDay = trend.get(0);
        assertEquals(new BigDecimal("0"), firstDay.get("totalCredit"));
        assertEquals(new BigDecimal("0"), firstDay.get("totalDebit"));
        assertEquals(new BigDecimal("0"), firstDay.get("netFlow"));
        assertEquals(0, firstDay.get("creditCount"));
        assertEquals(0, firstDay.get("debitCount"));
    }

    // ==================== 3. 收支明细 — 近期交易列表 ====================

    @Test
    @DisplayName("getRecentTransactions: returns recent transaction list with correct fields")
    void getRecentTransactions_returnsTransactionList() {
        AccountTransaction tx1 = new AccountTransaction();
        tx1.setTxNo("AT001");
        tx1.setOperationType(AccountOperationType.DEPOSIT);
        tx1.setDirection(TransactionDirection.CREDIT);
        tx1.setAmount(new BigDecimal("100"));
        tx1.setBalanceBefore(new BigDecimal("0"));
        tx1.setBalanceAfter(new BigDecimal("100"));
        tx1.setReference("ORDER-001");
        tx1.setCreatedAt(LocalDateTime.now());

        AccountTransaction tx2 = new AccountTransaction();
        tx2.setTxNo("AT002");
        tx2.setOperationType(AccountOperationType.WITHDRAW);
        tx2.setDirection(TransactionDirection.DEBIT);
        tx2.setAmount(new BigDecimal("50"));
        tx2.setBalanceBefore(new BigDecimal("100"));
        tx2.setBalanceAfter(new BigDecimal("50"));
        tx2.setReference("WD-001");
        tx2.setCreatedAt(LocalDateTime.now());

        when(transactionRepository.findByAccountIdOrderByCreatedAtDesc("MA100BALANCE"))
                .thenReturn(List.of(tx1, tx2));

        List<Map<String, Object>> result = fundDashboardService.getRecentTransactions(100L);

        assertEquals(2, result.size());

        Map<String, Object> first = result.get(0);
        assertEquals("AT001", first.get("txNo"));
        assertEquals("DEPOSIT", first.get("operationType"));
        assertEquals("CREDIT", first.get("direction"));
        assertEquals(new BigDecimal("100"), first.get("amount"));
        assertEquals(new BigDecimal("0"), first.get("balanceBefore"));
        assertEquals(new BigDecimal("100"), first.get("balanceAfter"));
        assertEquals("ORDER-001", first.get("reference"));

        Map<String, Object> second = result.get(1);
        assertEquals("AT002", second.get("txNo"));
        assertEquals("WITHDRAW", second.get("operationType"));
        assertEquals("DEBIT", second.get("direction"));
    }

    // ==================== 4. 异常监控 — 预警事件 + 风控操作 ====================

    @Test
    @DisplayName("getAnomalyMonitor: detects alertFlag, negative balance, and risk operations")
    void getAnomalyMonitor_detectsAlertsAndRiskEvents() {
        // 账户有预警标志
        balanceAccount.setAlertFlag("CRITICAL");

        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));

        // 近期包含风控冻结操作和退款导致负余额的异常流水
        AccountTransaction riskFreezeTx = new AccountTransaction();
        riskFreezeTx.setTxNo("AT-RF-001");
        riskFreezeTx.setOperationType(AccountOperationType.RISK_FREEZE);
        riskFreezeTx.setDirection(TransactionDirection.DEBIT);
        riskFreezeTx.setAmount(new BigDecimal("300"));
        riskFreezeTx.setBalanceBefore(new BigDecimal("1000"));
        riskFreezeTx.setBalanceAfter(new BigDecimal("700"));
        riskFreezeTx.setCreatedAt(LocalDateTime.now());

        AccountTransaction refundTx = new AccountTransaction();
        refundTx.setTxNo("AT-RF-002");
        refundTx.setOperationType(AccountOperationType.REFUND);
        refundTx.setDirection(TransactionDirection.DEBIT);
        refundTx.setAmount(new BigDecimal("1200"));
        refundTx.setBalanceBefore(new BigDecimal("200"));
        refundTx.setBalanceAfter(new BigDecimal("-1000"));
        refundTx.setCreatedAt(LocalDateTime.now());

        when(transactionRepository.findByMerchantIdAndCreatedAtBetween(eq(100L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(riskFreezeTx, refundTx));

        Map<String, Object> monitor = fundDashboardService.getAnomalyMonitor(100L);

        assertTrue((Boolean) monitor.get("hasAlert"));
        assertEquals("CRITICAL", monitor.get("alertLevel"));
        assertEquals(2, monitor.get("anomalyCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) monitor.get("anomalies");
        assertEquals(2, anomalies.size());

        // 验证风控冻结异常记录
        Map<String, Object> firstAnomaly = anomalies.get(0);
        assertEquals("AT-RF-001", firstAnomaly.get("txNo"));
        assertEquals("RISK_FREEZE", firstAnomaly.get("operationType"));

        // 验证退款负余额异常记录
        Map<String, Object> secondAnomaly = anomalies.get(1);
        assertEquals("AT-RF-002", secondAnomaly.get("txNo"));
        assertEquals("REFUND", secondAnomaly.get("operationType"));
        assertEquals(new BigDecimal("-1000"), secondAnomaly.get("balanceAfter"));
    }

    // ==================== 5. 缓存机制 — 30 秒缓存命中 ====================

    @Test
    @DisplayName("getDashboard: second call within 30s hits cache without repository access")
    void getDashboard_secondCallWithinCacheTTL_hitsCache() {
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.BALANCE))
                .thenReturn(Optional.of(balanceAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));
        when(accountRepository.findByMerchantIdAndAccountType(100L, AccountType.RESERVE))
                .thenReturn(Optional.of(reserveAccount));
        when(transactionRepository.findByMerchantIdAndCreatedAtBetween(eq(100L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());
        when(transactionRepository.findByAccountIdOrderByCreatedAtDesc("MA100BALANCE"))
                .thenReturn(Collections.emptyList());
        when(transactionRepository.findByMerchantIdOrderByCreatedAtDesc(eq(100L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        // 第一次调用 — 构建数据并写入缓存
        Map<String, Object> firstResult = fundDashboardService.getDashboard(100L);
        assertNotNull(firstResult);
        assertEquals(100L, firstResult.get("merchantId"));

        // 第二次调用 — 应命中缓存，不再访问 repository
        Map<String, Object> secondResult = fundDashboardService.getDashboard(100L);

        // 返回同一缓存对象
        assertSame(firstResult, secondResult);

        // repository 方法调用次数：第一次构建时 getFundOverview + getAnomalyMonitor 各调一次 BALANCE
        // 第二次走缓存不再调用，所以 BALANCE=2, FROZEN=1, RESERVE=1
        verify(accountRepository, times(2)).findByMerchantIdAndAccountType(100L, AccountType.BALANCE);
        verify(accountRepository, times(1)).findByMerchantIdAndAccountType(100L, AccountType.FROZEN);
        verify(accountRepository, times(1)).findByMerchantIdAndAccountType(100L, AccountType.RESERVE);
    }

    // ==================== 6. 无交易记录零值返回 — 不抛异常 ====================

    @Test
    @DisplayName("getDashboard: no transactions or accounts returns zero values without exception")
    void getDashboard_noData_returnsZeroValuesWithoutException() {
        when(accountRepository.findByMerchantIdAndAccountType(999L, AccountType.BALANCE))
                .thenReturn(Optional.empty());
        when(accountRepository.findByMerchantIdAndAccountType(999L, AccountType.FROZEN))
                .thenReturn(Optional.empty());
        when(accountRepository.findByMerchantIdAndAccountType(999L, AccountType.RESERVE))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByMerchantIdAndCreatedAtBetween(eq(999L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());
        when(transactionRepository.findByAccountIdOrderByCreatedAtDesc("MA999BALANCE"))
                .thenReturn(Collections.emptyList());
        when(transactionRepository.findByMerchantIdOrderByCreatedAtDesc(eq(999L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        // 不抛异常
        Map<String, Object> dashboard = assertDoesNotThrow(() -> fundDashboardService.getDashboard(999L));

        assertNotNull(dashboard);
        assertEquals(999L, dashboard.get("merchantId"));

        // 资金概览全部为零值
        @SuppressWarnings("unchecked")
        Map<String, Object> fundOverview = (Map<String, Object>) dashboard.get("fundOverview");
        assertEquals(BigDecimal.ZERO, fundOverview.get("balance"));
        assertEquals(BigDecimal.ZERO, fundOverview.get("frozen"));
        assertEquals(BigDecimal.ZERO, fundOverview.get("reserve"));
        assertEquals(BigDecimal.ZERO, fundOverview.get("totalAssets"));
        assertEquals("NOT_CREATED", fundOverview.get("balanceStatus"));
        assertNull(fundOverview.get("alertFlag"));

        // 流水趋势返回 7 天零值
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) dashboard.get("transactionTrend");
        assertEquals(7, trend.size());
        for (Map<String, Object> dayData : trend) {
            assertEquals(new BigDecimal("0"), dayData.get("totalCredit"));
            assertEquals(new BigDecimal("0"), dayData.get("totalDebit"));
            assertEquals(new BigDecimal("0"), dayData.get("netFlow"));
            assertEquals(0, dayData.get("creditCount"));
            assertEquals(0, dayData.get("debitCount"));
        }

        // 收支明细为空列表
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentTx = (List<Map<String, Object>>) dashboard.get("recentTransactions");
        assertTrue(recentTx.isEmpty());

        // 异常监控无预警
        @SuppressWarnings("unchecked")
        Map<String, Object> anomalyMonitor = (Map<String, Object>) dashboard.get("anomalyMonitor");
        assertFalse((Boolean) anomalyMonitor.get("hasAlert"));
        assertNull(anomalyMonitor.get("alertLevel"));
        assertEquals(0, anomalyMonitor.get("anomalyCount"));
    }
}