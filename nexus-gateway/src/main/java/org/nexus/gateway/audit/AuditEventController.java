package org.nexus.gateway.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计事件查询 REST API（P0 修复 2026-10-11）。
 *
 * <p><b>为什么需要它</b>：v2.63.0 起审计事件落库（`audit_events` 表），但**只能写、不能查**
 * ——资金纠纷时要回溯"谁在什么时候动了什么钱"，运维仍得去 gzip 日志里翻。本控制器把
 * 审计数据暴露成可用查询。</p>
 *
 * <p><b>设计约束</b>：</p>
 * <ul>
 *   <li>只读（GET only）——审计表 append-only，不提供任何改/删接口；</li>
 *   <li><b>按调用方商户隔离（2026-10-26 修复）</b>：审计含资金操作明细，属敏感数据。
 *       修复前类级注解是 {@code hasRole('MERCHANT') or hasRole('ADMIN')}，
 *       四个端点<b>全部只按请求参数查、不校验归属</b>：
 *       <ul>
 *         <li>{@code /merchant/{merchantId}} 可读<b>任意商户</b>的审计事件（IDOR）；</li>
 *         <li>{@code /action/{action}}、{@code /window}、{@code GET} 三视图
 *             返回<b>全平台所有商户</b>的事件。</li>
 *       </ul>
 *       现在四个视图一律以 {@link MerchantOwnershipGuard#requireMerchantId} 解析出的
 *       调用方商户为准：前者的路径参数必须等于调用方，后三者直接按调用方过滤。</li>
 *   <li>类级 {@code @PreAuthorize} 已移除：API Key 调用方没有 {@code Authentication}，
 *       留着会让商户恒 403（与 {@code PaymentController} 同款取舍）。鉴权改由
 *       {@code ApiKeyInterceptor}（设置商户上下文）+ 本类的归属校验共同承担。</li>
 *   <li>三视图：按商户 / 按动作 / 按时间窗——覆盖"查某商户"、"查某类资金动作"、
 *       "查某个时段"三类实际排障场景。</li>
 * </ul>
 *
 * <p><b>未提供</b>：跨商户的"全平台审计视图"。它需要 ADMIN 身份，而 ADMIN 只有 JWT、
 * 没有商户 API Key，会被 {@code ApiKeyInterceptor} 挡在 `/api/v1/**` 之外——
 * 那是独立的配置变更（需动 {@code WebConfig} 的排除名单），不在本次收紧范围内。</p>
 */
@RestController
@RequestMapping("/api/v1/audit/events")
public class AuditEventController {

    private final AuditEventRepository repository;
    private final MerchantOwnershipGuard ownershipGuard;

    public AuditEventController(AuditEventRepository repository,
                                MerchantOwnershipGuard ownershipGuard) {
        this.repository = repository;
        this.ownershipGuard = ownershipGuard;
    }

    /** 按商户查（默认倒序，最新在前）。只允许查自己。 */
    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<AuditEventEntity>> byMerchant(@PathVariable Long merchantId,
                                                             HttpServletRequest request) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "audit-events", merchantId);
        return ResponseEntity.ok(repository.findByMerchantIdOrderByEventTimeDesc(merchantId));
    }

    /** 按动作查（如 REFUND_EXECUTED / CHANNEL_REFUND_EXECUTED / AUTO_WITHDRAW_EXECUTED）。限定当前商户。 */
    @GetMapping("/action/{action}")
    public ResponseEntity<List<AuditEventEntity>> byAction(@PathVariable String action,
                                                           HttpServletRequest request) {
        Long merchantId = ownershipGuard.requireMerchantId(request);
        return ResponseEntity.ok(
                repository.findByMerchantIdAndActionOrderByEventTimeDesc(merchantId, action));
    }

    /** 按时间窗查（排障用：传入 ISO-8601 时间，如 2026-10-11T00:00:00Z）。限定当前商户。 */
    @GetMapping("/window")
    public ResponseEntity<List<AuditEventEntity>> byWindow(
            @RequestParam Instant from,
            @RequestParam Instant to,
            HttpServletRequest request) {
        // 先鉴权再校验参数：未通过归属校验的调用方不应能探测业务规则。
        Long merchantId = ownershipGuard.requireMerchantId(request);
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from 必须早于 to");
        }
        return ResponseEntity.ok(repository
                .findByMerchantIdAndEventTimeBetweenOrderByEventTimeDesc(merchantId, from, to));
    }

    /**
     * 分页兜底视图（数据量大时用；default size=50 防误拉全表）。限定当前商户。
     *
     * <p>刻意**不直接返回 {@code Page}**：序列化 Page 依赖 Spring Data 的 Jackson 模块，
     * 且与仓库既有列表响应风格（显式 {@code data/total/page/limit}，见
     * {@code PaymentOrchestrationController.listPayments}）不一致。</p>
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> recent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        Long merchantId = ownershipGuard.requireMerchantId(request);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        Page<AuditEventEntity> result = repository.findByMerchantId(merchantId,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "eventTime")));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", result.getContent());
        resp.put("total", result.getTotalElements());
        resp.put("page", safePage);
        resp.put("limit", safeSize);
        return ResponseEntity.ok(resp);
    }
}
