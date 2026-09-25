package org.nexus.gateway.fundreport;

import org.nexus.gateway.account.AccountTransaction;
import org.nexus.gateway.account.AccountTransactionRepository;
import org.nexus.gateway.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * 资金报表服务 — 生成商户资金流水报表。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #generateReport} — 生成日报/周报/月报，支持 CSV/JSON 格式</li>
 *   <li>账户编号脱敏 — 仅保留后 4 位，前缀 ****</li>
 *   <li>数据范围限制 — 单次报表最多 10000 条流水</li>
 *   <li>大数据量分批处理 — 超过 1000 条时分批查询</li>
 * </ul>
 */
@Service
public class FundReportService {

    private static final Logger log = LoggerFactory.getLogger(FundReportService.class);

    /** 单次报表最大流水条数 */
    private static final int MAX_REPORT_ROWS = 10000;

    /** 分批查询每批条数 */
    private static final int BATCH_SIZE = 1000;

    /** CSV 表头 */
    private static final String CSV_HEADER =
            "流水编号,账户编号,商户ID,操作类型,方向,金额,操作前余额,操作后余额,关联凭证,时间";

    private final FundReportRepository reportRepository;
    private final AccountTransactionRepository transactionRepository;

    public FundReportService(FundReportRepository reportRepository,
                              AccountTransactionRepository transactionRepository) {
        this.reportRepository = reportRepository;
        this.transactionRepository = transactionRepository;
    }

    // === 生成报表 ===

    /**
     * 生成资金报表 — 根据报表类型和格式生成商户资金流水报表。
     *
     * <p>报表类型决定统计周期：</p>
     * <ul>
     *   <li>DAILY — 当天 00:00:00 到 23:59:59</li>
     *   <li>WEEKLY — 本周一 00:00:00 到 本周日 23:59:59</li>
     *   <li>MONTHLY — 本月 1 日 00:00:00 到 本月最后一天 23:59:59</li>
     * </ul>
     *
     * <p>账户编号脱敏规则：仅保留后 4 位，前缀 ****。
     * 例如 MA12345BALANCE → ****LANCE。</p>
     *
     * @param merchantId 商户 ID
     * @param reportType 报表类型（DAILY/WEEKLY/MONTHLY）
     * @param reportFormat 报表格式（CSV/JSON）
     * @return 生成的报表
     */
    @Transactional
    public FundReport generateReport(Long merchantId, ReportType reportType, ReportFormat reportFormat) {
        // 计算报表周期
        LocalDateTime[] period = calculatePeriod(reportType);
        LocalDateTime periodStart = period[0];
        LocalDateTime periodEnd = period[1];

        log.info("生成资金报表: merchantId={}, type={}, format={}, period=[{}, {}]",
                merchantId, reportType, reportFormat, periodStart, periodEnd);

        // 分批查询流水数据
        List<AccountTransaction> transactions = fetchTransactionsInBatches(
                merchantId, periodStart, periodEnd);

        // 生成报表内容
        String content;
        if (reportFormat == ReportFormat.CSV) {
            content = generateCsvContent(transactions);
        } else {
            content = generateJsonContent(transactions, periodStart, periodEnd);
        }

        // 创建报表记录
        FundReport report = new FundReport();
        report.setReportNo(generateReportNo());
        report.setMerchantId(merchantId);
        report.setReportType(reportType);
        report.setReportFormat(reportFormat);
        report.setPeriodStart(periodStart);
        report.setPeriodEnd(periodEnd);
        report.setContent(content);
        report.setStatus("GENERATED");
        report.setTenantId(TenantContext.getCurrentTenantId());

        report = reportRepository.save(report);

        log.info("资金报表生成完成: reportNo={}, rows={}", report.getReportNo(), transactions.size());
        return report;
    }

    // === 查询报表 ===

    /**
     * 查询商户报表列表（分页）。
     *
     * @param merchantId 商户 ID
     * @param page 页码（从 0 开始）
     * @param size 每页条数
     * @return 报表分页结果
     */
    @Transactional(readOnly = true)
    public Page<FundReport> getReports(Long merchantId, int page, int size) {
        return reportRepository.findByMerchantIdOrderByCreatedAtDesc(
                merchantId, PageRequest.of(page, size));
    }

    /**
     * 按报表编号查询报表详情。
     *
     * @param reportNo 报表编号
     * @return 报表（可能为空）
     */
    @Transactional(readOnly = true)
    public Optional<FundReport> getReportByNo(String reportNo) {
        return reportRepository.findByReportNo(reportNo);
    }

    // === 内部方法 ===

    /**
     * 计算报表周期 — 根据报表类型确定时间范围。
     */
    private LocalDateTime[] calculatePeriod(ReportType reportType) {
        LocalDate today = LocalDate.now();
        LocalDateTime periodStart;
        LocalDateTime periodEnd;

        switch (reportType) {
            case DAILY:
                periodStart = today.atStartOfDay();
                periodEnd = today.plusDays(1).atStartOfDay().minusSeconds(1);
                break;
            case WEEKLY:
                // ISO 周从周一开始
                int dayOfWeek = today.getDayOfWeek().getValue();
                LocalDate monday = today.minusDays(dayOfWeek - 1);
                LocalDate sunday = monday.plusDays(6);
                periodStart = monday.atStartOfDay();
                periodEnd = sunday.plusDays(1).atStartOfDay().minusSeconds(1);
                break;
            case MONTHLY:
                LocalDate firstDay = today.withDayOfMonth(1);
                LocalDate lastDay = today.withDayOfMonth(today.lengthOfMonth());
                periodStart = firstDay.atStartOfDay();
                periodEnd = lastDay.plusDays(1).atStartOfDay().minusSeconds(1);
                break;
            default:
                throw new IllegalArgumentException("不支持的报表类型: " + reportType);
        }

        return new LocalDateTime[]{periodStart, periodEnd};
    }

    /**
     * 分批查询流水数据 — 大数据量时分批查询避免内存溢出。
     *
     * <p>数据范围限制：最多查询 MAX_REPORT_ROWS 条流水。
     * 分批策略：每批 BATCH_SIZE 条，直到查完或达到上限。</p>
     */
    private List<AccountTransaction> fetchTransactionsInBatches(
            Long merchantId, LocalDateTime start, LocalDateTime end) {

        List<AccountTransaction> allTransactions = new ArrayList<>();
        int fetched = 0;

        while (fetched < MAX_REPORT_ROWS) {
            int remaining = MAX_REPORT_ROWS - fetched;
            int currentBatchSize = Math.min(BATCH_SIZE, remaining);

            // 使用分页查询实现分批
            int pageNum = fetched / BATCH_SIZE;
            Page<AccountTransaction> page = transactionRepository
                    .findByMerchantIdOrderByCreatedAtDesc(
                            merchantId, PageRequest.of(pageNum, currentBatchSize));

            if (page.isEmpty()) {
                break;
            }

            // 过滤时间范围内的流水
            for (AccountTransaction tx : page.getContent()) {
                if (tx.getCreatedAt() != null
                        && !tx.getCreatedAt().isBefore(start)
                        && !tx.getCreatedAt().isAfter(end)) {
                    allTransactions.add(tx);
                }
            }

            fetched += page.getContent().size();

            // 如果当前页不满，说明没有更多数据
            if (page.getContent().size() < currentBatchSize) {
                break;
            }
        }

        log.debug("分批查询完成: merchantId={}, totalFetched={}, matchedRows={}",
                merchantId, fetched, allTransactions.size());
        return allTransactions;
    }

    /**
     * 生成 CSV 格式报表内容。
     *
     * <p>账户编号脱敏：仅保留后 4 位，前缀 ****。</p>
     */
    private String generateCsvContent(List<AccountTransaction> transactions) {
        StringBuilder sb = new StringBuilder();
        sb.append(CSV_HEADER).append("\n");

        for (AccountTransaction tx : transactions) {
            sb.append(tx.getTxNo()).append(",")
                    .append(maskAccountId(tx.getAccountId())).append(",")
                    .append(tx.getMerchantId()).append(",")
                    .append(tx.getOperationType()).append(",")
                    .append(tx.getDirection()).append(",")
                    .append(tx.getAmount()).append(",")
                    .append(tx.getBalanceBefore()).append(",")
                    .append(tx.getBalanceAfter()).append(",")
                    .append(tx.getReference()).append(",")
                    .append(tx.getCreatedAt())
                    .append("\n");
        }

        return sb.toString();
    }

    /**
     * 生成 JSON 格式报表内容。
     *
     * <p>包含报表摘要和流水明细。账户编号脱敏：仅保留后 4 位，前缀 ****。</p>
     */
    private String generateJsonContent(List<AccountTransaction> transactions,
                                        LocalDateTime periodStart, LocalDateTime periodEnd) {
        // 汇总统计
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal totalDebit = BigDecimal.ZERO;
        int creditCount = 0;
        int debitCount = 0;

        for (AccountTransaction tx : transactions) {
            if (tx.getDirection() == org.nexus.gateway.account.TransactionDirection.CREDIT) {
                totalCredit = totalCredit.add(tx.getAmount());
                creditCount++;
            } else {
                totalDebit = totalDebit.add(tx.getAmount());
                debitCount++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"summary\":{");
        sb.append("\"totalTransactions\":").append(transactions.size()).append(",");
        sb.append("\"totalCredit\":").append(totalCredit).append(",");
        sb.append("\"totalDebit\":").append(totalDebit).append(",");
        sb.append("\"creditCount\":").append(creditCount).append(",");
        sb.append("\"debitCount\":").append(debitCount).append(",");
        sb.append("\"netFlow\":").append(totalCredit.subtract(totalDebit));
        sb.append("},");
        sb.append("\"period\":{");
        sb.append("\"start\":\"").append(periodStart).append("\",");
        sb.append("\"end\":\"").append(periodEnd).append("\"");
        sb.append("},");
        sb.append("\"transactions\":[");

        for (int i = 0; i < transactions.size(); i++) {
            AccountTransaction tx = transactions.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{");
            sb.append("\"txNo\":\"").append(tx.getTxNo()).append("\",");
            sb.append("\"accountId\":\"").append(maskAccountId(tx.getAccountId())).append("\",");
            sb.append("\"merchantId\":").append(tx.getMerchantId()).append(",");
            sb.append("\"operationType\":\"").append(tx.getOperationType()).append("\",");
            sb.append("\"direction\":\"").append(tx.getDirection()).append("\",");
            sb.append("\"amount\":").append(tx.getAmount()).append(",");
            sb.append("\"balanceBefore\":").append(tx.getBalanceBefore()).append(",");
            sb.append("\"balanceAfter\":").append(tx.getBalanceAfter()).append(",");
            sb.append("\"reference\":\"").append(tx.getReference()).append("\",");
            sb.append("\"createdAt\":\"").append(tx.getCreatedAt()).append("\"");
            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * 账户编号脱敏 — 仅保留后 4 位，前缀 ****。
     *
     * <p>例如：MA12345BALANCE → ****LANCE</p>
     */
    private String maskAccountId(String accountId) {
        if (accountId == null || accountId.length() <= 4) {
            return "****";
        }
        return "****" + accountId.substring(accountId.length() - 4);
    }

    /**
     * 生成报表编号：FR{timestamp}{uuid}。
     */
    private String generateReportNo() {
        return "FR" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "");
    }
}