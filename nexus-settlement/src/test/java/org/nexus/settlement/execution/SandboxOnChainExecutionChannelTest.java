package org.nexus.settlement.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SandboxOnChainExecutionChannel} 单元测试。
 */
class SandboxOnChainExecutionChannelTest {

    private SandboxOnChainExecutionChannel channel;

    @BeforeEach
    void setUp() {
        channel = new SandboxOnChainExecutionChannel();
    }

    @Test
    void execute_validRequest_shouldReturnSimulatedSuccess() {
        TransactionRequest req = new TransactionRequest(
                TransactionRequest.Type.SETTLEMENT, "f", "t", BigDecimal.ONE, "X", "m", "r");

        TransactionResult result = channel.execute(req);

        assertTrue(result.isSuccess());
        assertTrue(result.isSimulated());
        assertNotNull(result.getTxHash());
        assertTrue(result.getTxHash().startsWith(SandboxOnChainExecutionChannel.SIMULATED_PREFIX));
    }

    @Test
    void execute_nullRequest_shouldReturnFailure() {
        TransactionResult result = channel.execute(null);

        assertTrue(!result.isSuccess());
        assertTrue(result.isSimulated());
        assertNotNull(result.getError());
    }

    @Test
    void queryStatus_simulatedHash_shouldReturnSuccess() {
        String txHash = SandboxOnChainExecutionChannel.SIMULATED_PREFIX + "abc123";

        TransactionResult result = channel.queryStatus(txHash);

        assertTrue(result.isSuccess());
        assertTrue(result.isSimulated());
        assertEquals(txHash, result.getTxHash());
    }

    @Test
    void queryStatus_realHash_shouldReturnPending() {
        TransactionResult result = channel.queryStatus("0xREALHASH");

        assertEquals(TransactionResult.Status.PENDING_CONFIRMATION, result.getStatus());
        assertTrue(result.isSimulated());
    }

    @Test
    void queryStatus_null_shouldReturnFailure() {
        TransactionResult result = channel.queryStatus(null);

        assertTrue(!result.isSuccess());
        assertTrue(result.isSimulated());
    }

    @Test
    void queryStatus_empty_shouldReturnFailure() {
        TransactionResult result = channel.queryStatus("");

        assertTrue(!result.isSuccess());
        assertTrue(result.isSimulated());
    }
}