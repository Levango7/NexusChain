package org.nexus.signing.controller;

import org.junit.jupiter.api.Test;
import org.nexus.signing.mpc.MpcProtocolException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 失败码契约：未处理异常兜底必须回 5002（结果不可判定），
 * 不得与「广播前确定未动账」的业务失败 5000 混用。
 *
 * <p>为什么要有这组断言：5002 是调用方 fail-closed 判据的输入。若有人把兜底
 * 改回 5000，调用方会把「可能已广播」判成「确定未动账」并重放——A2 与 gateway
 * 两条腿的 {@code declaresNotExecuted} 白名单都认 5000。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleGeneric_returnsIndeterminate5002() {
        ResponseEntity<Object> response = handler.handleGeneric(new IllegalStateException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(GlobalExceptionHandler.BIZ_INTERNAL_ERROR, body.get("statusCode"));
        assertEquals(5002, body.get("statusCode"));
        assertEquals("Internal error", body.get("message"));
        // 业务失败 5000 = 「确定未动账」；兜底异常不知道是否已广播，必须区分
        assertNotEquals(5000, body.get("statusCode"));
    }

    @Test
    void mpcProtocolRejection_keepsBusinessFail5000() {
        ResponseEntity<Object> response =
                handler.handleMpcProtocol(new MpcProtocolException(
                        MpcProtocolException.Reason.QUORUM_NOT_REACHED, "only 1 of 3 reachable"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(5000, body.get("statusCode"));
    }
}
