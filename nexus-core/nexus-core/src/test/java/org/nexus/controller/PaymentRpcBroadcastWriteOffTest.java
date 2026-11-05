package org.nexus.controller;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /rpc/v1/transaction/broadcast} 写端点收口测试。
 *
 * <p>支付扩展（0x10–0x1a）保留未启用：有效请求统一返回 HTTP 501 + 5010；
 * 缺失 {@code signed_tx_hex} 优先返回 4001。</p>
 */
class PaymentRpcBroadcastWriteOffTest {

    private final PaymentRpcController controller = new PaymentRpcController();

    @Test
    void nullBodyReturns4001() {
        Object result = controller.broadcastTransaction(null);

        assertMissingHexResult(result);
    }

    @Test
    void emptyBodyReturns4001() {
        Object result = controller.broadcastTransaction(Map.of());

        assertMissingHexResult(result);
    }

    @Test
    void blankSignedTxHexReturns4001() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("signed_tx_hex", "   ");
        Object result = controller.broadcastTransaction(body);

        assertMissingHexResult(result);
    }

    @Test
    void validSignedTxHexReturnsHttp501AndCode5010() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("signed_tx_hex", "00");

        Object result = controller.broadcastTransaction(body);

        ResponseEntity<?> entity = assertInstanceOf(ResponseEntity.class, result);
        assertEquals(501, entity.getStatusCode().value());

        Map<?, ?> payload = assertInstanceOf(Map.class, entity.getBody());
        assertEquals(APIResult.PAYMENT_EXTENSION_NOT_ENABLED, payload.get("code"));
        assertTrue(String.valueOf(payload.get("message")).contains("0x10-0x1a"),
                "message must state that 0x10-0x1a is reserved: " + payload.get("message"));
    }

    private static void assertMissingHexResult(Object result) {
        Map<?, ?> payload = assertInstanceOf(Map.class, result);
        assertEquals(4001, payload.get("code"));
    }
}
