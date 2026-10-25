package org.nexus.gateway.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SigningTransfers} 单元测试：requestKey 幂等形态 + 有/无审批执行服务两条路径。
 */
class SigningTransfersTest {

    private static Map<String, Object> resp(Object statusCode, Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", statusCode);
        if (data != null) {
            response.put("data", data);
        }
        return response;
    }

    @Test
    @DisplayName("requestKey: 业务 ID 优先，缺失才用兜底 ID")
    void requestKey_prefersBusinessId() {
        assertEquals("execution:CHAIN:pay_1",
                SigningTransfers.requestKey("CHAIN", "pay_1", "chain_abc"));
        assertEquals("execution:CHAIN:chain_abc",
                SigningTransfers.requestKey("CHAIN", "  ", "chain_abc"));
        assertEquals("execution:SUB_CHARGE:SUB1:2",
                SigningTransfers.requestKey("SUB_CHARGE", "SUB1:2", null));
    }

    @Test
    @DisplayName("有执行服务时委托 signOrAwait，不直接调签名客户端")
    void delegatesToServiceWhenPresent() {
        SigningApprovalExecutionService service = mock(SigningApprovalExecutionService.class);
        SigningServiceFeignClient client = mock(SigningServiceFeignClient.class);
        SigningOutcome expected = new SigningOutcome(
                ApprovalExecutionLink.Status.AWAITING_APPROVAL, "appr-1", null, null);
        when(service.signOrAwait("k", "CHAIN", "pk", "to", BigDecimal.TEN, "NEX")).thenReturn(expected);

        SigningOutcome outcome = SigningTransfers.signOrAwait(service, client,
                "k", "CHAIN", "pk", "to", BigDecimal.TEN, "NEX");

        assertEquals(expected, outcome);
        verify(client, never()).signTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("无执行服务时直接签名：2000 取到哈希")
    void fallbackWithoutService_executed() {
        SigningServiceFeignClient client = mock(SigningServiceFeignClient.class);
        when(client.signTransfer("pk", "to", BigDecimal.TEN)).thenReturn(resp(2000, "0xabc"));

        SigningOutcome outcome = SigningTransfers.signOrAwait(null, client,
                "k", "CHAIN", "pk", "to", BigDecimal.TEN, "NEX");

        assertTrue(outcome.isExecuted());
        assertEquals("0xabc", outcome.txHash());
    }

    @Test
    @DisplayName("无执行服务时遇 2001 fail-closed：报失败但带出 approvalId 供人工处置")
    void fallbackWithoutService_approvalRequiredFailsClosed() {
        SigningServiceFeignClient client = mock(SigningServiceFeignClient.class);
        Map<String, Object> pending = resp(2001, null);
        pending.put("approvalId", "appr-9");
        when(client.signTransfer("pk", "to", BigDecimal.TEN)).thenReturn(pending);

        SigningOutcome outcome = SigningTransfers.signOrAwait(null, client,
                "k", "CHAIN", "pk", "to", BigDecimal.TEN, "NEX");

        assertTrue(outcome.isFailed(), "无执行服务时无法跟踪审批腿，必须 fail-closed");
        assertEquals("appr-9", outcome.approvalId());
    }

    @Test
    @DisplayName("降级（null 响应）归入失败，原因可读")
    void fallbackWithoutService_nullResponseIsFailed() {
        SigningServiceFeignClient client = mock(SigningServiceFeignClient.class);
        when(client.signTransfer("pk", "to", BigDecimal.TEN)).thenReturn(null);

        SigningOutcome outcome = SigningTransfers.signOrAwait(null, client,
                "k", "CHAIN", "pk", "to", BigDecimal.TEN, "NEX");

        assertTrue(outcome.isFailed());
        assertNull(outcome.txHash());
        assertTrue(outcome.error().contains("unavailable"));
    }
}
