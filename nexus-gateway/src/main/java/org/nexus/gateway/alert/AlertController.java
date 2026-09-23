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
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "Alert rule management and alert event queries")
public class AlertController {

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;

    public AlertController(AlertRuleRepository ruleRepository,
                           AlertEventRepository eventRepository) {
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
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
        return ResponseEntity.ok(ruleRepository.findAll());
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
        if (rule.getName() != null && ruleRepository.existsByName(rule.getName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        AlertRule saved = ruleRepository.save(rule);
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
        return ruleRepository.findById(id)
                .map(existing -> {
                    applyUpdates(existing, rule);
                    AlertRule saved = ruleRepository.save(existing);
                    return ResponseEntity.ok(saved);
                })
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
        if (!ruleRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        ruleRepository.deleteById(id);
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
        return ResponseEntity.ok(eventRepository.findByTimestampAfterOrderByTimestampDesc(since));
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
        return eventRepository.findById(id)
                .map(event -> {
                    event.setResolved(true);
                    event.setResolvedAt(LocalDateTime.now());
                    AlertEvent saved = eventRepository.save(event);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // === Private helpers ===

    /**
     * 将请求体中的字段应用到已有实体（部分更新）。
     */
    private void applyUpdates(AlertRule existing, AlertRule updates) {
        if (updates.getName() != null) {
            existing.setName(updates.getName());
        }
        if (updates.getMetricName() != null) {
            existing.setMetricName(updates.getMetricName());
        }
        if (updates.getCondition() != null) {
            existing.setCondition(updates.getCondition());
        }
        existing.setThreshold(updates.getThreshold());
        existing.setWindowMinutes(updates.getWindowMinutes());
        existing.setCooldownMinutes(updates.getCooldownMinutes());
        existing.setEnabled(updates.isEnabled());
        if (updates.getSeverity() != null) {
            existing.setSeverity(updates.getSeverity());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }
    }
}