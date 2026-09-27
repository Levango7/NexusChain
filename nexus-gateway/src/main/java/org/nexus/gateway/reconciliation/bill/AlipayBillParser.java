package org.nexus.gateway.reconciliation.bill;

import org.nexus.gateway.reconciliation.ChannelRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 支付宝对账单 CSV 解析器。
 *
 * <p>解析支付宝对账单 CSV 格式：</p>
 * <ul>
 *   <li>跳过以 {@code #} 开头的汇总行和空行</li>
 *   <li>查找包含"商户订单号"的列标题行，建立列名→索引映射</li>
 *   <li>解析数据行提取 transactionId、amount、status、paidAt</li>
 * </ul>
 *
 * <p>支付宝时间格式为 {@code yyyy-MM-dd HH:mm:ss}。</p>
 */
@Component
public class AlipayBillParser {

    private static final Logger log = LoggerFactory.getLogger(AlipayBillParser.class);

    private static final DateTimeFormatter ALIPAY_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 解析支付宝对账单 CSV 内容为 ChannelRecord 列表。
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

            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("#")) {
                continue;
            }

            if (columnMap == null && line.contains("商户订单号")) {
                columnMap = buildColumnMap(line);
                continue;
            }

            if (columnMap != null) {
                try {
                    ChannelRecord record = parseLine(line, columnMap);
                    if (record != null) {
                        records.add(record);
                    }
                } catch (Exception e) {
                    failedCount++;
                    log.warn("[AlipayBillParser] 解析第 {} 行失败: {}", i + 1, e.getMessage());
                }
            }
        }

        if (failedCount > 0) {
            log.warn("[AlipayBillParser] 共 {} 行解析失败", failedCount);
        }

        log.info("[AlipayBillParser] 解析完成: {} 条记录", records.size());
        return records;
    }

    private Map<String, Integer> buildColumnMap(String headerLine) {
        Map<String, Integer> map = new LinkedHashMap<>();
        String[] headers = headerLine.split(",");
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    private ChannelRecord parseLine(String line, Map<String, Integer> columnMap) {
        String[] fields = line.split(",");
        if (fields.length < columnMap.size()) {
            log.warn("[AlipayBillParser] 字段数不足: expected={}, actual={}", columnMap.size(), fields.length);
            return null;
        }

        ChannelRecord record = new ChannelRecord();

        Integer txIdx = columnMap.get("商户订单号");
        if (txIdx != null && txIdx < fields.length) {
            record.setTransactionId(fields[txIdx].trim());
        }

        Integer amtIdx = columnMap.get("金额");
        if (amtIdx != null && amtIdx < fields.length) {
            try {
                record.setAmount(new BigDecimal(fields[amtIdx].trim()));
            } catch (NumberFormatException e) {
                log.warn("[AlipayBillParser] 金额解析失败: {}", fields[amtIdx]);
            }
        }

        Integer statusIdx = columnMap.get("状态");
        if (statusIdx != null && statusIdx < fields.length) {
            record.setStatus(fields[statusIdx].trim());
        }

        Integer timeIdx = columnMap.get("完成时间");
        if (timeIdx != null && timeIdx < fields.length) {
            record.setPaidAt(parseAlipayTime(fields[timeIdx].trim()));
        }

        return record;
    }

    /**
     * 解析支付宝时间格式（yyyy-MM-dd HH:mm:ss）。
     */
    private LocalDateTime parseAlipayTime(String timeStr) {
        try {
            return LocalDateTime.parse(timeStr, ALIPAY_TIME_FORMATTER);
        } catch (Exception e) {
            log.warn("[AlipayBillParser] 时间解析失败: {}", timeStr);
            return null;
        }
    }
}