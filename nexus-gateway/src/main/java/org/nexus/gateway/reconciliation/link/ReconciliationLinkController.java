package org.nexus.gateway.reconciliation.link;

import org.nexus.gateway.reconciliation.SuspenseAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 对账与资金账户联动 REST API。
 *
 * <p>提供以下接口：
 * <ul>
 *   <li>调整记录查询（按商户、状态、类型）</li>
 *   <li>调整审批（通过/拒绝）</li>
 *   <li>挂账核销（自动/人工）</li>
 *   <li>审计报告查询</li>
 *   <li>手动触发审计</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/reconciliation/link")
@PreAuthorize("hasRole('ADMIN') or hasRole('MERCHANT')")
public class ReconciliationLinkController {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationLinkController.class);

    private final ReconciliationAdjustmentService adjustmentService;
    private final SuspenseWriteoffService suspenseWriteoffService;
    private final TransactionAuditService transactionAuditService;

    public ReconciliationLinkController(
            ReconciliationAdjustmentService adjustmentService,
            SuspenseWriteoffService suspenseWriteoffService,
            TransactionAuditService transactionAuditService) {
        this.adjustmentService = adjustmentService;
        this.suspenseWriteoffService = suspenseWriteoffService;
        this.transactionAuditService = transactionAuditService;
    }

    // === 调整记录查询 ===

    /**
     * 查询商户的所有调整记录。
     */
    @GetMapping("/adjustments/merchant/{merchantId}")
    public ResponseEntity<List<ReconciliationAdjustment>> getAdjustmentsByMerchant(
            @PathVariable Long merchantId) {
        return ResponseEntity.ok(adjustmentService.getAdjustmentsByMerchant(merchantId));
    }

    /**
     * 按审批状态查询调整记录。
     */
    @GetMapping("/adjustments/status/{status}")
    public ResponseEntity<List<ReconciliationAdjustment>> getAdjustmentsByStatus(
            @PathVariable ApprovalStatus status) {
        return ResponseEntity.ok(adjustmentService.getAdjustmentsByStatus(status));
    }

    /**
     * 按商户 ID 和审批状态查询调整记录。
     */
    @GetMapping("/adjustments/merchant/{merchantId}/status/{status}")
    public ResponseEntity<List<ReconciliationAdjustment>> getAdjustmentsByMerchantAndStatus(
            @PathVariable Long merchantId, @PathVariable ApprovalStatus status) {
        return ResponseEntity.ok(adjustmentService.getAdjustmentsByMerchantAndStatus(merchantId, status));
    }

    /**
     * 按关联差错记录 ID 查询调整记录。
     */
    @GetMapping("/adjustments/discrepancy/{discrepancyId}")
    public ResponseEntity<List<ReconciliationAdjustment>> getAdjustmentsByDiscrepancyId(
            @PathVariable Long discrepancyId) {
        return ResponseEntity.ok(adjustmentService.getAdjustmentsByDiscrepancyId(discrepancyId));
    }

    /**
     * 按 ID 查询调整记录详情。
     */
    @GetMapping("/adjustments/{id}")
    public ResponseEntity<ReconciliationAdjustment> getAdjustmentById(@PathVariable Long id) {
        return adjustmentService.findAdjustmentById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // === 调整审批 ===

    /**
     * 审批通过调整记录。
     */
    @PostMapping("/adjustments/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReconciliationAdjustment> approveAdjustment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String approvedBy = getCurrentUser();
        if (approvedBy == null || approvedBy.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(adjustmentService.approveAdjustment(id, approvedBy));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 审批拒绝调整记录。
     */
    @PostMapping("/adjustments/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReconciliationAdjustment> rejectAdjustment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String rejectedBy = getCurrentUser();
        String reason = body != null ? body.get("reason") : null;
        if (rejectedBy == null || rejectedBy.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(adjustmentService.rejectAdjustment(id, rejectedBy, reason));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // === 挂账核销 ===

    /**
     * 自动核销挂账（金额 ≤ 阈值）。
     */
    @PostMapping("/suspense/{id}/auto-writeoff")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SuspenseAccount> autoWriteoff(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(suspenseWriteoffService.autoWriteoff(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 人工核销挂账。
     */
    @PostMapping("/suspense/{id}/manual-writeoff")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SuspenseAccount> manualWriteoff(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String approvedBy = getCurrentUser();
        String resolutionNote = body != null ? body.get("resolutionNote") : null;
        if (approvedBy == null || approvedBy.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(
                    suspenseWriteoffService.manualWriteoff(id, approvedBy, resolutionNote));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 查询可自动核销的挂账列表。
     */
    @GetMapping("/suspense/auto-writeoff-candidates")
    public ResponseEntity<List<SuspenseAccount>> getAutoWriteoffCandidates() {
        return ResponseEntity.ok(suspenseWriteoffService.findAutoWriteoffCandidates());
    }

    // === 审计报告 ===

    /**
     * 按日期查询审计记录。
     */
    @GetMapping("/audit/date/{auditDate}")
    public ResponseEntity<List<TransactionAuditRecord>> getAuditRecordsByDate(
            @PathVariable String auditDate) {
        try {
            LocalDate date = LocalDate.parse(auditDate);
            return ResponseEntity.ok(transactionAuditService.getAuditRecordsByDate(date));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 按商户查询审计记录。
     */
    @GetMapping("/audit/merchant/{merchantId}")
    public ResponseEntity<List<TransactionAuditRecord>> getAuditRecordsByMerchant(
            @PathVariable Long merchantId) {
        return ResponseEntity.ok(transactionAuditService.getAuditRecordsByMerchant(merchantId));
    }

    /**
     * 按日期范围查询审计记录。
     */
    @GetMapping("/audit/range")
    public ResponseEntity<List<TransactionAuditRecord>> getAuditRecordsByDateRange(
            @RequestParam String startDate, @RequestParam String endDate) {
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            return ResponseEntity.ok(transactionAuditService.getAuditRecordsByDateRange(start, end));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 手动触发审计。
     */
    @PostMapping("/audit/execute")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<TransactionAuditRecord>> executeAudit(
            @RequestBody Map<String, Object> body) {
        try {
            if (body == null) {
                return ResponseEntity.badRequest().build();
            }
            String auditDateStr = (String) body.get("auditDate");
            Long merchantId = body.get("merchantId") != null
                    ? Long.valueOf(body.get("merchantId").toString()) : null;

            if (auditDateStr == null) {
                return ResponseEntity.badRequest().build();
            }

            LocalDate auditDate = LocalDate.parse(auditDateStr);
            return ResponseEntity.ok(transactionAuditService.executeAudit(auditDate, merchantId));
        } catch (Exception e) {
            log.error("手动触发审计失败: error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // --- 内部方法 ---

    /**
     * 从 SecurityContext 获取当前认证用户名，防止请求体伪造身份。
     *
     * @return 当前认证用户名，如果无认证信息则返回 null
     */
    private String getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }
}