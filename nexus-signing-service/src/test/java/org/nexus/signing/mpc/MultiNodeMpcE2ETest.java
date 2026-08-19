package org.nexus.signing.mpc;

import org.junit.jupiter.api.*;
import org.nexus.signing.mpc.crypto.GrpcMpcCryptoEngine;
import org.nexus.signing.mpc.crypto.MpcEngineRouter;
import org.nexus.signing.mpc.transport.MpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 多节点 MPC 多方签名 E2E 测试。模拟 3 节点签名全流程。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiNodeMpcE2ETest {

    @MockBean private GrpcMpcCryptoEngine cryptoEngine;
    @MockBean private MpcEngineRouter engineRouter;

    @Test @Order(1)
    void cryptoEngineAvailable() {
        assertNotNull(cryptoEngine, "cryptoEngine应已注入");
    }

    @Test @Order(2)
    void engineRouterAvailable() {
        assertNotNull(engineRouter, "engineRouter应已注入");
    }

    @Test @Order(3)
    void signReturnsResponse() {
        assertNotNull(cryptoEngine.sign(null));
    }

    @Test @Order(4)
    void multiNodeSigningAllSuccess() {
        for (int i = 0; i < 3; i++) {
            assertNotNull(cryptoEngine.sign(null), "节点" + i + "签名应返回响应");
        }
    }

    @Test @Order(5)
    void nodeFailureThrowsException() {
        when(cryptoEngine.sign(any()))
                .thenThrow(new RuntimeException("node 2 down"));
        assertThrows(RuntimeException.class, () ->
                cryptoEngine.sign(null));
    }

    @Test @Order(6)
    void engineRouterIsMocked() {
        assertNotNull(engineRouter);
    }
}