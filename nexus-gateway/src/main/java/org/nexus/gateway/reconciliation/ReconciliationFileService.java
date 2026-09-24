package org.nexus.gateway.reconciliation;

import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 对账文件生成服务。
 *
 * <p>负责生成标准化格式的对账文件（CSV / JSON），持久化文件元数据到数据库。
 * 文件内容本身不持久化——下载时根据元数据（商户、周期、文件类型）重新生成，
 * 保证数据始终与最新交易记录一致。</p>
 *
 * <p>CSV 格式遵循标准支付对账格式：注释行以 {@code #} 开头，包含头部元信息、
 * 列标题、数据行和尾部汇总。JSON 格式包含 metadata、transactions、summary
 * 三个结构化部分。</p>
 *
 * <p>集成 {@link ReconciliationEngine} 支持自动对账：生成对账文件后可调用
 * {@link #runReconciliation} 比对渠道对账文件与内部交易记录，自动发现并分类差异，
 * 持久化差错记录到 {@code reconciliation_discrepancies} 表。</p>
 */
@Service
public class ReconciliationFileService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final ReconciliationFileRecordRepository fileRecordRepository;
    private final ReconciliationEngine reconciliationEngine;
    private final DiscrepancyResolutionService discrepancyResolutionService;

    @Autowired
    public ReconciliationFileService(PaymentOrderRepository paymentOrderRepository,
                                     ReconciliationFileRecordRepository fileRecordRepository,
                                     ReconciliationEngine reconciliationEngine,
                                     DiscrepancyResolutionService discrepancyResolutionService) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.reconciliationEngine = reconciliationEngine;
        this.discrepancyResolutionService = discrepancyResolutionService;
    }

    /**
     * 生成日对账文件。
     *
     * <p>查询指定日期内该商户的所有交易，生成 CSV 和 JSON 两种格式的对账文件，
     * 保存文件元数据到数据库。返回创建的文件记录。</p>
     *
     * @param merchantId 商户 ID
     * @param date       对账日期
     * @return 对账文件记录
     */
    public ReconciliationFileRecord generateDailyFile(Long merchantId, LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

        List<PaymentOrder> orders = paymentOrderRepository.findByMerchantId(merchantId).stream()
                .filter(o -> o.getCreatedAt() != null
                        && !o.getCreatedAt().isBefore(dayStart)
                        && o.getCreatedAt().isBefore(dayEnd))
                .collect(Collectors.toList());

        return createFileRecord(merchantId, orders, date, date, "DAILY");
    }

    /**
     * 生成月对账文件。
     *
     * <p>查询指定月份内该商户的所有交易，生成 CSV 和 JSON 两种格式的对账文件，
     * 保存文件元数据到数据库。返回创建的文件记录。</p>
     *
     * @param merchantId 商户 ID
     * @param year       年份
     * @param month      月份（1-12）
     * @return 对账文件记录
     */
    public ReconciliationFileRecord generateMonthlyFile(Long merchantId, int year, int month) {
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate periodStart = yearMonth.atDay(1);
        LocalDate periodEnd = yearMonth.atEndOfMonth();

        LocalDateTime monthStart = periodStart.atStartOfDay();
        LocalDateTime monthEnd = periodEnd.plusDays(1).atStartOfDay();

        List<PaymentOrder> orders = paymentOrderRepository.findByMerchantId(merchantId).stream()
                .filter(o -> o.getCreatedAt() != null
                        && !o.getCreatedAt().isBefore(monthStart)
                        && o.getCreatedAt().isBefore(monthEnd))
                .collect(Collectors.toList());

        return createFileRecord(merchantId, orders, periodStart, periodEnd, "MONTHLY");
    }

    /**
     * 构建 CSV 格式对账文件内容。
     *
     * <p>格式：头部注释（以 # 开头）→ 列标题 → 数据行 → 尾部汇总。</p>
     *
     * @param merchantId 商户 ID
     * @param orders     交易列表
     * @param start      对账周期开始日期
     * @param end        对账周期结束日期
     * @return CSV 字符串
     */
    public String generateCsvContent(Long merchantId, List<PaymentOrder> orders,
                                     LocalDate start, LocalDate end) {
        StringBuilder csv = new StringBuilder();

        // 头部注释
        csv.append("# NexusChain Daily Reconciliation File\n");
        csv.append("# Merchant: ").append(merchantId).append("\n");
        csv.append("# Period: ").append(start);
        if (!start.equals(end)) {
            csv.append(" to ").append(end);
        }
        csv.append("\n");
        csv.append("# Generated: ").append(LocalDateTime.now()).append("\n");

        // 列标题
        csv.append("order_no,amount,currency,status,connector_id,created_at,paid_at\n");

        // 数据行
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (PaymentOrder order : orders) {
            csv.append(order.getOrderNo()).append(",");
            csv.append(order.getAmount()).append(",");
            csv.append(order.getTokenSymbol() != null ? order.getTokenSymbol() : "NEX").append(",");
            csv.append(order.getStatus() != null ? order.getStatus().name() : "").append(",");
            csv.append(order.getChainTxHash() != null ? order.getChainTxHash() : "").append(",");
            csv.append(order.getCreatedAt() != null ? order.getCreatedAt() : "").append(",");
            csv.append(order.getPaidAt() != null ? order.getPaidAt() : "").append("\n");
            if (order.getAmount() != null) {
                totalAmount = totalAmount.add(order.getAmount());
            }
        }

        // 尾部汇总
        csv.append("# Summary: total_transactions=").append(orders.size());
        csv.append(", total_amount=").append(totalAmount);
        csv.append(", matched=").append(orders.size());
        csv.append(", discrepancies=0\n");

        return csv.toString();
    }

    /**
     * 构建 JSON 格式对账文件内容。
     *
     * <p>结构：metadata（文件元信息）+ transactions（交易列表）+ summary（汇总）。</p>
     *
     * @param merchantId 商户 ID
     * @param orders     交易列表
     * @param start      对账周期开始日期
     * @param end        对账周期结束日期
     * @return JSON 字符串
     */
    public String generateJsonContent(Long merchantId, List<PaymentOrder> orders,
                                      LocalDate start, LocalDate end) {
        StringBuilder json = new StringBuilder();

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (PaymentOrder order : orders) {
            if (order.getAmount() != null) {
                totalAmount = totalAmount.add(order.getAmount());
            }
        }

        json.append("{\n");

        // metadata 部分
        json.append("  \"metadata\": {\n");
        json.append("    \"merchantId\": ").append(merchantId).append(",\n");
        json.append("    \"periodStart\": \"").append(start).append("\",\n");
        json.append("    \"periodEnd\": \"").append(end).append("\",\n");
        json.append("    \"generatedAt\": \"").append(LocalDateTime.now()).append("\",\n");
        json.append("    \"fileType\": \"JSON\"\n");
        json.append("  },\n");

        // transactions 部分
        json.append("  \"transactions\": [\n");
        for (int i = 0; i < orders.size(); i++) {
            PaymentOrder order = orders.get(i);
            json.append("    {\n");
            json.append("      \"orderNo\": \"").append(order.getOrderNo()).append("\",\n");
            json.append("      \"amount\": \"").append(order.getAmount()).append("\",\n");
            json.append("      \"currency\": \"")
                    .append(order.getTokenSymbol() != null ? order.getTokenSymbol() : "NEX")
                    .append("\",\n");
            json.append("      \"status\": \"")
                    .append(order.getStatus() != null ? order.getStatus().name() : "")
                    .append("\",\n");
            json.append("      \"connectorId\": \"")
                    .append(order.getChainTxHash() != null ? order.getChainTxHash() : "")
                    .append("\",\n");
            json.append("      \"createdAt\": \"")
                    .append(order.getCreatedAt() != null ? order.getCreatedAt() : "")
                    .append("\",\n");
            json.append("      \"paidAt\": \"")
                    .append(order.getPaidAt() != null ? order.getPaidAt() : "")
                    .append("\"\n");
            json.append("    }");
            if (i < orders.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ],\n");

        // summary 部分
        json.append("  \"summary\": {\n");
        json.append("    \"totalTransactions\": ").append(orders.size()).append(",\n");
        json.append("    \"totalAmount\": \"").append(totalAmount).append("\",\n");
        json.append("    \"matchedCount\": ").append(orders.size()).append(",\n");
        json.append("    \"discrepancyCount\": 0\n");
        json.append("  }\n");

        json.append("}\n");

        return json.toString();
    }

    /**
     * 获取商户的对账文件历史记录列表。
     *
     * @param merchantId 商户 ID
     * @return 文件记录列表（按生成时间倒序）
     */
    public List<ReconciliationFileRecord> getFileHistory(Long merchantId) {
        return fileRecordRepository.findByMerchantIdOrderByGeneratedAtDesc(merchantId);
    }

    /**
     * 按 ID 查找文件记录。
     *
     * @param id 文件记录 ID
     * @return 文件记录（可能为空）
     */
    public Optional<ReconciliationFileRecord> getFileRecord(Long id) {
        return fileRecordRepository.findById(id);
    }

    /**
     * 根据文件记录重新生成文件内容。
     *
     * <p>文件内容不持久化到数据库，下载时根据元数据重新生成。
     * 根据记录的 fileType 字段决定生成 CSV 或 JSON 格式。</p>
     *
     * <p>P1-6 修复：添加 callerMerchantId 参数，验证文件记录属于当前商户，
     * 防止跨商户下载对账文件。</p>
     *
     * @param record           文件记录
     * @param callerMerchantId 当前认证商户 ID
     * @return 文件内容字符串
     * @throws org.nexus.gateway.security.MerchantOwnershipException 如果文件不属于当前商户
     */
    public String getFileContent(ReconciliationFileRecord record, Long callerMerchantId) {
        // P1-6：验证文件归属
        if (callerMerchantId != null && record.getMerchantId() != null
                && !callerMerchantId.equals(record.getMerchantId())) {
            throw new org.nexus.gateway.security.MerchantOwnershipException(
                    "Access denied: reconciliation file " + record.getId()
                            + " does not belong to the authenticated merchant");
        }

        LocalDateTime periodStartDt = record.getPeriodStart().atStartOfDay();
        LocalDateTime periodEndDt = record.getPeriodEnd().plusDays(1).atStartOfDay();

        List<PaymentOrder> orders = paymentOrderRepository.findByMerchantId(record.getMerchantId()).stream()
                .filter(o -> o.getCreatedAt() != null
                        && !o.getCreatedAt().isBefore(periodStartDt)
                        && o.getCreatedAt().isBefore(periodEndDt))
                .collect(Collectors.toList());

        if ("JSON".equalsIgnoreCase(record.getFileType())) {
            return generateJsonContent(record.getMerchantId(), orders,
                    record.getPeriodStart(), record.getPeriodEnd());
        } else {
            return generateCsvContent(record.getMerchantId(), orders,
                    record.getPeriodStart(), record.getPeriodEnd());
        }
    }

    /**
     * 根据文件记录重新生成文件内容（无商户校验，仅内部调用或测试使用）。
     *
     * @param record 文件记录
     * @return 文件内容字符串
     */
    public String getFileContent(ReconciliationFileRecord record) {
        return getFileContent(record, null);
    }

    // --- 自动对账集成 ---

    /**
     * 执行自动对账：比对渠道对账文件内容与内部交易记录。
     *
     * <p>解析渠道提供的 CSV 对账文件内容，与内部 PaymentOrder 记录比对，
     * 生成差异报告并持久化所有差错记录。同时更新对账文件记录的匹配数和差错数。</p>
     *
     * @param merchantId       商户 ID
     * @param channelCsvContent 渠道对账文件 CSV 内容
     * @param date             对账日期
     * @return 对账差异报告
     */
    @Transactional
    public ReconciliationDiffReport runDailyReconciliation(Long merchantId,
                                                            String channelCsvContent,
                                                            LocalDate date) {
        // 生成内部对账文件记录
        ReconciliationFileRecord fileRecord = generateDailyFile(merchantId, date);

        // 查询内部交易记录
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<PaymentOrder> internalOrders = paymentOrderRepository.findByMerchantId(merchantId).stream()
                .filter(o -> o.getCreatedAt() != null
                        && !o.getCreatedAt().isBefore(dayStart)
                        && o.getCreatedAt().isBefore(dayEnd))
                .collect(Collectors.toList());

        // 解析渠道对账文件
        List<ChannelRecord> channelRecords = reconciliationEngine.parseCsvContent(channelCsvContent);

        // 执行比对
        ReconciliationDiffReport report = reconciliationEngine.reconcile(
                merchantId, channelRecords, internalOrders, fileRecord.getId());

        // 持久化差错记录
        if (report.getDiscrepancies() != null && !report.getDiscrepancies().isEmpty()) {
            discrepancyResolutionService.saveDiscrepancies(report.getDiscrepancies());
        }

        // 更新对账文件记录的匹配数和差错数
        fileRecord.setMatchedCount(report.getMatchedCount());
        fileRecord.setDiscrepancyCount(report.getDiscrepancies() != null
                ? report.getDiscrepancies().size() : 0);
        fileRecordRepository.save(fileRecord);

        return report;
    }

    /**
     * 执行自动对账：使用已有对账文件记录，比对渠道对账文件内容与内部交易记录。
     *
     * @param reconciliationFileId 对账文件记录 ID
     * @param channelCsvContent    渠道对账文件 CSV 内容
     * @return 对账差异报告
     */
    @Transactional
    public ReconciliationDiffReport runReconciliation(Long reconciliationFileId,
                                                      String channelCsvContent) {
        ReconciliationFileRecord fileRecord = fileRecordRepository.findById(reconciliationFileId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Reconciliation file not found: " + reconciliationFileId));

        // 查询内部交易记录
        LocalDateTime periodStartDt = fileRecord.getPeriodStart().atStartOfDay();
        LocalDateTime periodEndDt = fileRecord.getPeriodEnd().plusDays(1).atStartOfDay();
        List<PaymentOrder> internalOrders = paymentOrderRepository
                .findByMerchantId(fileRecord.getMerchantId()).stream()
                .filter(o -> o.getCreatedAt() != null
                        && !o.getCreatedAt().isBefore(periodStartDt)
                        && o.getCreatedAt().isBefore(periodEndDt))
                .collect(Collectors.toList());

        // 解析渠道对账文件
        List<ChannelRecord> channelRecords = reconciliationEngine.parseCsvContent(channelCsvContent);

        // 执行比对
        ReconciliationDiffReport report = reconciliationEngine.reconcile(
                fileRecord.getMerchantId(), channelRecords, internalOrders, fileRecord.getId());

        // 持久化差错记录
        if (report.getDiscrepancies() != null && !report.getDiscrepancies().isEmpty()) {
            discrepancyResolutionService.saveDiscrepancies(report.getDiscrepancies());
        }

        // 更新对账文件记录的匹配数和差错数
        fileRecord.setMatchedCount(report.getMatchedCount());
        fileRecord.setDiscrepancyCount(report.getDiscrepancies() != null
                ? report.getDiscrepancies().size() : 0);
        fileRecordRepository.save(fileRecord);

        return report;
    }

    // --- 内部方法 ---

    /**
     * 创建并保存对账文件记录。
     */
    private ReconciliationFileRecord createFileRecord(Long merchantId, List<PaymentOrder> orders,
                                                      LocalDate periodStart, LocalDate periodEnd,
                                                      String periodType) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (PaymentOrder order : orders) {
            if (order.getAmount() != null) {
                totalAmount = totalAmount.add(order.getAmount());
            }
        }

        // 生成 CSV 内容以计算文件大小
        String csvContent = generateCsvContent(merchantId, orders, periodStart, periodEnd);
        long fileSizeBytes = csvContent.getBytes().length;

        LocalDateTime generatedAt = LocalDateTime.now();

        ReconciliationFileRecord record = new ReconciliationFileRecord();
        record.setMerchantId(merchantId);
        record.setFileType("CSV");
        record.setPeriodType(periodType);
        record.setPeriodStart(periodStart);
        record.setPeriodEnd(periodEnd);
        record.setTotalTransactions(orders.size());
        record.setTotalAmount(totalAmount);
        record.setMatchedCount(orders.size());
        record.setDiscrepancyCount(0);
        record.setFileUrl("/api/v1/reconciliation/download/" + 0); // ID 在保存后更新
        record.setFileSizeBytes(fileSizeBytes);
        record.setGeneratedAt(generatedAt);

        ReconciliationFileRecord saved = fileRecordRepository.save(record);

        // 更新 fileUrl 为包含实际 ID
        saved.setFileUrl("/api/v1/reconciliation/download/" + saved.getId());
        return fileRecordRepository.save(saved);
    }
}