package org.nexus.signing.mpc;

import org.junit.jupiter.api.*;
import org.nexus.signing.approval.SigningApprovalService;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.crypto.GrpcMpcCryptoEngine;
import org.nexus.signing.mpc.crypto.MpcEngineRouter;
import org.nexus.signing.mpc.transport.MpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 多节点 MPC 多方签名 Mock 测试（原 MultiNodeMpcE2ETest 重命名）。
 *
 * <p><b>命名诚实性说明</b>：本测试使用 {@code @MockitoBean} 替换
 * {@link GrpcMpcCryptoEngine} 与 {@link MpcEngineRouter}，
 * 仅验证 Spring 上下文加载与 mock 桩行为，不涉及真实 gRPC/MPC 引擎，
 * 因此从 "E2E" 重命名为 "Mock" 以准确反映测试性质。</p>
 *
 * <p>真实多节点 MPC 端到端验证见 {@link MpcMultiHostEngineTest}
 * （需 Docker 容器 + WSL 引擎）与 {@link MpcMultiHostTlsTest}（mTLS）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiNodeMpcMockTest {

    @MockitoBean private GrpcMpcCryptoEngine cryptoEngine;
    @MockitoBean private MpcEngineRouter engineRouter;
    // SigningApprovalService 通过构造函数注入 MpcApprovalPolicy 和 AuditLogService，
    // 需 mock 这两个 bean 否则 Spring 上下文无法加载（NoSuchMethodException: <init>()）。
    @MockitoBean private MpcApprovalPolicy mpcApprovalPolicy;
    @MockitoBean private AuditLogService auditLogService;

    @Test @Order(1)
    void cryptoEngineMockBeanAvailable() {
        assertNotNull(cryptoEngine, "cryptoEngine mock 应已注入");
    }

    @Test @Order(2)
    void engineRouterMockBeanAvailable() {
        assertNotNull(engineRouter, "engineRouter mock 应已注入");
    }

    @Test @Order(3)
    void signMockReturnsResponse() {
        assertNotNull(cryptoEngine.sign(null));
    }

    @Test @Order(4)
    void multiNodeSigningMockAllReturnResponse() {
        for (int i = 0; i < 3; i++) {
            assertNotNull(cryptoEngine.sign(null), "节点" + i + " mock 签名应返回响应");
        }
    }

    @Test @Order(5)
    void nodeFailureMockThrowsException() {
        when(cryptoEngine.sign(any()))
                .thenThrow(new RuntimeException("node 2 down"));
        assertThrows(RuntimeException.class, () ->
                cryptoEngine.sign(null));
    }

    @Test @Order(6)
    void engineRouterMockBeanIsInjected() {
        assertNotNull(engineRouter);
    }
}