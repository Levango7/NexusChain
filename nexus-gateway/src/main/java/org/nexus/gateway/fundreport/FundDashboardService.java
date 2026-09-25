package org.nexus.gateway.fundreport;

import org.nexus.gateway.account.AccountTransaction;
import org.nexus.gateway.account.AccountTransactionRepository;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.TransactionDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 资金管理仪表盘服务 — 商户资金概览与监控。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #getDashboard} — 获取完整仪表盘数据（资金概览/流水趋势/收支明细/异常监控）</li>
 *   <li>{@link #getTransactionTrend} — 近 N 日流水汇总趋势</li>
 *   <li>30 秒缓存机制 — 同一商户的仪表盘数据 30 秒内复用缓存</li>
 * </ul>
 */
@Service
public class FundDashboardService {

    private static final Logger log = LoggerFactory.getLogger(FundDashboardService.class);

    /** 缓存有效期：30 秒 */
    private static final long CACHE_TTL_MS = 30_000L;

    /** 近期收支明细最大返回条数 */
    private static final int RECENT_DETAIL_LIMIT = 20;

    /** 默认趋势天数 */
    private static final int DEFAULT_TREND_DAYS = 7;

    private final MerchantAccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;

    /** 仪表盘缓存：merchantId → (缓存数据, 过期时间) */
    private final Map<Long, CacheEntry> dashboardCache = new HashMap<>();

    public FundDashboardService(MerchantAccountRepository accountRepository,
                                 AccountTransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    // === 仪表盘主入口 ===

    /**
     * 获取资金管理仪表盘完整数据。
     *
     * <p>包含四个模块：</p>
     * <ul>
     *   <li>资金概览 — 可用余额/冻结金额/备付金/账户状态</li>
     *   <li>流水趋势 — 近 7 日每日收支汇总</li>
     *   <li>收支明细 — 近 20 条流水记录</li>
     *   <li>异常监控 — 预警标志/负余额/异常操作</li>
     * </ul>
     *
     * <p>30 秒缓存：同一商户 30 秒内的重复请求直接返回缓存数据。</p>
     *
     * @param merchantId 商户 ID
     * @return 仪表盘数据
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getDashboard(Long merchantId) {
        // 检查缓存
        CacheEntry cached = dashboardCache.get(merchantId);
        if (cached != null && !cached.isExpired()) {
            log.debug("命中仪表盘缓存: merchantId={}", merchantId);
            return cached.data;
        }

        // 构建仪表盘数据
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("merchantId", merchantId);
        dashboard.put("fundOverview", getFundOverview(merchantId));
        dashboard.put("transactionTrend", getTransactionTrend(merchantId, DEFAULT_TREND_DAYS));
        dashboard.put("recentTransactions", getRecentTransactions(merchantId));
        dashboard.put("anomalyMonitor", getAnomalyMonitor(merchantId));
        dashboard.put("generatedAt", LocalDateTime.now());

        // 写入缓存
        dashboardCache.put(merchantId, new CacheEntry(dashboard));
        log.debug("仪表盘数据已缓存: merchantId={}", merchantId);

        return dashboard;
    }

    // === 资金概览 ===

    /**
     * 获取资金概览 — 可用余额、冻结金额、备付金、账户状态。
     */
    public Map<String, Object> getFundOverview(Long merchantId) {
        Map<String, Object> overview = new LinkedHashMap<>();

        Optional<MerchantAccount> balanceAccount =
                accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.BALANCE);
        Optional<MerchantAccount> frozenAccount =
                accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.FROZEN);
        Optional<MerchantAccount> reserveAccount =
                accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.RESERVE);

        overview.put("balance", balanceAccount.map(MerchantAccount::getBalance).orElse(BigDecimal.ZERO));
        overview.put("frozen", frozenAccount.map(MerchantAccount::getBalance).orElse(BigDecimal.ZERO));
        overview.put("reserve", reserveAccount.map(MerchantAccount::getBalance).orElse(BigDecimal.ZERO));
        overview.put("totalAssets",
                balanceAccount.map(MerchantAccount::getBalance).orElse(BigDecimal.ZERO)
                        .add(frozenAccount.map(MerchantAccount::getBalance).orElse(BigDecimal.ZERO))
                        .add(reserveAccount.map(MerchantAccount::getBalance).orElse(BigDecimal.ZERO)));
        overview.put("balanceStatus",
                balanceAccount.map(a -> a.getStatus().name()).orElse("NOT_CREATED"));
        overview.put("alertFlag", balanceAccount.map(MerchantAccount::getAlertFlag).orElse(null));

        return overview;
    }

    // === 流水趋势 ===

    /**
     * 获取近 N 日流水汇总趋势 — 每日收入/支出/净额。
     *
     * @param merchantId 商户 ID
     * @param days 统计天数（默认 7 天）
     * @return 每日流水趋势列表
     */
    public List<Map<String, Object>> getTransactionTrend(Long merchantId, int days) {
        if (days <= 0) {
            days = DEFAULT_TREND_DAYS;
        }

        LocalDate today = LocalDate.now();
        LocalDateTime start = today.minusDays(days - 1).atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay().minusSeconds(1);

        List<AccountTransaction> transactions =
                transactionRepository.findByMerchantIdAndCreatedAtBetween(merchantId, start, end);

        // 按日期分组汇总
        Map<LocalDate, DailySummary> dailyMap = new TreeMap<>();

        // 初始化每一天
        for (int i = 0; i < days; i++) {
            LocalDate date = today.minusDays(i);
            dailyMap.put(date, new DailySummary(date));
        }

        // 汇总流水
        for (AccountTransaction tx : transactions) {
            if (tx.getCreatedAt() == null) {
                continue;
            }
            LocalDate txDate = tx.getCreatedAt().toLocalDate();
            DailySummary summary = dailyMap.get(txDate);
            if (summary == null) {
                continue;
            }
            if (tx.getDirection() == TransactionDirection.CREDIT) {
                summary.totalCredit = summary.totalCredit.add(tx.getAmount());
                summary.creditCount++;
            } else {
                summary.totalDebit = summary.totalDebit.add(tx.getAmount());
                summary.debitCount++;
            }
        }

        // 转换为结果列表
        List<Map<String, Object>> trendList = new ArrayList<>();
        for (DailySummary summary : dailyMap.values()) {
            Map<String, Object> dayData = new LinkedHashMap<>();
            dayData.put("date", summary.date.toString());
            dayData.put("totalCredit", summary.totalCredit);
            dayData.put("totalDebit", summary.totalDebit);
            dayData.put("netFlow", summary.totalCredit.subtract(summary.totalDebit));
            dayData.put("creditCount", summary.creditCount);
            dayData.put("debitCount", summary.debitCount);
            trendList.add(dayData);
        }

        return trendList;
    }

    // === 收支明细 ===

    /**
     * 获取近期收支明细 — 最近 20 条流水记录。
     */
    public List<Map<String, Object>> getRecentTransactions(Long merchantId) {
        List<AccountTransaction> transactions =
                transactionRepository.findByAccountIdOrderByCreatedAtDesc(
                        "MA" + merchantId + AccountType.BALANCE.name());

        // 如果通过 accountId 查不到，则通过 merchantId 查
        if (transactions.isEmpty()) {
            transactions = transactionRepository
                    .findByMerchantIdOrderByCreatedAtDesc(merchantId,
                            org.springframework.data.domain.PageRequest.of(0, RECENT_DETAIL_LIMIT))
                    .getContent();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        int limit = Math.min(transactions.size(), RECENT_DETAIL_LIMIT);
        for (int i = 0; i < limit; i++) {
            AccountTransaction tx = transactions.get(i);
            Map<String, Object> txMap = new LinkedHashMap<>();
            txMap.put("txNo", tx.getTxNo());
            txMap.put("operationType", tx.getOperationType().name());
            txMap.put("direction", tx.getDirection().name());
            txMap.put("amount", tx.getAmount());
            txMap.put("balanceBefore", tx.getBalanceBefore());
            txMap.put("balanceAfter", tx.getBalanceAfter());
            txMap.put("reference", tx.getReference());
            txMap.put("createdAt", tx.getCreatedAt());
            result.add(txMap);
        }

        return result;
    }

    // === 异常监控 ===

    /**
     * 获取异常监控数据 — 预警标志、负余额、异常操作。
     */
    public Map<String, Object> getAnomalyMonitor(Long merchantId) {
        Map<String, Object> monitor = new LinkedHashMap<>();

        // 检查账户预警标志
        Optional<MerchantAccount> balanceAccount =
                accountRepository.findByMerchantIdAndAccountType(merchantId, AccountType.BALANCE);

        boolean hasAlert = false;
        String alertLevel = null;
        if (balanceAccount.isPresent()) {
            MerchantAccount account = balanceAccount.get();
            if (account.getAlertFlag() != null && !account.getAlertFlag().isEmpty()) {
                hasAlert = true;
                alertLevel = account.getAlertFlag();
            }
            // 检查负余额
            if (account.getBalance().compareTo(BigDecimal.ZERO) < 0) {
                hasAlert = true;
                alertLevel = "NEGATIVE_BALANCE";
            }
        }

        monitor.put("hasAlert", hasAlert);
        monitor.put("alertLevel", alertLevel);

        // 检查近期异常操作（退款导致的负余额、风控冻结等）
        LocalDateTime recentStart = LocalDateTime.now().minusDays(7);
        List<AccountTransaction> recentTransactions =
                transactionRepository.findByMerchantIdAndCreatedAtBetween(
                        merchantId, recentStart, LocalDateTime.now());

        int anomalyCount = 0;
        List<Map<String, Object>> anomalies = new ArrayList<>();

        for (AccountTransaction tx : recentTransactions) {
            boolean isAnomaly = false;
            // 退款操作且余额变负
            if (tx.getOperationType() == org.nexus.gateway.account.AccountOperationType.REFUND
                    && tx.getBalanceAfter().compareTo(BigDecimal.ZERO) < 0) {
                isAnomaly = true;
            }
            // 风控冻结/解冻
            if (tx.getOperationType() == org.nexus.gateway.account.AccountOperationType.RISK_FREEZE
                    || tx.getOperationType() == org.nexus.gateway.account.AccountOperationType.RISK_UNFREEZE) {
                isAnomaly = true;
            }

            if (isAnomaly) {
                anomalyCount++;
                Map<String, Object> anomaly = new LinkedHashMap<>();
                anomaly.put("txNo", tx.getTxNo());
                anomaly.put("operationType", tx.getOperationType().name());
                anomaly.put("amount", tx.getAmount());
                anomaly.put("balanceAfter", tx.getBalanceAfter());
                anomaly.put("createdAt", tx.getCreatedAt());
                anomalies.add(anomaly);
            }
        }

        monitor.put("anomalyCount", anomalyCount);
        monitor.put("anomalies", anomalies);

        return monitor;
    }

    // === 清除缓存 ===

    /**
     * 清除指定商户的仪表盘缓存。
     *
     * @param merchantId 商户 ID
     */
    public void evictCache(Long merchantId) {
        dashboardCache.remove(merchantId);
    }

    /**
     * 清除所有仪表盘缓存。
     */
    public void evictAllCache() {
        dashboardCache.clear();
    }

    // === 内部类 ===

    /**
     * 缓存条目 — 存储仪表盘数据和过期时间。
     */
    private static class CacheEntry {
        final Map<String, Object> data;
        final long expireAt;

        CacheEntry(Map<String, Object> data) {
            this.data = data;
            this.expireAt = System.currentTimeMillis() + CACHE_TTL_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /**
     * 每日流水汇总 — 内部辅助类。
     */
    private static class DailySummary {
        final LocalDate date;
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal totalDebit = BigDecimal.ZERO;
        int creditCount = 0;
        int debitCount = 0;

        DailySummary(LocalDate date) {
            this.date = date;
        }
    }
}