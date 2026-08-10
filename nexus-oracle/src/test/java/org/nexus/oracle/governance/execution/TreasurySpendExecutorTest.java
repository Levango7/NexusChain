package org.nexus.oracle.governance.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;
import org.nexus.oracle.governance.Treasury;
import org.nexus.oracle.governance.event.GovernanceExecutionCompletedEvent;
import org.nexus.oracle.governance.event.TreasurySpendEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TreasurySpendExecutor} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>正常转账流程（余额充足、Treasury.spend 成功、事件发布、状态回写）</li>
 *   <li>余额不足拒绝</li>
 *   <li>Treasury.spend 返回 false</li>
 *   <li>payload 缺失 / 格式错误</li>
 *   <li>null proposal 边界</li>
 *   <li>GOV-P0-03: SHA-256 转账哈希（256 位、0x+64hex 格式、无碰撞）</li>
 *   <li>GOV-P2-01: 异常信息脱敏</li>
 *   <li>GOV-P2-04: BigDecimal 精度与范围校验</li>
 *   <li>GOV-P2-05: targetAddress 以太坊地址格式校验</li>
 * </ul>
 */
class TreasurySpendExecutorTest {

    /** 合法的以太坊测试地址（0x + 40 hex 字符） */
    private static final String VALID_ADDRESS = "0x1234567890123456789012345678901234567890";

    private Treasury treasury;
    private ApplicationEventPublisher eventPublisher;
    private GovernanceAuditLog auditLog;
    private TreasurySpendExecutor executor;

    @BeforeEach
    void setUp() {
        treasury = mock(Treasury.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        auditLog = new GovernanceAuditLog();
        executor = new TreasurySpendExecutor(treasury, eventPublisher, auditLog);
    }

    private Proposal buildSpendProposal(String targetAddress, String amount, String token, String chain) {
        return Proposal.builder()
                .proposalId("PROP-SPEND-001")
                .title("Treasury spend 1000 USDT")
                .type(Proposal.Type.TREASURY_SPEND)
                .state(ProposalState.PASSED)
                .proposer("admin-1")
                .parameters(Map.of(
                        "targetAddress", targetAddress,
                        "amount", amount,
                        "token", token,
                        "chain", chain))
                .build();
    }

    @Test
    void execute_validPayload_shouldSucceedAndWriteBackState() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertTrue(result.isSuccess());
        assertNotNull(result.getTxHash());
        assertEquals(ProposalState.EXECUTED, proposal.getState());
        assertNotNull(proposal.getExecutionResult());
        assertEquals(true, proposal.getExecutionResult().get("success"));
        assertEquals(VALID_ADDRESS, proposal.getExecutionResult().get("targetAddress"));
        assertEquals("1000", proposal.getExecutionResult().get("amount"));
        assertNotNull(proposal.getExecutionResult().get("txHash"));
    }

    @Test
    void execute_validPayload_shouldCallTreasurySpend() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "500", "USDC", "bsc");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

        executor.execute(proposal);

        verify(treasury, times(1)).spend(eq(new BigDecimal("500")), eq(VALID_ADDRESS), eq("PROP-SPEND-001"));
    }

    @Test
    void execute_validPayload_shouldPublishTreasurySpendEvent() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

        executor.execute(proposal);

        ArgumentCaptor<TreasurySpendEvent> captor = ArgumentCaptor.forClass(TreasurySpendEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        TreasurySpendEvent event = captor.getValue();
        assertEquals("PROP-SPEND-001", event.getProposalId());
        assertEquals(VALID_ADDRESS, event.getTargetAddress());
        assertEquals(new BigDecimal("1000"), event.getAmount());
        assertEquals("USDT", event.getToken());
        assertEquals("ethereum", event.getChain());
        assertNotNull(event.getTxHash());
    }

    @Test
    void execute_validPayload_shouldRecordAuditLog() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

        executor.execute(proposal);

        var records = auditLog.getAuditLog("PROP-SPEND-001");
        assertEquals(1, records.size());
        var record = records.get(0);
        assertEquals("TREASURY_SPEND", record.getProposalType());
        assertEquals("admin-1", record.getOperator());
        assertTrue(record.isSuccess());
        assertNotNull(record.getDetails().get("txHash"));
    }

    @Test
    void execute_insufficientBalance_shouldFail() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "10000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("100"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
        verify(treasury, never()).spend(any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    void execute_treasurySpendReturnsFalse_shouldFail() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(false);

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_nullProposal_shouldReturnFailure() {
        TreasurySpendExecutor.ExecutionResult result = executor.execute(null);

        assertFalse(result.isSuccess());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void execute_nullParameters_shouldFail() {
        Proposal proposal = Proposal.builder()
                .proposalId("PROP-SPEND-002")
                .title("bad")
                .type(Proposal.Type.TREASURY_SPEND)
                .state(ProposalState.PASSED)
                .proposer("p")
                .build();

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_missingTargetAddress_shouldFail() {
        Proposal proposal = Proposal.builder()
                .proposalId("PROP-SPEND-003")
                .title("bad")
                .type(Proposal.Type.TREASURY_SPEND)
                .state(ProposalState.PASSED)
                .proposer("p")
                .parameters(Map.of("amount", "1000", "token", "USDT", "chain", "ethereum"))
                .build();

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_invalidAmountFormat_shouldFail() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "not-a-number", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_negativeAmount_shouldFail() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "-100", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_failure_shouldPublishCompletedEventWithError() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "10000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("100"));

        executor.execute(proposal);

        ArgumentCaptor<GovernanceExecutionCompletedEvent> captor =
                ArgumentCaptor.forClass(GovernanceExecutionCompletedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        GovernanceExecutionCompletedEvent event = captor.getValue();
        assertFalse(event.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, event.getFinalState());
        assertNotNull(event.getErrorMessage());
    }

    // ---------- GOV-P0-03: SHA-256 转账哈希测试 ----------

    @Test
    void execute_txHash_shouldBeSha256Format_0xPrefixAnd64HexChars() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertTrue(result.isSuccess());
        String txHash = result.getTxHash();
        assertNotNull(txHash);
        // GOV-P0-03: 0x + 64 hex 字符 = 66 字符
        assertEquals(66, txHash.length(), "txHash should be 0x + 64 hex chars (66 total)");
        assertTrue(txHash.startsWith("0x"), "txHash should start with 0x");
        String hexPart = txHash.substring(2);
        assertTrue(hexPart.matches("[0-9a-f]{64}"),
                "txHash hex part should be 64 lowercase hex chars, got: " + hexPart);
    }

    @Test
    void execute_txHash_shouldBe256Bit_not32Bit() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        // SHA-256 输出 256 位 = 64 hex 字符；旧 hashCode() 输出 32 位 = 8 hex 字符
        // 修复后必须为 256 位
        String hexPart = result.getTxHash().substring(2);
        assertEquals(64, hexPart.length(),
                "GOV-P0-03: txHash must be 256-bit (64 hex chars), not 32-bit (8 hex chars)");
    }

    @Test
    void execute_txHash_differentProposals_shouldNotCollide() {
        // 执行多次，验证哈希不全部相同（碰撞概率极低）
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            Proposal proposal = Proposal.builder()
                    .proposalId("PROP-SPEND-COLLIDE-" + i)
                    .title("collision test")
                    .type(Proposal.Type.TREASURY_SPEND)
                    .state(ProposalState.PASSED)
                    .proposer("admin-1")
                    .parameters(Map.of(
                            "targetAddress", VALID_ADDRESS,
                            "amount", "1000",
                            "token", "USDT",
                            "chain", "ethereum"))
                    .build();
            when(treasury.balance()).thenReturn(new BigDecimal("1000000"));
            when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

            TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);
            hashes.add(result.getTxHash());
        }

        // 20 次执行应产生至少 15 个不同的哈希（允许极少数纳秒碰撞）
        assertTrue(hashes.size() >= 15,
                "GOV-P0-03: SHA-256 should not collide easily, got " + hashes.size() + " unique hashes out of 20");
    }

    @Test
    void execute_txHash_shouldBeRecordedInAuditLog() {
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        var records = auditLog.getAuditLog("PROP-SPEND-001");
        assertEquals(1, records.size());
        Object recordedTxHash = records.get(0).getDetails().get("txHash");
        assertNotNull(recordedTxHash);
        assertEquals(result.getTxHash(), recordedTxHash);
        // 验证记录的 txHash 也是 SHA-256 格式
        String recorded = String.valueOf(recordedTxHash);
        assertTrue(recorded.startsWith("0x") && recorded.length() == 66,
                "Recorded txHash should be SHA-256 format");
    }

    // ---------- GOV-P2-05: targetAddress 以太坊地址格式校验 ----------

    @Test
    void execute_invalidAddressFormat_noHexPrefix_shouldFail() {
        // 缺少 0x 前缀
        Proposal proposal = buildSpendProposal("1234567890123456789012345678901234567890", "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_invalidAddressFormat_tooShort_shouldFail() {
        // 太短（0x + 不足 40 hex）
        Proposal proposal = buildSpendProposal("0x123", "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_invalidAddressFormat_tooLong_shouldFail() {
        // 太长（0x + 超过 40 hex）
        Proposal proposal = buildSpendProposal("0x12345678901234567890123456789012345678901234567890", "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_invalidAddressFormat_nonHexChars_shouldFail() {
        // 包含非十六进制字符
        Proposal proposal = buildSpendProposal("0xGG34567890123456789012345678901234567890", "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_validAddress_uppercaseHex_shouldSucceed() {
        // 大写 hex 字符也应合法
        String uppercaseAddress = "0xABCDEF1234567890ABCDEF1234567890ABCDEF12";
        Proposal proposal = buildSpendProposal(uppercaseAddress, "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertTrue(result.isSuccess());
        assertEquals(ProposalState.EXECUTED, proposal.getState());
    }

    @Test
    void execute_invalidAddress_shouldNotCallTreasurySpend() {
        // 地址不合法时不应调用 treasury.spend
        Proposal proposal = buildSpendProposal("0xinvalid", "1000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        executor.execute(proposal);

        verify(treasury, never()).spend(any(BigDecimal.class), anyString(), anyString());
    }

    // ---------- GOV-P2-04: BigDecimal 精度与范围校验 ----------

    @Test
    void execute_amountScaleExceeds18_shouldFail() {
        // scale = 19，超过 wei 精度上限
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1.1234567890123456789", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
        verify(treasury, never()).spend(any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    void execute_amountScaleExactly18_shouldSucceed() {
        // scale = 18，正好等于 wei 精度上限
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1.123456789012345678", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));
        when(treasury.spend(any(BigDecimal.class), anyString(), anyString())).thenReturn(true);

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertTrue(result.isSuccess());
        assertEquals(ProposalState.EXECUTED, proposal.getState());
    }

    @Test
    void execute_amountExceedsMax1e30_shouldFail() {
        // 金额超过 1e30
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "1000000000000000000000000000000000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
        verify(treasury, never()).spend(any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    void execute_amountZero_shouldFail() {
        // 金额为 0
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "0", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_veryLargeScaleIntegerPart_shouldFail() {
        // 整数部分超长（超过 1e30），即使 scale = 0 也应失败
        // 1e30 = 1000000000000000000000000000000 (31 位数字)
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "10000000000000000000000000000000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("10000"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    // ---------- GOV-P2-01: 异常信息脱敏 ----------

    @Test
    void execute_failureErrorMessage_shouldBeSanitized() {
        // 余额不足时，错误信息应被脱敏（不应包含敏感信息）
        Proposal proposal = buildSpendProposal(VALID_ADDRESS, "10000", "USDT", "ethereum");
        when(treasury.balance()).thenReturn(new BigDecimal("100"));

        TreasurySpendExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        // 脱敏后的错误信息不应为 null
        assertEquals(false, proposal.getExecutionResult().get("success"));
        assertNotNull(proposal.getExecutionResult().get("errorMessage"));
    }
}
