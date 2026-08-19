package org.nexus.settlement.funds;

import org.junit.jupiter.api.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多签审批服务测试（FundSweep 增强）。
 *
 * <p>验证资金归集执行前的多签审批流程：发起审批→多签确认→达到阈值→可执行。
 *
 * @since 2.12.0
 */
@DisplayName("FundSweep多签审批")
class MultiSigApprovalServiceTest {

    private MultiSigApprovalService service;
    private static final List<String> APPROVERS = List.of("alice", "bob", "carol");
    private static final int THRESHOLD = 2;

    @BeforeEach
    void setUp() {
        service = new MultiSigApprovalService();
    }

    @Test
    @Order(1)
    @DisplayName("1. 发起审批→部分审批→未达阈值")
    void partialApproval_notApproved() {
        var req = service.requestApproval("order-001", "admin", APPROVERS, THRESHOLD);
        assertEquals(MultiSigApprovalService.ApprovalStatus.PENDING, req.getStatus());

        assertTrue(service.approve(req.getApprovalId(), "alice"));
        assertFalse(service.isApproved(req.getApprovalId()), "1票未达阈值2");
        assertEquals(MultiSigApprovalService.ApprovalStatus.PENDING, service.getStatus(req.getApprovalId()).getStatus());
    }

    @Test
    @Order(2)
    @DisplayName("2. 发起审批→足够审批→达阈值→可执行")
    void sufficientApproval_approved() {
        var req = service.requestApproval("order-002", "admin", APPROVERS, THRESHOLD);

        service.approve(req.getApprovalId(), "alice");
        service.approve(req.getApprovalId(), "bob");

        assertTrue(service.isApproved(req.getApprovalId()), "2票达阈值应approved");
        assertEquals(MultiSigApprovalService.ApprovalStatus.APPROVED, service.getStatus(req.getApprovalId()).getStatus());
    }

    @Test
    @Order(3)
    @DisplayName("3. 审批拒绝→不可执行")
    void rejected_notExecutable() {
        var req = service.requestApproval("order-003", "admin", APPROVERS, THRESHOLD);

        service.approve(req.getApprovalId(), "alice");
        assertTrue(service.reject(req.getApprovalId(), "bob"));

        assertFalse(service.isApproved(req.getApprovalId()), "被拒绝的审批不可执行");
        assertEquals(MultiSigApprovalService.ApprovalStatus.REJECTED, service.getStatus(req.getApprovalId()).getStatus());
        assertEquals("bob", service.getStatus(req.getApprovalId()).getRejectedBy());
    }

    @Test
    @Order(4)
    @DisplayName("4. 重复审批→幂等（同一审批人多次确认只算1票）")
    void duplicateApproval_idempotent() {
        var req = service.requestApproval("order-004", "admin", APPROVERS, THRESHOLD);

        service.approve(req.getApprovalId(), "alice");
        service.approve(req.getApprovalId(), "alice"); // 重复
        service.approve(req.getApprovalId(), "alice"); // 重复

        assertEquals(1, service.getStatus(req.getApprovalId()).getApprovedBy().size(),
                "同一审批人多次确认只算1票");
    }

    @Test
    @Order(5)
    @DisplayName("5. 未授权审批人→拒绝")
    void unauthorizedApprover_rejected() {
        var req = service.requestApproval("order-005", "admin", APPROVERS, THRESHOLD);

        assertFalse(service.approve(req.getApprovalId(), "eve"), "未授权审批人应被拒绝");
        assertEquals(0, service.getStatus(req.getApprovalId()).getApprovedBy().size());
    }

    @Test
    @Order(6)
    @DisplayName("6. 多审批独立管理→互不干扰")
    void multipleApprovals_independent() {
        var req1 = service.requestApproval("order-A", "admin", APPROVERS, THRESHOLD);
        var req2 = service.requestApproval("order-B", "admin", APPROVERS, THRESHOLD);

        service.approve(req1.getApprovalId(), "alice");
        service.approve(req1.getApprovalId(), "bob");
        service.approve(req2.getApprovalId(), "alice");

        assertTrue(service.isApproved(req1.getApprovalId()), "req1应approved");
        assertFalse(service.isApproved(req2.getApprovalId()), "req2不应approved");
    }

    @Test
    @Order(7)
    @DisplayName("7. 拒绝后不能再审批")
    void rejectedThenApprove_fails() {
        var req = service.requestApproval("order-006", "admin", APPROVERS, THRESHOLD);

        service.reject(req.getApprovalId(), "alice");
        assertFalse(service.approve(req.getApprovalId(), "bob"), "拒绝后不能再审批");
        assertFalse(service.isApproved(req.getApprovalId()));
    }
}