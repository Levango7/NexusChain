package org.nexus.gateway.reconciliation;

import org.nexus.gateway.model.PaymentOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 对账逻辑引擎。
 *
 * <p>自动比对渠道对账文件与内部交易记录，按交易ID精确匹配，
 * 金额容差可配置（默认 0.01 元）。比对维度包括：交易ID、金额、状态、时间。</p>
 *
 * <p>比对策略：
 * <ul>
 *   <li>按交易ID（orderNo）精确匹配</li>
 *   <li>金额容差范围内视为匹配（默认 0.01）</li>
 *   <li>超出容差的金额差异 → AMOUNT_MISMATCH</li>
 *   <li>状态不同 → STATUS_MISMATCH</li>
 *   <li>其他信息不同 → INFO_MISMATCH</li>
 *   <li>渠道有但内部无 → LONG_AMOUNT（长款）</li>
 *   <li>内部有但渠道无 → SHORT_AMOUNT（短款）</li>
 * </ul>
 * </p>
 *
 * <p>输出 {@link ReconciliationDiffReport}，包含匹配数、不匹配数、缺失数、多余数
 * 及所有差异明细列表。</p>
 */
@Service
public class ReconciliationEngine {

    /** 金额容差（默认 0.01 元） */
    @Value("${reconciliation.amount.tolerance:0.01}")
    private BigDecimal amountTolerance;

    /**
     * 执行对账比对。
     *
     * @param merchantId      商户 ID
     * @param channelRecords  渠道对账文件中的交易记录列表
     * @param internalOrders  内部交易记录列表（从 PaymentOrderRepository 查询）
     * @param reconciliationFileId 关联的对账文件记录 ID（可为 null）
     * @return 对账差异报告
     */
    public ReconciliationDiffReport reconcile(Long merchantId,
                                              List<ChannelRecord> channelRecords,
                                              List<PaymentOrder> internalOrders,
                                              Long reconciliationFileId) {
        ReconciliationDiffReport report = new ReconciliationDiffReport();
        report.setReconciledAt(LocalDateTime.now());
        report.setTotalInternal(internalOrders != null ? internalOrders.size() : 0);
        report.setTotalChannel(channelRecords != null ? channelRecords.size() : 0);

        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        long matchedCount = 0;
        long mismatchedCount = 0;
        long missingCount = 0;
        long extraCount = 0;
        BigDecimal totalDiscrepancyAmount = BigDecimal.ZERO;

        // 构建内部交易按 orderNo 的索引
        Map<String, PaymentOrder> internalMap = new HashMap<>();
        if (internalOrders != null) {
            for (PaymentOrder order : internalOrders) {
                if (order.getOrderNo() != null) {
                    internalMap.put(order.getOrderNo(), order);
                }
            }
        }

        // 构建渠道交易按 transactionId 的索引
        Map<String, ChannelRecord> channelMap = new HashMap<>();
        Set<String> matchedTransactionIds = new HashSet<>();
        if (channelRecords != null) {
            for (ChannelRecord record : channelRecords) {
                if (record.getTransactionId() != null) {
                    channelMap.put(record.getTransactionId(), record);
                }
            }
        }

        // 第一轮：遍历渠道记录，与内部记录比对
        if (channelRecords != null) {
            for (ChannelRecord channelRecord : channelRecords) {
                String txnId = channelRecord.getTransactionId();
                if (txnId == null) {
                    continue;
                }

                PaymentOrder internalOrder = internalMap.get(txnId);
                if (internalOrder == null) {
                    // 渠道有但内部无 → 长款
                    extraCount++;
                    ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                            merchantId, reconciliationFileId,
                            ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT,
                            txnId, channelRecord, null);
                    discrepancies.add(discrepancy);
                    if (channelRecord.getAmount() != null) {
                        totalDiscrepancyAmount = totalDiscrepancyAmount.add(channelRecord.getAmount());
                    }
                } else {
                    matchedTransactionIds.add(txnId);
                    // 双方都有，比对金额、状态、信息
                    ReconciliationDiscrepancy discrepancy = compareMatched(
                            merchantId, reconciliationFileId, channelRecord, internalOrder);
                    if (discrepancy == null) {
                        matchedCount++;
                    } else {
                        mismatchedCount++;
                        discrepancies.add(discrepancy);
                        if (discrepancy.getAmountDiff() != null) {
                            totalDiscrepancyAmount = totalDiscrepancyAmount.add(discrepancy.getAmountDiff());
                        }
                    }
                }
            }
        }

        // 第二轮：遍历内部记录，找出渠道对账文件中缺失的
        if (internalOrders != null) {
            for (PaymentOrder order : internalOrders) {
                String orderNo = order.getOrderNo();
                if (orderNo == null || matchedTransactionIds.contains(orderNo)) {
                    continue;
                }
                // 内部有但渠道无 → 短款
                missingCount++;
                ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                        merchantId, reconciliationFileId,
                        ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT,
                        orderNo, null, order);
                discrepancies.add(discrepancy);
                if (order.getAmount() != null) {
                    totalDiscrepancyAmount = totalDiscrepancyAmount.add(order.getAmount());
                }
            }
        }

        report.setMatchedCount(matchedCount);
        report.setMismatchedCount(mismatchedCount);
        report.setMissingCount(missingCount);
        report.setExtraCount(extraCount);
        report.setDiscrepancies(discrepancies);
        report.setTotalDiscrepancyAmount(totalDiscrepancyAmount);

        return report;
    }

    /**
     * 比对双方都存在的交易记录，判断金额/状态/信息是否一致。
     *
     * @return null 表示完全匹配，否则返回差异记录
     */
    private ReconciliationDiscrepancy compareMatched(Long merchantId,
                                                     Long reconciliationFileId,
                                                     ChannelRecord channelRecord,
                                                     PaymentOrder internalOrder) {
        String txnId = channelRecord.getTransactionId();

        BigDecimal channelAmount = channelRecord.getAmount();
        BigDecimal internalAmount = internalOrder.getAmount();

        // 金额比对（容差范围内视为匹配）
        boolean amountMismatch = false;
        BigDecimal amountDiff = BigDecimal.ZERO;
        if (channelAmount != null && internalAmount != null) {
            amountDiff = channelAmount.subtract(internalAmount).abs();
            if (amountDiff.compareTo(amountTolerance) > 0) {
                amountMismatch = true;
            }
        } else if (channelAmount != null || internalAmount != null) {
            amountMismatch = true;
            amountDiff = channelAmount != null ? channelAmount : internalAmount;
        }

        // 状态比对
        String channelStatus = channelRecord.getStatus();
        String internalStatus = internalOrder.getStatus() != null
                ? internalOrder.getStatus().name() : null;
        boolean statusMismatch = !Objects.equals(channelStatus, internalStatus);

        // 如果金额和状态都匹配，视为完全匹配
        if (!amountMismatch && !statusMismatch) {
            return null;
        }

        // 确定差异类型：金额不一致优先，其次状态不一致
        ReconciliationDiscrepancy.DiscrepancyType type;
        String description;

        if (amountMismatch) {
            type = ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH;
            description = "Amount mismatch: channel=" + channelAmount
                    + ", internal=" + internalAmount
                    + ", diff=" + amountDiff;
        } else {
            type = ReconciliationDiscrepancy.DiscrepancyType.STATUS_MISMATCH;
            description = "Status mismatch: channel=" + channelStatus
                    + ", internal=" + internalStatus;
        }

        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setMerchantId(merchantId);
        discrepancy.setReconciliationFileId(reconciliationFileId);
        discrepancy.setDiscrepancyType(type);
        discrepancy.setTransactionId(txnId);
        discrepancy.setChannelAmount(channelAmount);
        discrepancy.setInternalAmount(internalAmount);
        discrepancy.setAmountDiff(amountDiff);
        discrepancy.setChannelStatus(channelStatus);
        discrepancy.setInternalStatus(internalStatus);
        discrepancy.setDescription(description);
        discrepancy.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        // 处置规则：金额容差范围内自动解决，否则人工审核
        if (amountMismatch && amountDiff.compareTo(amountTolerance) <= 0) {
            discrepancy.setResolutionType(ReconciliationDiscrepancy.ResolutionType.AUTO_RESOLVE);
        } else {
            discrepancy.setResolutionType(ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW);
        }

        return discrepancy;
    }

    /**
     * 创建长款或短款差异记录。
     */
    private ReconciliationDiscrepancy createDiscrepancy(Long merchantId,
                                                        Long reconciliationFileId,
                                                        ReconciliationDiscrepancy.DiscrepancyType type,
                                                        String transactionId,
                                                        ChannelRecord channelRecord,
                                                        PaymentOrder internalOrder) {
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setMerchantId(merchantId);
        discrepancy.setReconciliationFileId(reconciliationFileId);
        discrepancy.setDiscrepancyType(type);
        discrepancy.setTransactionId(transactionId);
        discrepancy.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        if (type == ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT) {
            // 长款：渠道有但内部无
            discrepancy.setChannelAmount(channelRecord != null ? channelRecord.getAmount() : null);
            discrepancy.setChannelStatus(channelRecord != null ? channelRecord.getStatus() : null);
            discrepancy.setInternalAmount(null);
            discrepancy.setInternalStatus(null);
            discrepancy.setAmountDiff(channelRecord != null ? channelRecord.getAmount() : null);
            discrepancy.setDescription("Long amount: channel has record but internal missing for txn "
                    + transactionId);
            discrepancy.setResolutionType(ReconciliationDiscrepancy.ResolutionType.PENDING_INVESTIGATION);
        } else {
            // 短款：内部有但渠道无
            discrepancy.setInternalAmount(internalOrder != null ? internalOrder.getAmount() : null);
            discrepancy.setInternalStatus(internalOrder != null && internalOrder.getStatus() != null
                    ? internalOrder.getStatus().name() : null);
            discrepancy.setChannelAmount(null);
            discrepancy.setChannelStatus(null);
            discrepancy.setAmountDiff(internalOrder != null ? internalOrder.getAmount() : null);
            discrepancy.setDescription("Short amount: internal has record but channel missing for txn "
                    + transactionId);
            discrepancy.setResolutionType(ReconciliationDiscrepancy.ResolutionType.PENDING_INVESTIGATION);
        }

        return discrepancy;
    }

    /**
     * 解析 CSV 格式的渠道对账文件内容为 ChannelRecord 列表。
     *
     * <p>CSV 格式与 {@link ReconciliationFileService#generateCsvContent} 生成的格式一致：
     * 注释行以 # 开头，列标题行，数据行。</p>
     *
     * @param csvContent CSV 文件内容
     * @return 渠道记录列表
     */
    public List<ChannelRecord> parseCsvContent(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return Collections.emptyList();
        }

        List<ChannelRecord> records = new ArrayList<>();
        String[] lines = csvContent.split("\n");
        boolean dataStarted = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            // 跳过列标题行
            if (!dataStarted) {
                if (line.startsWith("order_no")) {
                    dataStarted = true;
                }
                continue;
            }

            String[] fields = line.split(",");
            if (fields.length < 4) {
                continue;
            }

            ChannelRecord record = new ChannelRecord();
            record.setTransactionId(fields[0].trim());
            try {
                record.setAmount(new BigDecimal(fields[1].trim()));
            } catch (NumberFormatException e) {
                record.setAmount(null);
            }
            record.setCurrency(fields.length > 2 ? fields[2].trim() : null);
            record.setStatus(fields.length > 3 ? fields[3].trim() : null);
            record.setConnectorId(fields.length > 4 ? fields[4].trim() : null);

            records.add(record);
        }

        return records;
    }

    /**
     * 获取当前金额容差配置。
     */
    public BigDecimal getAmountTolerance() {
        return amountTolerance;
    }

    /**
     * 设置金额容差（主要用于测试）。
     */
    public void setAmountTolerance(BigDecimal amountTolerance) {
        this.amountTolerance = amountTolerance;
    }
}