package org.nexus.gateway.reconciliation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 差错处理 REST API。
 *
 * <p>提供差错列表查询、差错详情查看、审核差错、解决差错、升级差错等端点。
 * 所有端点要求 MERCHANT 或 ADMIN 角色权限。</p>
 */
@RestController
@RequestMapping("/api/v1/reconciliation/discrepancies")
@PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
public class DiscrepancyResolutionController {

    private final DiscrepancyResolutionService resolutionService;

    public DiscrepancyResolutionController(
            DiscrepancyResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    /**
     * 查询商户的差错列表（可按状态/类型筛选）。
     *
     * @param merchantId 商户 ID
     * @param status     差错状态（可选）
     * @param type       差异类型（可选）
     * @return 差错记录列表
     */
    @GetMapping("/{merchantId}")
    public ResponseEntity<List<ReconciliationDiscrepancy>> getDiscrepancies(
            @PathVariable Long merchantId,
            @RequestParam(required = false) ReconciliationDiscrepancy.DiscrepancyStatus status,
            @RequestParam(required = false) ReconciliationDiscrepancy.DiscrepancyType type) {

        List<ReconciliationDiscrepancy> discrepancies;
        if (status != null) {
            discrepancies = resolutionService.getDiscrepanciesByMerchantAndStatus(merchantId, status);
        } else if (type != null) {
            discrepancies = resolutionService.getDiscrepanciesByMerchantAndType(merchantId, type);
        } else {
            discrepancies = resolutionService.getDiscrepanciesByMerchant(merchantId);
        }
        return ResponseEntity.ok(discrepancies);
    }

    /**
     * 查询对账文件关联的差错列表。
     *
     * @param merchantId 商户 ID
     * @param fileId     对账文件记录 ID
     * @return 差错记录列表
     */
    @GetMapping("/{merchantId}/file/{fileId}")
    public ResponseEntity<List<ReconciliationDiscrepancy>> getDiscrepanciesByFile(
            @PathVariable Long merchantId,
            @PathVariable Long fileId) {
        List<ReconciliationDiscrepancy> discrepancies =
                resolutionService.getDiscrepanciesByFileId(fileId);
        return ResponseEntity.ok(discrepancies);
    }

    /**
     * 查看差错详情。
     *
     * @param discrepancyId 差错记录 ID
     * @return 差错记录详情
     */
    @GetMapping("/detail/{discrepancyId}")
    public ResponseEntity<ReconciliationDiscrepancy> getDiscrepancyDetail(
            @PathVariable Long discrepancyId) {
        Optional<ReconciliationDiscrepancy> discrepancy =
                resolutionService.findDiscrepancyById(discrepancyId);
        return discrepancy.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 开始调查差错（状态流转：DISCOVERED → INVESTIGATING）。
     *
     * @param discrepancyId 差错记录 ID
     * @return 更新后的差错记录
     */
    @PostMapping("/{discrepancyId}/investigate")
    public ResponseEntity<ReconciliationDiscrepancy> startInvestigation(
            @PathVariable Long discrepancyId) {
        try {
            ReconciliationDiscrepancy updated =
                    resolutionService.startInvestigation(discrepancyId);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 待补渠道归属的差错列表（运营工作队列，P1 修复 2026-10-11）。
     *
     * <p>V94 之前落库的差错没有渠道归属，渠道退款路径对它们 fail-closed；
     * 本端点给运营一个"待办清单"，逐个补录后即可走自动退款。</p>
     */
    @GetMapping("/unattributed")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ReconciliationDiscrepancy>> listUnattributed() {
        return ResponseEntity.ok(resolutionService.findWithoutChannelAttribution());
    }

    /**
     * 补录差错的渠道归属（P1 修复 2026-10-11）。
     *
     * <p><b>仅允许 null → 有值</b>（已有归属拒绝覆盖，防止把已归因的差错改错渠道——
     * 那会让退款退错对象，比"退不了"更严重）；取值只接受 WECHAT / ALIPAY。属人工数据修正，
     * 每次补录写审计。</p>
     *
     * @param discrepancyId 差错 ID
     * @param channelType   渠道类型（WECHAT / ALIPAY）
     */
    @PostMapping("/{discrepancyId}/channel")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReconciliationDiscrepancy> annotateChannel(
            @PathVariable Long discrepancyId,
            @RequestParam String channelType) {
        try {
            return ResponseEntity.ok(
                    resolutionService.annotateChannelType(discrepancyId, channelType));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 解决差错（状态流转：INVESTIGATING/DISCOVERED → RESOLVED）。
     *
     * @param discrepancyId 差错记录 ID
     * @param resolutionNote 解决备注
     * @return 更新后的差错记录
     */
    @PostMapping("/{discrepancyId}/resolve")
    public ResponseEntity<ReconciliationDiscrepancy> resolveDiscrepancy(
            @PathVariable Long discrepancyId,
            @RequestParam(required = false) String resolutionNote) {
        try {
            ReconciliationDiscrepancy updated =
                    resolutionService.resolveDiscrepancy(discrepancyId, resolutionNote);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 升级差错（状态流转：INVESTIGATING/DISCOVERED → ESCALATED）。
     *
     * @param discrepancyId 差错记录 ID
     * @param escalationNote 升级备注
     * @return 更新后的差错记录
     */
    @PostMapping("/{discrepancyId}/escalate")
    public ResponseEntity<ReconciliationDiscrepancy> escalateDiscrepancy(
            @PathVariable Long discrepancyId,
            @RequestParam(required = false) String escalationNote) {
        try {
            ReconciliationDiscrepancy updated =
                    resolutionService.escalateDiscrepancy(discrepancyId, escalationNote);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}