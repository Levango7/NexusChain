package org.nexus.gateway.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 *   <li>权限从严：回到 {@link org.nexus.gateway.reconciliation.DiscrepancyResolutionController}
 *       同款（MERCHANT 或 ADMIN）。审计含资金操作明细，属敏感数据；</li>
 *   <li>三视图：按商户 / 按动作 / 按时间窗——覆盖"查某商户"、"查某类资金动作"、
 *       "查某个时段"三类实际排障场景。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/audit/events")
@PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
public class AuditEventController {

    private final AuditEventRepository repository;

    public AuditEventController(AuditEventRepository repository) {
        this.repository = repository;
    }

    /** 按商户查（默认倒序，最新在前）。 */
    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<AuditEventEntity>> byMerchant(@PathVariable Long merchantId) {
        return ResponseEntity.ok(repository.findByMerchantIdOrderByEventTimeDesc(merchantId));
    }

    /** 按动作查（如 REFUND_EXECUTED / CHANNEL_REFUND_EXECUTED / AUTO_WITHDRAW_EXECUTED）。 */
    @GetMapping("/action/{action}")
    public ResponseEntity<List<AuditEventEntity>> byAction(@PathVariable String action) {
        return ResponseEntity.ok(repository.findByActionOrderByEventTimeDesc(action));
    }

    /** 按时间窗查（排障用：传入 ISO-8601 时间，如 2026-10-11T00:00:00Z）。 */
    @GetMapping("/window")
    public ResponseEntity<List<AuditEventEntity>> byWindow(
            @RequestParam Instant from,
            @RequestParam Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from 必须早于 to");
        }
        return ResponseEntity.ok(repository.findByEventTimeBetween(from, to));
    }

    /**
     * 分页兜底视图（数据量大时用；default size=50 防误拉全表）。
     *
     * <p>刻意**不直接返回 {@code Page}**：序列化 Page 依赖 Spring Data 的 Jackson 模块，
     * 且与仓库既有列表响应风格（显式 {@code data/total/page/limit}，见
     * {@code PaymentOrchestrationController.listPayments}）不一致。</p>
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> recent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        Page<AuditEventEntity> result = repository.findAll(
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "eventTime")));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", result.getContent());
        resp.put("total", result.getTotalElements());
        resp.put("page", safePage);
        resp.put("limit", safeSize);
        return ResponseEntity.ok(resp);
    }
}
