package org.nexus.gateway.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link AlertController} 单元测试 — 使用 MockMvc + Mockito（standaloneSetup，无 Spring 上下文）。
 *
 * <p>覆盖告警规则 CRUD 和告警事件查询/解决的完整 API 流程。</p>
 */
class AlertControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private AlertRuleRepository ruleRepository;
    private AlertEventRepository eventRepository;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(AlertRuleRepository.class);
        eventRepository = mock(AlertEventRepository.class);

        AlertController controller = new AlertController(ruleRepository, eventRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    // === GET /api/v1/alerts/rules ===

    @Test
    @DisplayName("GET /rules — 返回所有规则列表")
    void listRules_returnsAll() throws Exception {
        AlertRule rule1 = createRule(1L, "failed-payments", "nexus.payments.failed",
                AlertRule.Condition.GT, 10, AlertRule.Severity.CRITICAL);
        AlertRule rule2 = createRule(2L, "low-confirmations", "nexus.payments.confirmed",
                AlertRule.Condition.LT, 5, AlertRule.Severity.WARN);

        when(ruleRepository.findAll()).thenReturn(List.of(rule1, rule2));

        mockMvc.perform(get("/api/v1/alerts/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("failed-payments"))
                .andExpect(jsonPath("$[1].name").value("low-confirmations"));
    }

    // === POST /api/v1/alerts/rules ===

    @Test
    @DisplayName("POST /rules — 创建规则成功 -> 201")
    void createRule_success() throws Exception {
        AlertRule newRule = createRule(null, "new-alert", "nexus.test.metric",
                AlertRule.Condition.GT, 100, AlertRule.Severity.WARN);

        AlertRule savedRule = createRule(1L, "new-alert", "nexus.test.metric",
                AlertRule.Condition.GT, 100, AlertRule.Severity.WARN);

        when(ruleRepository.existsByName("new-alert")).thenReturn(false);
        when(ruleRepository.save(any(AlertRule.class))).thenReturn(savedRule);

        mockMvc.perform(post("/api/v1/alerts/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newRule)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("new-alert"));
    }

    @Test
    @DisplayName("POST /rules — 名称已存在 -> 409")
    void createRule_duplicateName_conflict() throws Exception {
        AlertRule newRule = createRule(null, "existing-alert", "nexus.test.metric",
                AlertRule.Condition.GT, 100, AlertRule.Severity.WARN);

        when(ruleRepository.existsByName("existing-alert")).thenReturn(true);

        mockMvc.perform(post("/api/v1/alerts/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newRule)))
                .andExpect(status().isConflict());
    }

    // === PUT /api/v1/alerts/rules/{id} ===

    @Test
    @DisplayName("PUT /rules/{id} — 更新规则成功 -> 200")
    void updateRule_success() throws Exception {
        AlertRule existing = createRule(1L, "old-name", "nexus.test.metric",
                AlertRule.Condition.GT, 10, AlertRule.Severity.WARN);
        AlertRule updates = createRule(null, "new-name", "nexus.test.metric",
                AlertRule.Condition.LT, 5, AlertRule.Severity.CRITICAL);

        when(ruleRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/alerts/rules/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updates)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("new-name"))
                .andExpect(jsonPath("$.condition").value("LT"))
                .andExpect(jsonPath("$.severity").value("CRITICAL"));
    }

    @Test
    @DisplayName("PUT /rules/{id} — 规则不存在 -> 404")
    void updateRule_notFound() throws Exception {
        when(ruleRepository.findById(999L)).thenReturn(Optional.empty());

        AlertRule updates = createRule(null, "name", "metric",
                AlertRule.Condition.GT, 1, AlertRule.Severity.WARN);

        mockMvc.perform(put("/api/v1/alerts/rules/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updates)))
                .andExpect(status().isNotFound());
    }

    // === DELETE /api/v1/alerts/rules/{id} ===

    @Test
    @DisplayName("DELETE /rules/{id} — 删除成功 -> 204")
    void deleteRule_success() throws Exception {
        when(ruleRepository.existsById(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/alerts/rules/1"))
                .andExpect(status().isNoContent());

        verify(ruleRepository).deleteById(1L);
    }

    @Test
    @DisplayName("DELETE /rules/{id} — 规则不存在 -> 404")
    void deleteRule_notFound() throws Exception {
        when(ruleRepository.existsById(999L)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/alerts/rules/999"))
                .andExpect(status().isNotFound());

        verify(ruleRepository, never()).deleteById(any());
    }

    // === GET /api/v1/alerts/events ===

    @Test
    @DisplayName("GET /events — 返回最近告警事件")
    void listEvents_returnsRecent() throws Exception {
        AlertEvent event1 = createEvent(1L, "failed-payments", "nexus.payments.failed",
                15.0, 10.0, AlertRule.Severity.CRITICAL);
        AlertEvent event2 = createEvent(2L, "low-confirmations", "nexus.payments.confirmed",
                3.0, 5.0, AlertRule.Severity.WARN);

        when(eventRepository.findByTimestampAfterOrderByTimestampDesc(any(LocalDateTime.class)))
                .thenReturn(List.of(event1, event2));

        mockMvc.perform(get("/api/v1/alerts/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].ruleName").value("failed-payments"));
    }

    // === POST /api/v1/alerts/events/{id}/resolve ===

    @Test
    @DisplayName("POST /events/{id}/resolve — 标记已解决 -> 200")
    void resolveEvent_success() throws Exception {
        AlertEvent event = createEvent(1L, "failed-payments", "nexus.payments.failed",
                15.0, 10.0, AlertRule.Severity.CRITICAL);

        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(eventRepository.save(any(AlertEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/alerts/events/1/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true));
    }

    @Test
    @DisplayName("POST /events/{id}/resolve — 事件不存在 -> 404")
    void resolveEvent_notFound() throws Exception {
        when(eventRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/alerts/events/999/resolve"))
                .andExpect(status().isNotFound());
    }

    // === Helpers ===

    private AlertRule createRule(Long id, String name, String metricName,
                                  AlertRule.Condition condition, double threshold,
                                  AlertRule.Severity severity) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setName(name);
        rule.setMetricName(metricName);
        rule.setCondition(condition);
        rule.setThreshold(threshold);
        rule.setSeverity(severity);
        rule.setWindowMinutes(5);
        rule.setCooldownMinutes(10);
        rule.setEnabled(true);
        return rule;
    }

    private AlertEvent createEvent(Long id, String ruleName, String metricName,
                                    double currentValue, double threshold,
                                    AlertRule.Severity severity) {
        AlertEvent event = new AlertEvent();
        event.setId(id);
        event.setRuleName(ruleName);
        event.setMetricName(metricName);
        event.setCurrentValue(currentValue);
        event.setThreshold(threshold);
        event.setSeverity(severity);
        event.setMessage("test message");
        event.setTimestamp(LocalDateTime.now());
        event.setResolved(false);
        return event;
    }
}