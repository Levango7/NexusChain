package org.nexus.compliance.aml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 AML 服务实现。
 * <p>
 * 筛查逻辑：
 * <ul>
 *   <li>{@link #screen}：从交易对象提取付款 / 收款地址与金额，逐一对制裁名单匹配，
 *       并结合金额阈值汇总风险等级（LOW / MEDIUM / HIGH / CRITICAL）</li>
 *   <li>{@link #screenAddress}：单地址制裁名单匹配</li>
 *   <li>{@link #screenUser}：用户 ID 制裁名单匹配</li>
 * </ul>
 * 风险等级约定（与网关 {@code DefaultComplianceService.mapRiskLevel} 对齐）：
 * 精确命中名单 → CRITICAL；模糊命中 → HIGH；仅金额超阈 → MEDIUM；无命中 → LOW。
 * 可疑交易报告受理为进程内登记（生成报告 ID、置 SUBMITTED、留存可查），
 * 后续替换为持久化存储与监管报送通道时仅需替换本实现。
 * </p>
 */
@Service
public class DefaultAmlService implements AmlScreeningService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAmlService.class);

    /** STR JSON 序列化（jackson-databind，compliance 已依赖）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper STR_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    /** 大额交易阈值（超过则至少 MEDIUM） */
    private static final BigDecimal LARGE_AMOUNT_THRESHOLD = new BigDecimal("100000");

    /** 制裁名单检查器 */
    private final SanctionListChecker sanctionListChecker;

    /** 已受理的可疑交易报告登记表（内存索引 + JSONL 文件持久化——TODO(v2.0.0) 落地） */
    private final Map<String, SuspiciousTransactionReport> filedReports = new ConcurrentHashMap<>();

    /**
     * STR 持久化目录（JSONL 追加，合规审计重启可恢复）。
     * 配置: compliance.aml.str-dir，默认 ./nexus-compliance-str
     */
    @org.springframework.beans.factory.annotation.Value("${compliance.aml.str-dir:./nexus-compliance-str}")
    private String strDir;

    private java.nio.file.Path strFile() {
        return java.nio.file.Path.of(strDir, "suspicious-transaction-reports.jsonl");
    }

    @jakarta.annotation.PostConstruct
    public void initStrStore() {
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(strDir));
            if (java.nio.file.Files.exists(strFile())) {
                // 启动加载已有 STR（重启恢复）
                for (String line : java.nio.file.Files.readAllLines(strFile())) {
                    try {
                        SuspiciousTransactionReport r = STR_MAPPER.readValue(line, SuspiciousTransactionReport.class);
                        if (r != null && r.getReportId() != null) {
                            filedReports.put(r.getReportId(), r);
                        }
                    } catch (Exception ignored) {
                        // 单行损坏跳过（审计日志文件容错）
                    }
                }
                log.info("DefaultAmlService: loaded {} STR from {}", filedReports.size(), strFile());
            }
        } catch (Exception e) {
            log.warn("DefaultAmlService: STR store init failed (in-memory only): {}", e.getMessage());
        }
    }

    /** 追加 STR 到 JSONL 文件（合规审计持久化）。 */
    private void persistReport(SuspiciousTransactionReport report) {
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(strDir));
            java.nio.file.Files.writeString(strFile(),
                    STR_MAPPER.writeValueAsString(report) + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("DefaultAmlService: STR persist failed (in-memory only): {}", e.getMessage());
        }
    }

    public DefaultAmlService(SanctionListChecker sanctionListChecker) {
        this.sanctionListChecker = sanctionListChecker;
    }

    @Override
    public ScreeningResult screen(Object transaction) {
        ScreeningResult result = new ScreeningResult();
        List<String> hitLists = new ArrayList<>();
        List<String> matchDetails = new ArrayList<>();

        // 提取交易要素：付款地址、收款地址、金额
        String fromAddress = extractString(transaction, "getFromAddress");
        String toAddress = extractString(transaction, "getToAddress");
        BigDecimal amount = extractAmount(transaction);

        // 地址名单匹配（返回命中次数用于风险分级）
        int hitCount = 0;
        hitCount += screenInto(fromAddress, hitLists, matchDetails, "from");
        hitCount += screenInto(toAddress, hitLists, matchDetails, "to");

        // 汇总风险等级
        String riskLevel = determineRiskLevel(hitLists, hitCount, amount);
        result.setRiskLevel(riskLevel);
        result.setHitLists(hitLists);
        result.setMatchDetails(matchDetails);
        result.setNeedManualReview(isHighRisk(riskLevel) || !hitLists.isEmpty());

        if (result.isNeedManualReview()) {
            log.warn("AML screen flagged: riskLevel={}, hits={}, amount={}",
                    riskLevel, hitLists, amount);
        }
        return result;
    }

    @Override
    public ScreeningResult screenAddress(String address) {
        ScreeningResult result = new ScreeningResult();
        List<String> hitLists = new ArrayList<>();
        List<String> matchDetails = new ArrayList<>();
        screenInto(address, hitLists, matchDetails, "address");

        String riskLevel = hitLists.isEmpty() ? "LOW" : "HIGH";
        result.setRiskLevel(riskLevel);
        result.setHitLists(hitLists);
        result.setMatchDetails(matchDetails);
        result.setNeedManualReview(!hitLists.isEmpty());
        return result;
    }

    @Override
    public ScreeningResult screenUser(String userId) {
        ScreeningResult result = new ScreeningResult();
        List<String> hitLists = new ArrayList<>();
        List<String> matchDetails = new ArrayList<>();
        screenInto(userId, hitLists, matchDetails, "user");

        String riskLevel = hitLists.isEmpty() ? "LOW" : "HIGH";
        result.setRiskLevel(riskLevel);
        result.setHitLists(hitLists);
        result.setMatchDetails(matchDetails);
        result.setNeedManualReview(!hitLists.isEmpty());
        return result;
    }

    @Override
    public SuspiciousTransactionReport fileSuspiciousReport(SuspiciousTransactionReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Suspicious transaction report must not be null");
        }
        if (report.getReportId() == null || report.getReportId().isBlank()) {
            report.setReportId("STR-" + UUID.randomUUID().toString().replace("-", ""));
        }
        report.setReportStatus(SuspiciousTransactionReport.ReportStatus.SUBMITTED);
        report.setReportedAt(Instant.now());
        filedReports.put(report.getReportId(), report);
        persistReport(report);  // TODO(v2.0.0) 落地：STR 持久化（JSONL 追加）
        log.warn("Suspicious transaction report filed: reportId={}, reason={}",
                report.getReportId(), report.getSuspiciousReason());
        return report;
    }

    /**
     * 查询已受理报告数量（测试 / 审计用）。
     *
     * @return 报告数
     */
    public int filedReportCount() {
        return filedReports.size();
    }

    /**
     * 将单个值与制裁名单匹配，命中则写入 hitLists / matchDetails。
     *
     * @return 本次命中次数
     */
    private int screenInto(String value, List<String> hitLists,
                           List<String> matchDetails, String fieldLabel) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        SanctionListChecker.SanctionHit[] hits = sanctionListChecker.check(value);
        for (SanctionListChecker.SanctionHit hit : hits) {
            if (!hitLists.contains(hit.getListName())) {
                hitLists.add(hit.getListName());
            }
            matchDetails.add(String.format("%s=%s hit list=%s score=%.2f",
                    fieldLabel, value, hit.getListName(), hit.getMatchScore()));
        }
        return hits.length;
    }

    /**
     * 汇总风险等级。
     */
    private String determineRiskLevel(List<String> hitLists, int hitCount, BigDecimal amount) {
        if (!hitLists.isEmpty()) {
            // 命中名单：多次命中升级 CRITICAL，单次命中 HIGH
            return hitCount >= 2 ? "CRITICAL" : "HIGH";
        }
        if (amount != null && amount.compareTo(LARGE_AMOUNT_THRESHOLD) > 0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private boolean isHighRisk(String riskLevel) {
        return "HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel);
    }

    /**
     * 反射提取交易对象的字符串属性（兼容网关 Transaction 等任意 DTO）。
     */
    private String extractString(Object transaction, String getterName) {
        if (transaction == null) {
            return null;
        }
        try {
            Method method = transaction.getClass().getMethod(getterName);
            Object value = method.invoke(transaction);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 反射提取交易金额。
     */
    private BigDecimal extractAmount(Object transaction) {
        if (transaction == null) {
            return null;
        }
        try {
            Method method = transaction.getClass().getMethod("getAmount");
            Object value = method.invoke(transaction);
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            if (value instanceof Number) {
                return new BigDecimal(value.toString());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
