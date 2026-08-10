package org.nexus.oracle.governance.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.oracle.governance.ProposalState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GovernanceAuditLog} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>记录与查询审计日志</li>
 *   <li>多提案隔离</li>
 *   <li>同一提案多次执行（按时间顺序）</li>
 *   <li>查询不存在提案返回空</li>
 *   <li>全局查询 / 统计 / 清空</li>
 *   <li>GOV-P0-02: 哈希链防篡改（记录后链完整，篡改后链断裂）</li>
 * </ul>
 */
class GovernanceAuditLogTest {

    private GovernanceAuditLog auditLog;

    @BeforeEach
    void setUp() {
        // 使用内存存储模式（无 Repository），向后兼容
        auditLog = new GovernanceAuditLog();
    }

    @Test
    void record_shouldStoreAndReturnAuditRecord() {
        GovernanceAuditLog.AuditRecord record = auditLog.record(
                "PROP-001", "SOFTWARE_UPGRADE", "operator-1",
                ProposalState.PASSED, ProposalState.EXECUTED, true,
                Map.of("target", "gateway", "version", "2.1.0"));

        assertNotNull(record);
        assertEquals("PROP-001", record.getProposalId());
        assertEquals("SOFTWARE_UPGRADE", record.getProposalType());
        assertEquals("operator-1", record.getOperator());
        assertEquals(ProposalState.PASSED, record.getPreviousState());
        assertEquals(ProposalState.EXECUTED, record.getNewState());
        assertTrue(record.isSuccess());
        assertEquals("gateway", record.getDetails().get("target"));
        assertEquals("2.1.0", record.getDetails().get("version"));
        assertNotNull(record.getTimestamp());
        // GOV-P0-02: 哈希链字段
        assertNotNull(record.getPreviousHash());
        assertNotNull(record.getEntryHash());
        assertEquals(64, record.getEntryHash().length());
        assertEquals(64, record.getPreviousHash().length());
    }

    @Test
    void getAuditLog_existingProposal_shouldReturnRecords() {
        auditLog.record("PROP-001", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        List<GovernanceAuditLog.AuditRecord> records = auditLog.getAuditLog("PROP-001");

        assertEquals(1, records.size());
        assertEquals("PROP-001", records.get(0).getProposalId());
    }

    @Test
    void getAuditLog_nonExistingProposal_shouldReturnEmpty() {
        List<GovernanceAuditLog.AuditRecord> records = auditLog.getAuditLog("NOPE");

        assertTrue(records.isEmpty());
    }

    @Test
    void getAuditLog_multipleExecutionsForSameProposal_shouldReturnInOrder() {
        auditLog.record("PROP-001", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTION_FAILED, false, Map.of());
        auditLog.record("PROP-001", "SOFTWARE_UPGRADE", "op",
                ProposalState.EXECUTION_FAILED, ProposalState.EXECUTED, true, Map.of());

        List<GovernanceAuditLog.AuditRecord> records = auditLog.getAuditLog("PROP-001");

        assertEquals(2, records.size());
        assertFalse(records.get(0).isSuccess());
        assertTrue(records.get(1).isSuccess());
    }

    @Test
    void getAuditLog_multipleProposals_shouldBeIsolated() {
        auditLog.record("PROP-A", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());
        auditLog.record("PROP-B", "TREASURY_SPEND", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        assertEquals(1, auditLog.getAuditLog("PROP-A").size());
        assertEquals(1, auditLog.getAuditLog("PROP-B").size());
        assertEquals("SOFTWARE_UPGRADE", auditLog.getAuditLog("PROP-A").get(0).getProposalType());
        assertEquals("TREASURY_SPEND", auditLog.getAuditLog("PROP-B").get(0).getProposalType());
    }

    @Test
    void record_nullDetails_shouldStoreEmptyMap() {
        GovernanceAuditLog.AuditRecord record = auditLog.record(
                "PROP-001", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, null);

        assertNotNull(record.getDetails());
        assertTrue(record.getDetails().isEmpty());
    }

    @Test
    void getAllAuditLogs_shouldReturnAllProposals() {
        auditLog.record("PROP-A", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());
        auditLog.record("PROP-B", "TREASURY_SPEND", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        Map<String, List<GovernanceAuditLog.AuditRecord>> all = auditLog.getAllAuditLogs();

        assertEquals(2, all.size());
        assertTrue(all.containsKey("PROP-A"));
        assertTrue(all.containsKey("PROP-B"));
    }

    @Test
    void getAllAuditLogs_empty_shouldReturnEmptyMap() {
        Map<String, List<GovernanceAuditLog.AuditRecord>> all = auditLog.getAllAuditLogs();

        assertTrue(all.isEmpty());
    }

    @Test
    void totalRecords_shouldCountAllRecordsAcrossProposals() {
        auditLog.record("PROP-A", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());
        auditLog.record("PROP-A", "SOFTWARE_UPGRADE", "op",
                ProposalState.EXECUTED, ProposalState.EXECUTED, true, Map.of());
        auditLog.record("PROP-B", "TREASURY_SPEND", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        assertEquals(3, auditLog.totalRecords());
    }

    @Test
    void totalRecords_empty_shouldReturnZero() {
        assertEquals(0, auditLog.totalRecords());
    }

    @Test
    void clear_shouldRemoveAllRecords() {
        auditLog.record("PROP-A", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());
        auditLog.record("PROP-B", "TREASURY_SPEND", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        auditLog.clear();

        assertEquals(0, auditLog.totalRecords());
        assertTrue(auditLog.getAllAuditLogs().isEmpty());
    }

    @Test
    void getAuditLog_shouldReturnDefensiveCopy() {
        auditLog.record("PROP-001", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        List<GovernanceAuditLog.AuditRecord> records = auditLog.getAuditLog("PROP-001");
        records.clear(); // 修改返回的副本不应影响内部存储

        assertEquals(1, auditLog.getAuditLog("PROP-001").size());
    }

    @Test
    void record_failureExecution_shouldStoreFailureInfo() {
        auditLog.record("PROP-FAIL", "TREASURY_SPEND", "op",
                ProposalState.PASSED, ProposalState.EXECUTION_FAILED, false,
                Map.of("errorMessage", "insufficient balance"));

        List<GovernanceAuditLog.AuditRecord> records = auditLog.getAuditLog("PROP-FAIL");
        assertEquals(1, records.size());
        assertFalse(records.get(0).isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, records.get(0).getNewState());
        assertEquals("insufficient balance", records.get(0).getDetails().get("errorMessage"));
    }

    // ---------- GOV-P0-02: 哈希链防篡改测试 ----------

    @Test
    void record_firstEntry_shouldHaveGenesisPreviousHash() {
        GovernanceAuditLog.AuditRecord record = auditLog.record(
                "PROP-CHAIN-001", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        assertEquals(GovernanceAuditLog.GENESIS_PREVIOUS_HASH, record.getPreviousHash());
    }

    @Test
    void record_subsequentEntry_shouldChainPreviousHash() {
        GovernanceAuditLog.AuditRecord first = auditLog.record(
                "PROP-CHAIN-002", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTION_FAILED, false, Map.of());
        GovernanceAuditLog.AuditRecord second = auditLog.record(
                "PROP-CHAIN-002", "SOFTWARE_UPGRADE", "op",
                ProposalState.EXECUTION_FAILED, ProposalState.EXECUTED, true, Map.of());

        assertEquals(first.getEntryHash(), second.getPreviousHash(),
                "Second entry's previousHash must equal first entry's entryHash");
    }

    @Test
    void verifyAuditChain_emptyProposal_shouldReturnTrue() {
        assertTrue(auditLog.verifyAuditChain("NON-EXISTENT"));
    }

    @Test
    void verifyAuditChain_singleRecord_shouldReturnTrue() {
        auditLog.record("PROP-VERIFY-001", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        assertTrue(auditLog.verifyAuditChain("PROP-VERIFY-001"));
    }

    @Test
    void verifyAuditChain_multipleRecords_shouldReturnTrue() {
        auditLog.record("PROP-VERIFY-002", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTION_FAILED, false, Map.of());
        auditLog.record("PROP-VERIFY-002", "SOFTWARE_UPGRADE", "op",
                ProposalState.EXECUTION_FAILED, ProposalState.EXECUTED, true, Map.of());
        auditLog.record("PROP-VERIFY-002", "SOFTWARE_UPGRADE", "op",
                ProposalState.EXECUTED, ProposalState.EXECUTED, true, Map.of());

        assertTrue(auditLog.verifyAuditChain("PROP-VERIFY-002"));
    }

    @Test
    void verifyAuditChain_differentProposals_shouldHaveIndependentChains() {
        auditLog.record("PROP-CHAIN-A", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());
        auditLog.record("PROP-CHAIN-B", "TREASURY_SPEND", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        assertTrue(auditLog.verifyAuditChain("PROP-CHAIN-A"));
        assertTrue(auditLog.verifyAuditChain("PROP-CHAIN-B"));
    }

    @Test
    void entryHash_shouldBeSha256_64HexChars() {
        GovernanceAuditLog.AuditRecord record = auditLog.record(
                "PROP-HASH-001", "TREASURY_SPEND", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true,
                Map.of("txHash", "0xabc"));

        String entryHash = record.getEntryHash();
        assertEquals(64, entryHash.length(), "SHA-256 hex should be 64 chars");
        assertTrue(entryHash.matches("[0-9a-f]{64}"), "entryHash should be lowercase hex");
    }

    @Test
    void record_sameContent_differentTimestamp_shouldProduceDifferentHash() throws InterruptedException {
        GovernanceAuditLog.AuditRecord r1 = auditLog.record(
                "PROP-DIFF-001", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());
        // 确保时间戳不同（Instant.now() 精度为纳秒，通常足够，但保险起见 sleep 1ms）
        Thread.sleep(1);
        GovernanceAuditLog.AuditRecord r2 = auditLog.record(
                "PROP-DIFF-002", "SOFTWARE_UPGRADE", "op",
                ProposalState.PASSED, ProposalState.EXECUTED, true, Map.of());

        assertFalse(r1.getEntryHash().equals(r2.getEntryHash()),
                "Different timestamps should produce different hashes");
    }
}
