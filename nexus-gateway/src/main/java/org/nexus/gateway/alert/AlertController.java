package org.nexus.gateway.alert;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警管理 REST API。
 *
 * <p>提供告警规则的 CRUD 操作和告警事件的查询/解决功能。</p>
 *
 * <ul>
 *   <li>GET    /api/v1/alerts/rules — 列出所有告警规则</li>
 *   <li>POST   /api/v1/alerts/rules — 创建告警规则</li>
 *   <li>PUT    /api/v1/alerts/rules/{id} — 更新告警规则</li>
 *   <li>DELETE /api/v1/alerts/rules/{id} — 删除告警规则</li>
 *   <li>GET    /api/v1/alerts/events — 列出最近告警事件</li>
 *   <li>POST   /api/v1/alerts/events/{id}/resolve — 标记告警已解决</li>
 * </ul>
 *
 * <p>P1-1 架构修复：Controller 不再直接依赖 Repository，
 * 数据访问统一委托给 {@link AlertRuleService} / {@link AlertEventService}。</p>
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "Alert rule management and alert event queries")
public class AlertController {

    private final AlertRuleService ruleService;
    private final AlertEventService eventService;

    public AlertController(AlertRuleService ruleService,
                           AlertEventService eventService) {
        this.ruleService = ruleService;
        this.eventService = eventService;
    }

    // === Alert Rule CRUD ===

    /**
     * 列出所有告警规则。
     *
     * @return 全部告警规则列表
     */
    @Operation(summary = "List all alert rules")
    @GetMapping("/rules")
    public ResponseEntity<List<AlertRule>> listRules() {
        return ResponseEntity.ok(ruleService.findAll());
    }

    /**
     * 创建告警规则。
     *
     * @param rule 告警规则（不含 id）
     * @return 创建后的规则（201）
     */
    @Operation(summary = "Create a new alert rule")
    @PostMapping("/rules")
    public ResponseEntity<AlertRule> createRule(@RequestBody AlertRule rule) {
        if (rule.getName() != null && ruleService.existsByName(rule.getName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        AlertRule saved = ruleService.save(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * 更新告警规则。
     *
     * @param id 规则 ID
     * @param rule 更新内容
     * @return 更新后的规则，或 404
     */
    @Operation(summary = "Update an existing alert rule")
    @PutMapping("/rules/{id}")
    public ResponseEntity<AlertRule> updateRule(@PathVariable Long id, @RequestBody AlertRule rule) {
        return ruleService.update(id, rule)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 删除告警规则。
     *
     * @param id 规则 ID
     * @return 204 或 404
     */
    @Operation(summary = "Delete an alert rule")
    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        if (!ruleService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        ruleService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // === Alert Event Queries ===

    /**
     * 列出最近告警事件（默认最近 24 小时）。
     *
     * @return 最近告警事件列表
     */
    @Operation(summary = "List recent alert events")
    @GetMapping("/events")
    public ResponseEntity<List<AlertEvent>> listEvents() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        return ResponseEntity.ok(eventService.findRecentEvents(since));
    }

    /**
     * 标记告警事件为已解决。
     *
     * @param id 事件 ID
     * @return 更新后的事件，或 404
     */
    @Operation(summary = "Mark an alert event as resolved")
    @PostMapping("/events/{id}/resolve")
    public ResponseEntity<AlertEvent> resolveEvent(@PathVariable Long id) {
        return eventService.resolve(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
