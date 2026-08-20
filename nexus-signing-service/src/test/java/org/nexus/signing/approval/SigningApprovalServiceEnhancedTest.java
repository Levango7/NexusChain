package org.nexus.signing.approval;

import org.junit.jupiter.api.*;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("签名审批服务（通知+持久化）")
class SigningApprovalServiceEnhancedTest {

    private MpcApprovalPolicy mpcPolicy;
    private AuditLogService auditLog;

    @BeforeEach
    void setUp() {
        mpcPolicy = mock(MpcApprovalPolicy.class);
        when(mpcPolicy.getRequiredApprovers(any(), any())).thenReturn(2);
        auditLog = mock(AuditLogService.class);
    }

    @Test @Order(1) @DisplayName("1.创建审批->通知审批人")
    void createApproval_notifiesApprover() {
        ApprovalNotifier mockNotifier = mock(ApprovalNotifier.class);
        var service = new SigningApprovalService(mpcPolicy, auditLog,
                new MapApprovalStore(new ConcurrentHashMap<>()), mockNotifier);
        String rid = service.createApprovalRequest("pkA","pkB",new BigDecimal("20000"),"USDT","init","1.1.1.1");
        assertNotNull(rid);
        verify(mockNotifier, times(1)).notifyApprovalCreated(any());
    }

    @Test @Order(2) @DisplayName("2.通知异常->不影响审批流程")
    void notifyFailure_doesNotBlock() {
        ApprovalNotifier failingNotifier = req -> { throw new RuntimeException("notify fail"); };
        var service = new SigningApprovalService(mpcPolicy, auditLog,
                new MapApprovalStore(new ConcurrentHashMap<>()), failingNotifier);
        String rid = service.createApprovalRequest("pkA","pkB",new BigDecimal("20000"),"USDT","init","1.1.1.1");
        assertNotNull(rid);
        assertNotNull(service.getRequest(rid));
    }

    @Test @Order(3) @DisplayName("3.文件持久化->存后重启可恢复")
    void fileStore_persistAcrossRestart() throws Exception {
        Path tmpFile = Files.createTempFile("approval-test-", ".jsonl");
        try {
            FileBasedApprovalStore store = new FileBasedApprovalStore(tmpFile);
            var service = new SigningApprovalService(mpcPolicy, auditLog, store, null);
            String rid = service.createApprovalRequest("pkA","pkB",new BigDecimal("50000"),"USDT","init","1.1.1.1");
            assertNotNull(rid);
            assertEquals(1, store.size());

            // 模拟重启：创建新store从同一文件恢复
            FileBasedApprovalStore store2 = new FileBasedApprovalStore(tmpFile);
            assertEquals(1, store2.size());
            SigningApprovalRequest restored = store2.get(rid);
            assertNotNull(restored);
            assertEquals(new BigDecimal("50000"), restored.getAmount());
            assertEquals("init", restored.getInitiator());
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test @Order(4) @DisplayName("4.审批流程完整->approve+reject+markExecuted")
    void fullApprovalFlow() {
        var service = new SigningApprovalService(mpcPolicy, auditLog,
                new MapApprovalStore(new ConcurrentHashMap<>()), null);
        String rid = service.createApprovalRequest("pkA","pkB",new BigDecimal("30000"),"USDT","init","1.1.1.1");
        assertNotNull(rid);
        assertEquals(SigningApprovalRequest.Status.PENDING, service.getRequest(rid).getStatus());

        var approved = service.approve(rid, "bob@nexus", "1.1.1.2");
        assertEquals(SigningApprovalRequest.Status.PENDING, approved.getStatus());

        var approved2 = service.approve(rid, "carol@nexus", "1.1.1.3");
        assertEquals(SigningApprovalRequest.Status.APPROVED, approved2.getStatus());

        var executed = service.markExecuted(rid);
        assertEquals(SigningApprovalRequest.Status.EXECUTED, executed.getStatus());
    }

    @Test @Order(5) @DisplayName("5.拒绝流程->拒绝后状态REJECTED")
    void rejectionFlow() {
        var service = new SigningApprovalService(mpcPolicy, auditLog,
                new MapApprovalStore(new ConcurrentHashMap<>()), null);
        String rid = service.createApprovalRequest("pkA","pkB",new BigDecimal("30000"),"USDT","init","1.1.1.1");
        var rejected = service.reject(rid, "eve@nexus", "1.1.1.4");
        assertEquals(SigningApprovalRequest.Status.REJECTED, rejected.getStatus());
    }

    @Test @Order(6) @DisplayName("6.内存存储->默认ConcurrentHashMap实现")
    void defaultMemoryStore() {
        var service = new SigningApprovalService(mpcPolicy, auditLog);
        String rid = service.createApprovalRequest("pkA","pkB",new BigDecimal("20000"),"USDT","init","1.1.1.1");
        assertNotNull(rid);
        assertNotNull(service.getRequest(rid));
        assertFalse(service.getPendingRequests().isEmpty());
    }

    @Test @Order(7) @DisplayName("7.小金额不触发审批->returns null")
    void smallAmount_noApproval() {
        var service = new SigningApprovalService(mpcPolicy, auditLog);
        String rid = service.createApprovalRequest("pkA","pkB",new BigDecimal("100"),"USDT","init","1.1.1.1");
        assertNull(rid);
    }
}