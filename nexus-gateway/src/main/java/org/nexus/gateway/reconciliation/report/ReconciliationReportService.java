package org.nexus.gateway.reconciliation.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancyRepository;
import org.nexus.gateway.reconciliation.SuspenseAccount;
import org.nexus.gateway.reconciliation.SuspenseAccountRepository;
import org.nexus.gateway.reconciliation.compensation.CompensationRecord;
import org.nexus.gateway.reconciliation.compensation.CompensationRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 对账报表服务 — 生成 T+1 对账报表，汇总差异、补偿和挂账数据。
 *
 * <p>报表生成流程：</p>
 * <ol>
 *   <li>查询指定商户和日期范围内的所有差错记录</li>
 *   <li>查询关联的补偿记录</li>
 *   <li>查询关联的挂账记录</li>
 *   <li>构建 {@link ReconciliationReportContent} 并序列化为 JSON</li>
 *   <li>持久化 {@link ReconciliationReportRecord}</li>
 * </ol>
 *
 * <p>报表格式支持 JSON 和 CSV（当前实现 JSON 格式）。</p>
 */
@Service
public class ReconciliationReportService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationReportService.class);

    private final ReconciliationReportRecordRepository reportRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final CompensationRecordRepository compensationRepository;
    private final SuspenseAccountRepository suspenseRepository;
    private final ObjectMapper objectMapper;

    public ReconciliationReportService(ReconciliationReportRecordRepository reportRepository,
                                        ReconciliationDiscrepancyRepository discrepancyRepository,
                                        CompensationRecordRepository compensationRepository,
                                        SuspenseAccountRepository suspenseRepository,
                                        ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.compensationRepository = compensationRepository;
        this.suspenseRepository = suspenseRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 报表生成 ====================

    /**
     * 生成对账报表。
     *
     * @param merchantId  商户 ID
     * @param reportDate  报表日期（T+1 的 T）
     * @param channelType 渠道类型（可为 null 表示所有渠道）
     * @return 持久化的报表记录
     */
    @Transactional
    public ReconciliationReportRecord generateReport(Long merchantId, LocalDate reportDate,
                                                      String channelType) {
        log.info("[ReportService] 生成对账报表: merchantId={}, reportDate={}, channelType={}",
                merchantId, reportDate, channelType);

        // 查询差错记录
        LocalDateTime periodStart = reportDate.atStartOfDay();
        LocalDateTime periodEnd = reportDate.plusDays(1).atStartOfDay();
        List<ReconciliationDiscrepancy> discrepancies =
                discrepancyRepository.findByMerchantIdAndCreatedAtBetween(merchantId, periodStart, periodEnd);

        // 查询补偿记录
        List<CompensationRecord> compensations =
                compensationRepository.findByMerchantIdAndCreatedAtBetween(merchantId, periodStart, periodEnd);

        // 查询挂账记录
        List<SuspenseAccount> suspenseAccounts =
                suspenseRepository.findByMerchantIdAndCreatedAtBetween(merchantId, periodStart, periodEnd);

        // 构建报表内容
        ReconciliationReportContent content = buildReportContent(
                merchantId, reportDate, channelType, discrepancies, compensations, suspenseAccounts);

        // 序列化为 JSON
        String contentJson = serializeContent(content);

        // 持久化报表记录
        ReconciliationReportRecord record = new ReconciliationReportRecord();
        record.setMerchantId(merchantId);
        record.setReportDate(reportDate);
        record.setChannelType(channelType);
        record.setFileFormat("JSON");
        record.setTotalTransactions(content.getSummary().getTotalTransactions());
        record.setMatchedCount(content.getSummary().getMatchedCount());
        record.setDiscrepancyCount(content.getSummary().getDiscrepancyCount());
        record.setCompensationCount(content.getSummary().getCompensationCount());
        record.setSuspenseCount(content.getSummary().getSuspenseCount());
        record.setTotalDiscrepancyAmount(content.getSummary().getTotalDiscrepancyAmount());
        record.setFileSizeBytes((long) contentJson.length());
        record.setGeneratedAt(LocalDateTime.now());

        ReconciliationReportRecord saved = reportRepository.save(record);
        log.info("[ReportService] 报表已生成: id={}, discrepancies={}, compensations={}, suspense={}",
                saved.getId(), saved.getDiscrepancyCount(), saved.getCompensationCount(),
                saved.getSuspenseCount());

        return saved;
    }

    // ==================== 查询 ====================

    /**
     * 查询报表记录。
     */
    public Optional<ReconciliationReportRecord> getReport(Long id) {
        return reportRepository.findById(id);
    }

    /**
     * 查询商户的报表列表。
     */
    public List<ReconciliationReportRecord> getReportsByMerchant(Long merchantId) {
        return reportRepository.findByMerchantIdOrderByGeneratedAtDesc(merchantId);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建报表内容 DTO。
     */
    private ReconciliationReportContent buildReportContent(
            Long merchantId, LocalDate reportDate, String channelType,
            List<ReconciliationDiscrepancy> discrepancies,
            List<CompensationRecord> compensations,
            List<SuspenseAccount> suspenseAccounts) {

        ReconciliationReportContent content = new ReconciliationReportContent();

        // Metadata
        ReconciliationReportContent.Metadata metadata = new ReconciliationReportContent.Metadata();
        metadata.setMerchantId(merchantId);
        metadata.setReportDate(reportDate);
        metadata.setChannelType(channelType);
        metadata.setGeneratedAt(LocalDateTime.now());
        content.setMetadata(metadata);

        // Summary
        ReconciliationReportContent.Summary summary = new ReconciliationReportContent.Summary();
        summary.setDiscrepancyCount(discrepancies.size());
        summary.setTotalDiscrepancyAmount(discrepancies.stream()
                .map(ReconciliationDiscrepancy::getAmountDiff)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.setDiscrepancyByType(buildDiscrepancyByTypeMap(discrepancies));
        summary.setCompensationCount(compensations.size());
        summary.setCompensationSuccessCount((int) compensations.stream()
                .filter(c -> c.getStatus() == CompensationRecord.CompensationStatus.SUCCESS)
                .count());
        summary.setCompensationFailedCount((int) compensations.stream()
                .filter(c -> c.getStatus() == CompensationRecord.CompensationStatus.FAILED)
                .count());
        summary.setSuspenseCount(suspenseAccounts.size());
        // totalTransactions 和 matchedCount 从差错数据推算
        summary.setTotalTransactions(discrepancies.size());
        summary.setMatchedCount(0); // 匹配数需要从 ReconciliationDiffReport 获取，此处暂为0
        content.setSummary(summary);

        // Discrepancies
        content.setDiscrepancies(discrepancies.stream()
                .map(this::toDiscrepancyDto)
                .collect(Collectors.toList()));

        // Compensations
        content.setCompensations(compensations.stream()
                .map(this::toCompensationDto)
                .collect(Collectors.toList()));

        // Suspense Accounts
        content.setSuspenseAccounts(suspenseAccounts.stream()
                .map(this::toSuspenseDto)
                .collect(Collectors.toList()));

        return content;
    }

    /**
     * 构建差异类型统计 Map。
     */
    private Map<String, Integer> buildDiscrepancyByTypeMap(
            List<ReconciliationDiscrepancy> discrepancies) {
        Map<String, Integer> map = new HashMap<>();
        for (ReconciliationDiscrepancy d : discrepancies) {
            String type = d.getDiscrepancyType().name();
            map.merge(type, 1, Integer::sum);
        }
        return map;
    }

    /**
     * 差错转 DTO。
     */
    private ReconciliationReportContent.DiscrepancyDto toDiscrepancyDto(
            ReconciliationDiscrepancy d) {
        ReconciliationReportContent.DiscrepancyDto dto =
                new ReconciliationReportContent.DiscrepancyDto();
        dto.setId(d.getId());
        dto.setTransactionId(d.getTransactionId());
        dto.setDiscrepancyType(d.getDiscrepancyType().name());
        dto.setAmountDiff(d.getAmountDiff());
        dto.setStatus(d.getStatus().name());
        return dto;
    }

    /**
     * 补偿转 DTO。
     */
    private ReconciliationReportContent.CompensationDto toCompensationDto(
            CompensationRecord c) {
        ReconciliationReportContent.CompensationDto dto =
                new ReconciliationReportContent.CompensationDto();
        dto.setId(c.getId());
        dto.setDiscrepancyId(c.getDiscrepancyId());
        dto.setCompensationType(c.getCompensationType().name());
        dto.setAmount(c.getAmount());
        dto.setStatus(c.getStatus().name());
        dto.setFailureReason(c.getFailureReason());
        return dto;
    }

    /**
     * 挂账转 DTO。
     */
    private ReconciliationReportContent.SuspenseDto toSuspenseDto(SuspenseAccount s) {
        ReconciliationReportContent.SuspenseDto dto =
                new ReconciliationReportContent.SuspenseDto();
        dto.setId(s.getId());
        dto.setSuspenseType(s.getDiscrepancyType() != null
                ? s.getDiscrepancyType().name() : null);
        dto.setAmount(s.getAmount());
        dto.setStatus(s.getStatus() != null ? s.getStatus().name() : null);
        return dto;
    }

    /**
     * 序列化报表内容为 JSON。
     */
    private String serializeContent(ReconciliationReportContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            log.error("[ReportService] 序列化报表内容失败: {}", e.getMessage(), e);
            return "{}";
        }
    }
}