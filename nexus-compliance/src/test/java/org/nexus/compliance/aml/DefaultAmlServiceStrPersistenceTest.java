package org.nexus.compliance.aml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STR 持久化测试（TODO v2.0.0 落地）：JSONL 追加 + 启动加载恢复。
 */
class DefaultAmlServiceStrPersistenceTest {

    private DefaultAmlService service;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("nexus-str-test");
        service = new DefaultAmlService(new InMemorySanctionListChecker());
        setField(service, "strDir", tempDir.toString());
        service.initStrStore();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private SuspiciousTransactionReport report(String reason) {
        SuspiciousTransactionReport r = new SuspiciousTransactionReport();
        r.setTransactionDetail("tx-detail");
        r.setSuspiciousReason(reason);
        return r;
    }

    @Test
    void fileReport_persistsToJsonl() throws Exception {
        service.fileSuspiciousReport(report("SANCTIONED"));
        Path file = tempDir.resolve("suspicious-transaction-reports.jsonl");
        assertTrue(Files.exists(file), "STR 应落盘 JSONL");
        String content = Files.readString(file);
        assertTrue(content.contains("SANCTIONED"), "文件应含上报内容");
        assertTrue(content.contains("STR-"), "文件应含报告 ID");
    }

    @Test
    void restart_loadsPersistedStr() throws Exception {
        SuspiciousTransactionReport filed = service.fileSuspiciousReport(report("HIGH-RISK"));
        String reportId = filed.getReportId();

        // 模拟重启：新实例 + 同一目录 + 启动加载
        DefaultAmlService restarted = new DefaultAmlService(new InMemorySanctionListChecker());
        setField(restarted, "strDir", tempDir.toString());
        restarted.initStrStore();

        // 反射读取 filedReports（无 getter——测试专用）
        Field f = DefaultAmlService.class.getDeclaredField("filedReports");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, SuspiciousTransactionReport> loaded =
                (java.util.Map<String, SuspiciousTransactionReport>) f.get(restarted);
        assertTrue(loaded.containsKey(reportId), "重启后应恢复已上报 STR（持久化实证）");
        assertEquals("HIGH-RISK", loaded.get(reportId).getSuspiciousReason());
    }
}
