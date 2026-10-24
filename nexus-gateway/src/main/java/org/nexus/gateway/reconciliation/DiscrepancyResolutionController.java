package org.nexus.gateway.reconciliation;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 差错处理 REST API。
 *
 * <p>提供差错列表查询、差错详情查看、审核差错、解决差错、升级差错等端点。</p>
 *
 * <h3>鉴权（2026-10-25 修复跨商户越权）</h3>
 *
 * <p><b>修复前</b>：类级只有 {@code @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")}，
 * 而商户可访问的 6 个端点里：</p>
 * <ul>
 *   <li>{@code GET /{merchantId}} —— 路径 merchantId 直接交给 Service，不校验归属；</li>
 *   <li>{@code GET /{merchantId}/file/{fileId}} —— <b>校验了路径 merchantId，但没校验 fileId</b>：
 *       带上自己的 merchantId + 别人的 fileId 即可读他人差错（混合形态）；</li>
 *   <li>{@code GET /detail/{discrepancyId}} 与三个写端点
 *       （{@code investigate} / {@code resolve} / {@code escalate}）—— 路径里只有
 *       {@code discrepancyId}，<b>只按 ID 取/改，不校验归属</b>。任何商户换一个 ID
 *       即可查看、甚至推进他人的差错状态。</li>
 * </ul>
 *
 * <p><b>修复方式</b>：商户身份由 {@code ApiKeyInterceptor} 解析并写入
 * {@code nexus.merchantId}，控制器用 {@link MerchantOwnershipGuard} 比对。
 * 资源号形态（{@code discrepancyId} / {@code fileId}）必须<b>先加载资源再比对它的
 * merchantId</b>——路径参数守卫管不到这种形态。</p>
 *
 * <p>为什么去掉类级 {@code hasRole('MERCHANT')}：API Key 调用方在 Spring Security 里
 * 没有 Authentication（{@code ApiKeyInterceptor} 只设 request 属性），
 * 该表达式对真实商户恒假、恒 403。</p>
 *
 * <p><b>管理动作不变</b>：{@code /unattributed} 与 {@code /{id}/channel} 仍限
 * {@code ADMIN}（运营工作队列与人工数据修正）。它们与
 * {@code RiskLinkController} 修前同型——ADMIN 只有 JWT 会被 {@code ApiKeyInterceptor}
 * 挡成 401，属既有独立问题，本类不引入也不修。</p>
 */
@RestController
@RequestMapping("/api/v1/reconciliation/discrepancies")
public class DiscrepancyResolutionController {

    private final DiscrepancyResolutionService resolutionService;
    private final MerchantOwnershipGuard ownershipGuard;

    public DiscrepancyResolutionController(
            DiscrepancyResolutionService resolutionService,
            MerchantOwnershipGuard ownershipGuard) {
        this.resolutionService = resolutionService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 归属校验（路径 merchantId 形态）。
     */
    private void requireOwnMerchant(HttpServletRequest request, Long merchantId) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "discrepancy", merchantId);
    }

    /**
     * 归属校验（资源号形态）：先加载差错，再比对它属于谁。
     *
     * <p>返回 {@code null} 表示记录不存在（调用方据此 404）；
     * 存在但不属于调用方时抛 {@code MerchantOwnershipException} → 403。</p>
     *
     * <p><b>已知边界</b>：这是"先查后改"，与随后的 Service 写操作之间存在
     * 一个 TOCTOU 窗口。当前 Service 在单节点事务内执行，窗口可接受；
     * 若将来多节点并发推进同一差错，应把归属判据下推为带条件的更新
     * （{@code WHERE id=? AND merchant_id=?}）。</p>
     */
    private ReconciliationDiscrepancy requireOwnDiscrepancy(HttpServletRequest request,
                                                            Long discrepancyId) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        Optional<ReconciliationDiscrepancy> found =
                resolutionService.findDiscrepancyById(discrepancyId);
        if (found.isEmpty()) {
            return null;
        }
        ReconciliationDiscrepancy discrepancy = found.get();
        ownershipGuard.requireOwned(callerMerchantId, discrepancy.getMerchantId(),
                "discrepancy", discrepancyId);
        return discrepancy;
    }

    /**
     * 查询商户的差错列表（可按状态/类型筛选）。
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param status     差错状态（可选）
     * @param type       差异类型（可选）
     * @param request    HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 差错记录列表
     */
    @GetMapping("/{merchantId}")
    public ResponseEntity<List<ReconciliationDiscrepancy>> getDiscrepancies(
            @PathVariable Long merchantId,
            @RequestParam(required = false) ReconciliationDiscrepancy.DiscrepancyStatus status,
            @RequestParam(required = false) ReconciliationDiscrepancy.DiscrepancyType type,
            HttpServletRequest request) {

        requireOwnMerchant(request, merchantId);

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
     * <p><b>混合形态</b>：路径 {@code merchantId} 要校验，{@code fileId} 也要校验——
     * 否则"自己的 merchantId + 别人的 fileId"就能读他人差错。这里对返回的每一条
     * 逐项核对 {@code merchantId}，任一条不属于调用方即整体 403（不做静默截断，
     * 免得把越权试探伪装成"空列表"）。</p>
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param fileId     对账文件记录 ID（其差错必须全部属于调用方）
     * @param request    HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 差错记录列表
     */
    @GetMapping("/{merchantId}/file/{fileId}")
    public ResponseEntity<List<ReconciliationDiscrepancy>> getDiscrepanciesByFile(
            @PathVariable Long merchantId,
            @PathVariable Long fileId,
            HttpServletRequest request) {

        requireOwnMerchant(request, merchantId);

        List<ReconciliationDiscrepancy> discrepancies =
                resolutionService.getDiscrepanciesByFileId(fileId);
        for (ReconciliationDiscrepancy d : discrepancies) {
            ownershipGuard.requireOwned(merchantId, d.getMerchantId(), "discrepancy", d.getId());
        }
        return ResponseEntity.ok(discrepancies);
    }

    /**
     * 查看差错详情。
     *
     * @param discrepancyId 差错记录 ID（必须属于调用方）
     * @param request       HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 差错记录详情
     */
    @GetMapping("/detail/{discrepancyId}")
    public ResponseEntity<ReconciliationDiscrepancy> getDiscrepancyDetail(
            @PathVariable Long discrepancyId,
            HttpServletRequest request) {
        ReconciliationDiscrepancy discrepancy = requireOwnDiscrepancy(request, discrepancyId);
        return discrepancy == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(discrepancy);
    }

    /**
     * 开始调查差错（状态流转：DISCOVERED → INVESTIGATING）。
     *
     * @param discrepancyId 差错记录 ID（必须属于调用方）
     * @param request       HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 更新后的差错记录
     */
    @PostMapping("/{discrepancyId}/investigate")
    public ResponseEntity<ReconciliationDiscrepancy> startInvestigation(
            @PathVariable Long discrepancyId,
            HttpServletRequest request) {
        if (requireOwnDiscrepancy(request, discrepancyId) == null) {
            return ResponseEntity.notFound().build();
        }
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
     * @param discrepancyId  差错记录 ID（必须属于调用方）
     * @param resolutionNote 解决备注
     * @param request        HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 更新后的差错记录
     */
    @PostMapping("/{discrepancyId}/resolve")
    public ResponseEntity<ReconciliationDiscrepancy> resolveDiscrepancy(
            @PathVariable Long discrepancyId,
            @RequestParam(required = false) String resolutionNote,
            HttpServletRequest request) {
        if (requireOwnDiscrepancy(request, discrepancyId) == null) {
            return ResponseEntity.notFound().build();
        }
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
     * @param discrepancyId  差错记录 ID（必须属于调用方）
     * @param escalationNote 升级备注
     * @param request        HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 更新后的差错记录
     */
    @PostMapping("/{discrepancyId}/escalate")
    public ResponseEntity<ReconciliationDiscrepancy> escalateDiscrepancy(
            @PathVariable Long discrepancyId,
            @RequestParam(required = false) String escalationNote,
            HttpServletRequest request) {
        if (requireOwnDiscrepancy(request, discrepancyId) == null) {
            return ResponseEntity.notFound().build();
        }
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
