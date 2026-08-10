package org.nexus.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.model.BridgeTransaction.BridgeTxStatus;
import org.nexus.bridge.repository.BridgeTransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link BridgeController} 单元测试：覆盖 REST 端点与异常处理。
 */
@ExtendWith(MockitoExtension.class)
class BridgeControllerTest {

    @Mock
    private BridgeService bridgeService;

    private BridgeController controller;

    @BeforeEach
    void setUp() {
        controller = new BridgeController(bridgeService);
    }

    @Test
    @DisplayName("lock: 应返回 201 CREATED 与桥交易")
    void lock_returnsCreated() {
        LockRequest req = new LockRequest("ethereum", "bsc", 1000L, "0xUser", "0xTarget", "0xHash");
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("tx-1");
        when(bridgeService.lock(any())).thenReturn(tx);

        ResponseEntity<BridgeTransaction> response = controller.lock(req);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("tx-1", response.getBody().getTxId());
    }

    @Test
    @DisplayName("mint: 应返回 200 OK 与桥交易")
    void mint_returnsOk() {
        MintRequest req = new MintRequest("lock-1", new HashMap<>(), "0xM", "bsc");
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("tx-1");
        when(bridgeService.mint(any())).thenReturn(tx);

        ResponseEntity<BridgeTransaction> response = controller.mint(req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("burn: 应返回 201 CREATED 与桥交易")
    void burn_returnsCreated() {
        BurnRequest req = new BurnRequest("bsc", "ethereum", 1000L, "0xUser", "0xTarget", "0xHash");
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("tx-1");
        when(bridgeService.burn(any())).thenReturn(tx);

        ResponseEntity<BridgeTransaction> response = controller.burn(req);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("unlock: 应返回 200 OK 与桥交易")
    void unlock_returnsOk() {
        UnlockRequest req = new UnlockRequest("burn-1", new HashMap<>(), "0xU", "ethereum");
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("tx-1");
        when(bridgeService.unlock(any())).thenReturn(tx);

        ResponseEntity<BridgeTransaction> response = controller.unlock(req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("getTx: 交易存在时返回 200 OK")
    void getTx_foundReturnsOk() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("tx-1");
        when(bridgeService.getTransaction("tx-1")).thenReturn(tx);

        ResponseEntity<BridgeTransaction> response = controller.getTx("tx-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("getTx: 交易不存在时返回 404 NOT FOUND")
    void getTx_notFoundReturns404() {
        when(bridgeService.getTransaction("unknown")).thenReturn(null);

        ResponseEntity<BridgeTransaction> response = controller.getTx("unknown");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    @DisplayName("getBySourceHash: 交易存在时返回 200 OK")
    void getBySourceHash_foundReturnsOk() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("tx-1");
        when(bridgeService.getTransactionBySourceHash("0xhash")).thenReturn(tx);

        ResponseEntity<BridgeTransaction> response = controller.getBySourceHash("0xhash");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("getBySourceHash: 交易不存在时返回 404")
    void getBySourceHash_notFoundReturns404() {
        when(bridgeService.getTransactionBySourceHash("unknown")).thenReturn(null);

        ResponseEntity<BridgeTransaction> response = controller.getBySourceHash("unknown");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("status: 应返回 200 OK 与桥状态")
    void status_returnsOk() {
        BridgeStatus status = new BridgeStatus();
        status.setState(BridgeState.ACTIVE);
        when(bridgeService.getStatus()).thenReturn(status);

        ResponseEntity<BridgeStatus> response = controller.status();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(BridgeState.ACTIVE, response.getBody().getState());
    }

    @Test
    @DisplayName("pause: 应返回 200 OK 与消息")
    void pause_returnsOk() {
        Map<String, String> body = new HashMap<>();
        body.put("validatorId", "v1");

        ResponseEntity<Map<String, String>> response = controller.pause(body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Bridge paused", response.getBody().get("message"));
        verify(bridgeService).pause("v1");
    }

    @Test
    @DisplayName("resume: 应返回 200 OK 与消息")
    void resume_returnsOk() {
        Map<String, Object> body = new HashMap<>();
        body.put("validatorIds", Arrays.asList("v1", "v2", "v3"));

        ResponseEntity<Map<String, String>> response = controller.resume(body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Bridge resumed", response.getBody().get("message"));
        verify(bridgeService).resume(any(Set.class));
    }

    @Test
    @DisplayName("handleBridgeException: 应返回 409 CONFLICT 与错误信息")
    void handleBridgeException_returnsConflict() {
        BridgeException ex = new BridgeException("INVALID_AMOUNT", "amount must be positive");

        ResponseEntity<Map<String, Object>> response = controller.handleBridgeException(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(40900, response.getBody().get("code"));
        assertEquals("amount must be positive", response.getBody().get("message"));
    }
}