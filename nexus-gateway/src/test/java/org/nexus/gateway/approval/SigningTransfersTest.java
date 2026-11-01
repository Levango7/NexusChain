package org.nexus.gateway.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("降级（null 响应）归入结果不明，不落终态失败")
    void fallbackWithoutService_nullResponseIsUnknown() {
        SigningServiceFeignClient client = mock(SigningServiceFeignClient.class);
        when(client.signTransfer("pk", "to", BigDecimal.TEN)).thenReturn(null);

        SigningOutcome outcome = SigningTransfers.signOrAwait(null, client,
                "k", "CHAIN", "pk", "to", BigDecimal.TEN, "NEX");

        // 2026-10-31 fail-closed 收敛：降级/读超时都可能发生在广播之后，
        // 记 FAILED 会让调用方安全重放 ⇒ 与可能已上链的那笔构成二次出款。
        assertFalse(outcome.isFailed(), "null 响应不得当「确定未动账」");
        assertTrue(outcome.requiresManualVerification());
        assertNull(outcome.txHash());
        assertTrue(outcome.error().contains("statusCode=null"), "实得：" + outcome.error());
    }

    @Test
    @DisplayName("业务失败 5000 仍落终态失败（白名单不变）")
    void fallbackWithoutService_businessFail5000StaysFailed() {
        SigningServiceFeignClient client = mock(SigningServiceFeignClient.class);
        when(client.signTransfer("pk", "to", BigDecimal.TEN)).thenReturn(resp(5000, null));

        SigningOutcome outcome = SigningTransfers.signOrAwait(null, client,
                "k", "CHAIN", "pk", "to", BigDecimal.TEN, "NEX");

        assertTrue(outcome.isFailed());
        assertFalse(outcome.requiresManualVerification());
    }

    @Test
    @DisplayName("兜底异常码 5002 归入结果不明（与有服务那条腿同口径）")
    void fallbackWithoutService_internalError5002IsUnknown() {
        SigningServiceFeignClient client = mock(SigningServiceFeignClient.class);
        Map<String, Object> envelope = resp(5002, null);
        envelope.put("message", "Internal error");
        when(client.signTransfer("pk", "to", BigDecimal.TEN)).thenReturn(envelope);

        SigningOutcome outcome = SigningTransfers.signOrAwait(null, client,
                "k", "CHAIN", "pk", "to", BigDecimal.TEN, "NEX");

        assertFalse(outcome.isFailed());
        assertTrue(outcome.requiresManualVerification());
        assertTrue(outcome.error().contains("5002"), "实得：" + outcome.error());
    }
}
