package org.nexus.gateway.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.model.Refund;
import org.nexus.gateway.orchestration.webhook.WebhookDeliveryRecord;
import org.nexus.gateway.orchestration.webhook.WebhookDeliveryRepository;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;
import org.nexus.gateway.risk.RiskEvent;
import org.nexus.gateway.risk.RiskEventRepository;
import org.nexus.gateway.split.SplitOrder;
import org.nexus.gateway.split.SplitOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 数据导出服务。
 *
 * <p>负责创建、处理、查询和清理数据导出请求。导出流程：
 * <ol>
 *   <li>商户通过 API 创建导出请求（PENDING 状态）</li>
 *   <li>异步任务处理导出（PROCESSING → COMPLETED/FAILED）</li>
 *   <li>商户查询导出状态或下载文件</li>
 *   <li>定时清理过期文件（completedAt > retentionDays 的记录）</li>
 * </ol>
 *
 * <p>支持的导出类型：交易、结算、退款、分账、风控事件、Webhook 投递记录。
 * 支持的导出格式：CSV、JSON。</p>
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

    private final DataExportRequestRepository exportRequestRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final RefundRepository refundRepository;
    private final SplitOrderRepository splitOrderRepository;
    private final RiskEventRepository riskEventRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final ObjectMapper objectMapper;

    @Value("${nexus.data-export.storage-path:data/exports}")
    private String storagePath;

    @Value("${nexus.data-export.file-retention-days:7}")
    private int fileRetentionDays;

    @Value("${nexus.data-export.max-records-per-export:100000}")
    private int maxRecordsPerExport;

    @Autowired
    public DataExportService(DataExportRequestRepository exportRequestRepository,
                             PaymentOrderRepository paymentOrderRepository,
                             RefundRepository refundRepository,
                             SplitOrderRepository splitOrderRepository,
                             RiskEventRepository riskEventRepository,
                             WebhookDeliveryRepository webhookDeliveryRepository,
                             ObjectMapper objectMapper) {
        this.exportRequestRepository = exportRequestRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.refundRepository = refundRepository;
        this.splitOrderRepository = splitOrderRepository;
        this.riskEventRepository = riskEventRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建数据导出请求。
     *
     * @param merchantId 商户 ID
     * @param exportType 导出类型
     * @param format     导出格式
     * @param dateFrom   数据时间范围起始
     * @param dateTo     数据时间范围结束
     * @param filters    额外过滤条件（JSON 格式，可为 null）
     * @return 创建的导出请求
     */
    public DataExportRequest createExportRequest(Long merchantId,
                                                  DataExportType exportType,
                                                  DataExportFormat format,
                                                  LocalDateTime dateFrom,
                                                  LocalDateTime dateTo,
                                                  String filters) {
        DataExportRequest request = new DataExportRequest();
        request.setMerchantId(merchantId);
        request.setExportType(exportType);
        request.setFormat(format);
        request.setDateFrom(dateFrom);
        request.setDateTo(dateTo);
        request.setFilters(filters);
        request.setStatus(DataExportRequest.Status.PENDING);

        DataExportRequest saved = exportRequestRepository.save(request);
        log.info("创建数据导出请求: id={}, merchantId={}, type={}, format={}",
                saved.getId(), merchantId, exportType, format);
        return saved;
    }

    /**
     * 异步处理数据导出请求。
     *
     * <p>根据导出类型查询数据，生成 CSV 或 JSON 文件，存储到本地文件系统，
     * 更新请求状态为 COMPLETED。失败时更新状态为 FAILED 并记录错误信息。</p>
     *
     * @param requestId 导出请求 ID
     */
    @Async
    public void processExport(Long requestId) {
        Optional<DataExportRequest> requestOpt = exportRequestRepository.findById(requestId);
        if (requestOpt.isEmpty()) {
            log.warn("导出请求不存在: id={}", requestId);
            return;
        }

        DataExportRequest request = requestOpt.get();
        request.setStatus(DataExportRequest.Status.PROCESSING);
        exportRequestRepository.save(request);

        try {
            List<?> records = queryDataForExport(request);
            int recordCount = records.size();

            if (recordCount > maxRecordsPerExport) {
                records = records.subList(0, maxRecordsPerExport);
                recordCount = maxRecordsPerExport;
                log.warn("导出记录数超过上限，截断至 {} 条: requestId={}", maxRecordsPerExport, requestId);
            }

            String fileContent;
            if (request.getFormat() == DataExportFormat.JSON) {
                fileContent = generateJsonFile(request, records);
            } else {
                fileContent = generateCsvFile(request, records);
            }

            Path filePath = storeExportFile(request, fileContent);

            request.setStatus(DataExportRequest.Status.COMPLETED);
            request.setFilePath(filePath.toString());
            request.setFileSizeBytes(Files.size(filePath));
            request.setRecordCount(recordCount);
            request.setCompletedAt(LocalDateTime.now());
            exportRequestRepository.save(request);

            log.info("数据导出完成: id={}, type={}, records={}, file={}",
                    requestId, request.getExportType(), recordCount, filePath);

        } catch (Exception e) {
            request.setStatus(DataExportRequest.Status.FAILED);
            request.setErrorMessage(truncateMessage(e.getMessage(), 1024));
            request.setCompletedAt(LocalDateTime.now());
            exportRequestRepository.save(request);
            log.error("数据导出失败: id={}, error={}", requestId, e.getMessage(), e);
        }
    }

    /**
     * 查询导出请求状态。
     *
     * @param id 导出请求 ID
     * @return 导出请求（可能为空）
     */
    public Optional<DataExportRequest> getExportRequest(Long id) {
        return exportRequestRepository.findById(id);
    }

    /**
     * 列出租户的导出请求。
     *
     * @param merchantId 商户 ID
     * @return 导出请求列表（按创建时间倒序）
     */
    public List<DataExportRequest> listExportRequests(Long merchantId) {
        return exportRequestRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /**
     * 下载导出文件内容。
     *
     * @param id 导出请求 ID
     * @return 文件内容字节数组（可能为空）
     */
    public Optional<byte[]> downloadExport(Long id) {
        Optional<DataExportRequest> requestOpt = exportRequestRepository.findById(id);
        if (requestOpt.isEmpty()) {
            return Optional.empty();
        }

        DataExportRequest request = requestOpt.get();
        if (request.getStatus() != DataExportRequest.Status.COMPLETED || request.getFilePath() == null) {
            return Optional.empty();
        }

        try {
            Path filePath = Paths.get(request.getFilePath());
            if (Files.exists(filePath)) {
                return Optional.of(Files.readAllBytes(filePath));
            }
            log.warn("导出文件不存在: id={}, path={}", id, request.getFilePath());
            return Optional.empty();
        } catch (IOException e) {
            log.error("读取导出文件失败: id={}, error={}", id, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 删除导出请求和关联文件。
     *
     * @param id 导出请求 ID
     * @return true 如果删除成功
     */
    public boolean deleteExport(Long id) {
        Optional<DataExportRequest> requestOpt = exportRequestRepository.findById(id);
        if (requestOpt.isEmpty()) {
            return false;
        }

        DataExportRequest request = requestOpt.get();

        // 删除文件
        if (request.getFilePath() != null) {
            try {
                Path filePath = Paths.get(request.getFilePath());
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                log.warn("删除导出文件失败: id={}, path={}, error={}",
                        id, request.getFilePath(), e.getMessage());
            }
        }

        exportRequestRepository.delete(request);
        log.info("删除导出请求: id={}", id);
        return true;
    }

    /**
     * 定时清理过期导出文件。
     *
     * <p>每天凌晨 2 点执行，清理 completedAt 超过保留天数（默认 7 天）的导出文件。
     * 将状态标记为 EXPIRED 并删除物理文件。</p>
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupExpiredExports() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(fileRetentionDays);
        List<DataExportRequest> expiredRequests = exportRequestRepository
                .findByStatusAndCompletedAtBefore(DataExportRequest.Status.COMPLETED, cutoff);

        for (DataExportRequest request : expiredRequests) {
            // 删除物理文件
            if (request.getFilePath() != null) {
                try {
                    Path filePath = Paths.get(request.getFilePath());
                    Files.deleteIfExists(filePath);
                } catch (IOException e) {
                    log.warn("清理过期导出文件失败: id={}, path={}, error={}",
                            request.getId(), request.getFilePath(), e.getMessage());
                }
            }

            request.setStatus(DataExportRequest.Status.EXPIRED);
            request.setExpiredAt(LocalDateTime.now());
            exportRequestRepository.save(request);
        }

        if (!expiredRequests.isEmpty()) {
            log.info("清理过期导出文件: count={}", expiredRequests.size());
        }
    }

    // --- 内部方法 ---

    /**
     * 根据导出类型查询数据。
     */
    private List<?> queryDataForExport(DataExportRequest request) {
        Long merchantId = request.getMerchantId();
        LocalDateTime dateFrom = request.getDateFrom();
        LocalDateTime dateTo = request.getDateTo();

        switch (request.getExportType()) {
            case TRANSACTIONS:
                return paymentOrderRepository.findByMerchantId(merchantId).stream()
                        .filter(o -> o.getCreatedAt() != null
                                && !o.getCreatedAt().isBefore(dateFrom)
                                && !o.getCreatedAt().isAfter(dateTo))
                        .collect(Collectors.toList());

            case REFUNDS:
                return refundRepository.findByMerchantId(merchantId).stream()
                        .filter(r -> r.getCreatedAt() != null
                                && !r.getCreatedAt().isBefore(dateFrom)
                                && !r.getCreatedAt().isAfter(dateTo))
                        .collect(Collectors.toList());

            case SPLITS:
                return splitOrderRepository.findByMerchantId(merchantId).stream()
                        .filter(s -> s.getCreatedAt() != null
                                && !s.getCreatedAt().isBefore(dateFrom)
                                && !s.getCreatedAt().isAfter(dateTo))
                        .collect(Collectors.toList());

            case RISK_EVENTS:
                return riskEventRepository.findByMerchantIdOrderByOccurredAtDesc(merchantId).stream()
                        .filter(e -> e.getOccurredAt() != null
                                && !e.getOccurredAt().isBefore(dateFrom)
                                && !e.getOccurredAt().isAfter(dateTo))
                        .collect(Collectors.toList());

            case WEBHOOK_DELIVERIES:
                return webhookDeliveryRepository.findByMerchantId(merchantId,
                        org.springframework.data.domain.PageRequest.of(0, maxRecordsPerExport))
                        .stream()
                        .filter(w -> w.getCreatedAt() != null
                                && !w.getCreatedAt().isBefore(dateFrom.atZone(java.time.ZoneOffset.UTC).toInstant())
                                && !w.getCreatedAt().isAfter(dateTo.atZone(java.time.ZoneOffset.UTC).toInstant()))
                        .collect(Collectors.toList());

            case SETTLEMENTS:
                // 结算数据复用交易数据中 PAID 状态的订单
                return paymentOrderRepository.findByMerchantIdAndStatus(merchantId,
                        PaymentOrder.OrderStatus.PAID).stream()
                        .filter(o -> o.getPaidAt() != null
                                && !o.getPaidAt().isBefore(dateFrom)
                                && !o.getPaidAt().isAfter(dateTo))
                        .collect(Collectors.toList());

            default:
                throw new IllegalArgumentException("不支持的导出类型: " + request.getExportType());
        }
    }

    /**
     * 生成 CSV 格式文件内容。
     */
    private String generateCsvFile(DataExportRequest request, List<?> records) {
        StringBuilder csv = new StringBuilder();

        // 头部注释
        csv.append("# NexusChain Data Export\n");
        csv.append("# Merchant: ").append(request.getMerchantId()).append("\n");
        csv.append("# Type: ").append(request.getExportType()).append("\n");
        csv.append("# Format: CSV\n");
        csv.append("# Period: ").append(request.getDateFrom())
                .append(" to ").append(request.getDateTo()).append("\n");
        csv.append("# Generated: ").append(LocalDateTime.now()).append("\n");

        // 根据导出类型生成列标题和数据行
        switch (request.getExportType()) {
            case TRANSACTIONS:
            case SETTLEMENTS:
                appendTransactionCsv(csv, records);
                break;
            case REFUNDS:
                appendRefundCsv(csv, records);
                break;
            case SPLITS:
                appendSplitCsv(csv, records);
                break;
            case RISK_EVENTS:
                appendRiskEventCsv(csv, records);
                break;
            case WEBHOOK_DELIVERIES:
                appendWebhookDeliveryCsv(csv, records);
                break;
            default:
                throw new IllegalArgumentException("不支持的导出类型: " + request.getExportType());
        }

        return csv.toString();
    }

    /**
     * 生成 JSON 格式文件内容。
     */
    private String generateJsonFile(DataExportRequest request, List<?> records) {
        try {
            String json = objectMapper.writeValueAsString(records);
            // 包装为带元数据的结构
            StringBuilder wrapper = new StringBuilder();
            wrapper.append("{\n");
            wrapper.append("  \"metadata\": {\n");
            wrapper.append("    \"merchantId\": ").append(request.getMerchantId()).append(",\n");
            wrapper.append("    \"exportType\": \"").append(request.getExportType()).append("\",\n");
            wrapper.append("    \"format\": \"JSON\",\n");
            wrapper.append("    \"dateFrom\": \"").append(request.getDateFrom()).append("\",\n");
            wrapper.append("    \"dateTo\": \"").append(request.getDateTo()).append("\",\n");
            wrapper.append("    \"generatedAt\": \"").append(LocalDateTime.now()).append("\",\n");
            wrapper.append("    \"recordCount\": ").append(records.size()).append("\n");
            wrapper.append("  },\n");
            wrapper.append("  \"data\": ");
            wrapper.append(json);
            wrapper.append("\n}\n");
            return wrapper.toString();
        } catch (Exception e) {
            throw new RuntimeException("生成 JSON 文件失败", e);
        }
    }

    /**
     * 存储导出文件到本地文件系统。
     */
    private Path storeExportFile(DataExportRequest request, String fileContent) throws IOException {
        String dirPath = storagePath + "/" + request.getMerchantId();
        Path dir = Paths.get(dirPath);
        Files.createDirectories(dir);

        String fileName = request.getId() + "." + request.getFormat().name().toLowerCase();
        Path filePath = dir.resolve(fileName);
        Files.writeString(filePath, fileContent);

        return filePath;
    }

    // --- CSV 生成辅助方法 ---

    @SuppressWarnings("unchecked")
    private void appendTransactionCsv(StringBuilder csv, List<?> records) {
        csv.append("order_no,amount,currency,status,chain_tx_hash,created_at,paid_at\n");
        for (Object obj : records) {
            PaymentOrder order = (PaymentOrder) obj;
            csv.append(nullSafe(order.getOrderNo())).append(",");
            csv.append(nullSafe(order.getAmount())).append(",");
            csv.append(nullSafe(order.getTokenSymbol())).append(",");
            csv.append(order.getStatus() != null ? order.getStatus().name() : "").append(",");
            csv.append(nullSafe(order.getChainTxHash())).append(",");
            csv.append(nullSafe(order.getCreatedAt())).append(",");
            csv.append(nullSafe(order.getPaidAt())).append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void appendRefundCsv(StringBuilder csv, List<?> records) {
        csv.append("refund_no,order_id,amount,currency,status,reason,created_at,completed_at\n");
        for (Object obj : records) {
            Refund refund = (Refund) obj;
            csv.append(nullSafe(refund.getRefundNo())).append(",");
            csv.append(nullSafe(refund.getOrderId())).append(",");
            csv.append(nullSafe(refund.getAmount())).append(",");
            csv.append(nullSafe(refund.getTokenSymbol())).append(",");
            csv.append(refund.getStatus() != null ? refund.getStatus().name() : "").append(",");
            csv.append(nullSafe(refund.getReason())).append(",");
            csv.append(nullSafe(refund.getCreatedAt())).append(",");
            csv.append(nullSafe(refund.getCompletedAt())).append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void appendSplitCsv(StringBuilder csv, List<?> records) {
        csv.append("order_id,payment_id,receiver_address,amount,split_type,status,created_at,settled_at\n");
        for (Object obj : records) {
            SplitOrder split = (SplitOrder) obj;
            csv.append(nullSafe(split.getOrderId())).append(",");
            csv.append(nullSafe(split.getPaymentId())).append(",");
            csv.append(nullSafe(split.getReceiverAddress())).append(",");
            csv.append(nullSafe(split.getAmount())).append(",");
            csv.append(split.getSplitType() != null ? split.getSplitType().name() : "").append(",");
            csv.append(split.getStatus() != null ? split.getStatus().name() : "").append(",");
            csv.append(nullSafe(split.getCreatedAt())).append(",");
            csv.append(nullSafe(split.getSettledAt())).append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void appendRiskEventCsv(StringBuilder csv, List<?> records) {
        csv.append("event_id,event_type,merchant_id,order_id,amount,risk_decision,risk_score,occurred_at\n");
        for (Object obj : records) {
            RiskEvent event = (RiskEvent) obj;
            csv.append(nullSafe(event.getEventId())).append(",");
            csv.append(event.getEventType() != null ? event.getEventType().name() : "").append(",");
            csv.append(nullSafe(event.getMerchantId())).append(",");
            csv.append(nullSafe(event.getOrderId())).append(",");
            csv.append(nullSafe(event.getAmount())).append(",");
            csv.append(nullSafe(event.getRiskDecision())).append(",");
            csv.append(nullSafe(event.getRiskScore())).append(",");
            csv.append(nullSafe(event.getOccurredAt())).append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void appendWebhookDeliveryCsv(StringBuilder csv, List<?> records) {
        csv.append("delivery_id,payment_id,merchant_id,notify_url,status,attempt_count,last_error,created_at\n");
        for (Object obj : records) {
            WebhookDeliveryRecord delivery = (WebhookDeliveryRecord) obj;
            csv.append(nullSafe(delivery.getDeliveryId())).append(",");
            csv.append(nullSafe(delivery.getPaymentId())).append(",");
            csv.append(nullSafe(delivery.getMerchantId())).append(",");
            csv.append(nullSafe(delivery.getNotifyUrl())).append(",");
            csv.append(delivery.getStatus() != null ? delivery.getStatus().name() : "").append(",");
            csv.append(delivery.getAttemptCount()).append(",");
            csv.append(nullSafe(delivery.getLastError())).append(",");
            csv.append(nullSafe(delivery.getCreatedAt())).append("\n");
        }
    }

    private String nullSafe(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private String truncateMessage(String message, int maxLength) {
        if (message == null) {
            return null;
        }
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength);
    }
}