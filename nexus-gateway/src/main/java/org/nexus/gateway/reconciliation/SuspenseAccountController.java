package org.nexus.gateway.reconciliation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 挂账资金 REST API。
 *
 * <p>提供挂账列表查询、详情查看、核销、注销、提交处理方案、审批通过/拒绝等端点。
 * 所有端点要求 MERCHANT 或 ADMIN 角色权限。</p>
 */
@RestController
@RequestMapping("/api/v1/suspense-accounts")
@PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
public class SuspenseAccountController {

    private final SuspenseAccountService suspenseAccountService;
    private final ManualResolutionWorkflow manualResolutionWorkflow;

    public SuspenseAccountController(
            SuspenseAccountService suspenseAccountService,
            ManualResolutionWorkflow manualResolutionWorkflow) {
        this.suspenseAccountService = suspenseAccountService;
        this.manualResolutionWorkflow = manualResolutionWorkflow;
    }

    /**
     * 查询挂账列表（支持按商户/状态/类型过滤）。
     *
     * @param merchantId 商户 ID（可选）
     * @param status     挂账状态（可选）
     * @param type       差错类型（可选）
     * @return 挂账记录列表
     */
    @GetMapping
    public ResponseEntity<List<SuspenseAccount>> getSuspenseAccounts(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) SuspenseAccount.SuspenseStatus status,
            @RequestParam(required = false) SuspenseAccount.DiscrepancyType type) {

        List<SuspenseAccount> accounts;
        if (merchantId != null && status != null) {
            accounts = suspenseAccountService.findByMerchantIdAndStatus(merchantId, status);
        } else if (merchantId != null && type != null) {
            accounts = suspenseAccountService.findByMerchantIdAndDiscrepancyType(merchantId, type);
        } else if (merchantId != null) {
            accounts = suspenseAccountService.findByMerchantId(merchantId);
        } else if (status != null) {
            accounts = suspenseAccountService.findByStatus(status);
        } else if (type != null) {
            accounts = suspenseAccountService.findByDiscrepancyType(type);
        } else {
            accounts = suspenseAccountService.findByStatus(SuspenseAccount.SuspenseStatus.PENDING);
        }
        return ResponseEntity.ok(accounts);
    }

    /**
     * 查询挂账详情。
     *
     * @param id 挂账记录 ID
     * @return 挂账记录详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<SuspenseAccount> getSuspenseAccountDetail(@PathVariable Long id) {
        Optional<SuspenseAccount> account = suspenseAccountService.findSuspenseAccountById(id);
        return account.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 核销挂账。
     *
     * @param id 挂账记录 ID
     * @param resolutionNote 核销备注
     * @return 更新后的挂账记录
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<SuspenseAccount> resolveSuspenseAccount(
            @PathVariable Long id,
            @RequestParam(required = false) String resolutionNote) {
        try {
            SuspenseAccount updated =
                    suspenseAccountService.resolveSuspenseAccount(id, resolutionNote);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 注销挂账（无法追回的资金）。
     *
     * @param id 挂账记录 ID
     * @param reason 注销原因
     * @return 更新后的挂账记录
     */
    @PostMapping("/{id}/write-off")
    public ResponseEntity<SuspenseAccount> writeOffSuspenseAccount(
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        try {
            SuspenseAccount updated =
                    suspenseAccountService.writeOffSuspenseAccount(id, reason);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 提交处理方案。
     *
     * @param id 挂账记录 ID
     * @param proposal 处理方案描述
     * @param proposedBy 方案提交人
     * @return 更新后的挂账记录
     */
    @PostMapping("/{id}/proposal")
    public ResponseEntity<SuspenseAccount> submitResolutionProposal(
            @PathVariable Long id,
            @RequestParam String proposal,
            @RequestParam String proposedBy) {
        try {
            SuspenseAccount updated =
                    manualResolutionWorkflow.submitResolutionProposal(id, proposal, proposedBy);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 审批通过。
     *
     * @param id 挂账记录 ID
     * @param approvedBy 审批人
     * @return 更新后的挂账记录（已核销）
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<SuspenseAccount> approveResolution(
            @PathVariable Long id,
            @RequestParam String approvedBy) {
        try {
            SuspenseAccount updated =
                    manualResolutionWorkflow.approveResolution(id, approvedBy);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 审批拒绝。
     *
     * @param id 挂账记录 ID
     * @param rejectedBy 审批人
     * @param reason 拒绝原因
     * @return 更新后的挂账记录
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<SuspenseAccount> rejectResolution(
            @PathVariable Long id,
            @RequestParam String rejectedBy,
            @RequestParam(required = false) String reason) {
        try {
            SuspenseAccount updated =
                    manualResolutionWorkflow.rejectResolution(id, rejectedBy, reason);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}