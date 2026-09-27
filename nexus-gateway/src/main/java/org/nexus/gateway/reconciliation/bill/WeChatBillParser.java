package org.nexus.gateway.reconciliation.bill;

import org.nexus.gateway.reconciliation.ChannelRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 微信支付对账单 CSV 解析器。
 *
 * <p>解析微信支付 V3 对账单 CSV 格式：</p>
 * <ul>
 *   <li>跳过以 {@code #} 开头的汇总行和空行</li>
 *   <li>查找包含"商户订单号"的列标题行，建立列名→索引映射</li>
 *   <li>解析数据行提取 transactionId、amount、status、paidAt、currency</li>
 * </ul>
 */
@Component
public class WeChatBillParser {

    private static final Logger log = LoggerFactory.getLogger(WeChatBillParser.class);

    /**
     * 解析微信对账单 CSV 内容为 ChannelRecord 列表。
     *
     * @param csvContent CSV 格式的对账单内容
     * @return 解析出的渠道记录列表
     */
    public List<ChannelRecord> parse(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return Collections.emptyList();
        }

        List<ChannelRecord> records = new ArrayList<>();
        String[] lines = csvContent.split("\n");
        Map<String, Integer> columnMap = null;
        int failedCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // 跳过空行
            if (line.isEmpty()) {
                continue;
            }

            // 跳过汇总行（# 开头）
            if (line.startsWith("#")) {
                continue;
            }

            // 查找列标题行（包含"商户订单号"）
            if (columnMap == null && line.contains("商户订单号")) {
                columnMap = buildColumnMap(line);
                continue;
            }

            // 解析数据行
            if (columnMap != null) {
                try {
                    ChannelRecord record = parseLine(line, columnMap);
                    if (record != null) {
                        records.add(record);
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.warn("[WeChatBillParser] 解析第 {} 行失败: {}", i + 1, e.getMessage());
                }
            }
        }

        if (failedCount > 0) {
            log.warn("[WeChatBillParser] 共 {} 行解析失败", failedCount);
        }

        log.info("[WeChatBillParser] 解析完成: {} 条记录", records.size());
        return records;
    }

    /**
     * 构建列名→索引映射。
     */
    private Map<String, Integer> buildColumnMap(String headerLine) {
        Map<String, Integer> map = new LinkedHashMap<>();
        String[] headers = headerLine.split(",");
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    /**
     * 解析单行数据为 ChannelRecord。
     */
    private ChannelRecord parseLine(String line, Map<String, Integer> columnMap) {
        String[] fields = line.split(",");
        if (fields.length < columnMap.size()) {
            log.warn("[WeChatBillParser] 字段数不足: expected={}, actual={}", columnMap.size(), fields.length);
            return null;
        }

        ChannelRecord record = new ChannelRecord();

        // 商户订单号 → transactionId
        Integer txIdx = columnMap.get("商户订单号");
        if (txIdx != null && txIdx < fields.length) {
            record.setTransactionId(fields[txIdx].trim());
        }

        // 总金额 → amount
        Integer amtIdx = columnMap.get("总金额");
        if (amtIdx != null && amtIdx < fields.length) {
            try {
                record.setAmount(new BigDecimal(fields[amtIdx].trim()));
            } catch (NumberFormatException e) {
                log.warn("[WeChatBillParser] 金额解析失败: {}", fields[amtIdx]);
            }
        }

        // 交易状态 → status
        Integer statusIdx = columnMap.get("交易状态");
        if (statusIdx != null && statusIdx < fields.length) {
            record.setStatus(fields[statusIdx].trim());
        }

        // 交易时间 → paidAt
        Integer timeIdx = columnMap.get("交易时间");
        if (timeIdx != null && timeIdx < fields.length) {
            record.setPaidAt(parseWeChatTime(fields[timeIdx].trim()));
        }

        // 货币种类 → currency
        Integer curIdx = columnMap.get("货币种类");
        if (curIdx != null && curIdx < fields.length) {
            record.setCurrency(fields[curIdx].trim());
        }

        return record;
    }

    /**
     * 解析微信时间格式（OffsetDateTime 格式，如 2026-09-27T10:00:00+08:00）。
     */
    private LocalDateTime parseWeChatTime(String timeStr) {
        try {
            return OffsetDateTime.parse(timeStr).toLocalDateTime();
        } catch (Exception e) {
            log.warn("[WeChatBillParser] 时间解析失败: {}", timeStr);
            return null;
        }
    }
}