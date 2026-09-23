package org.nexus.gateway.sla;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SlaController 集成测试。
 *
 * <p>测试 SLA 管理 API 的 CRUD 操作、测量记录查询和报告生成接口。
 * 使用 H2 内存数据库和 test profile，无外部依赖。</p>
 */
@SpringBootTest

@ActiveProfiles("test")
@Transactional
class SlaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SlaTargetRepository targetRepository;

    @Autowired
    private SlaMeasurementRepository measurementRepository;

    @BeforeEach
    void setUp() {
        measurementRepository.deleteAll();
        targetRepository.deleteAll();
    }

    // === SLA 目标 CRUD 测试 ===

    @Test
    @DisplayName("POST /api/v1/sla/targets — 创建 SLA 目标")
    void testCreateTarget() throws Exception {
        SlaTarget target = new SlaTarget();
        target.setName("Test Availability");
        target.setMetricName("nexus.test");
        target.setTargetValue(99.5);
        target.setTargetType(SlaTarget.TargetType.AVAILABILITY);
        target.setWindowMinutes(30);
        target.setEnabled(true);

        mockMvc.perform(post("/api/v1/sla/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(target)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Availability"))
                .andExpect(jsonPath("$.metricName").value("nexus.test"))
                .andExpect(jsonPath("$.targetValue").value(99.5))
                .andExpect(jsonPath("$.targetType").value("AVAILABILITY"))
                .andExpect(jsonPath("$.windowMinutes").value(30));
    }

    @Test
    @DisplayName("POST /api/v1/sla/targets — 缺少必填字段返回 400")
    void testCreateTargetMissingFields() throws Exception {
        SlaTarget target = new SlaTarget();
        target.setName("Incomplete");
        // 缺少 metricName 和 targetType

        mockMvc.perform(post("/api/v1/sla/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(target)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/sla/targets — 列出所有 SLA 目标")
    void testListTargets() throws Exception {
        createAndSaveTarget("Target 1", "nexus.metric1",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);
        createAndSaveTarget("Target 2", "nexus.metric2",
                5000, SlaTarget.TargetType.LATENCY, 30);

        mockMvc.perform(get("/api/v1/sla/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Target 1"))
                .andExpect(jsonPath("$[1].name").value("Target 2"));
    }

    @Test
    @DisplayName("PUT /api/v1/sla/targets/{id} — 更新 SLA 目标")
    void testUpdateTarget() throws Exception {
        SlaTarget saved = createAndSaveTarget("Original", "nexus.original",
                99.0, SlaTarget.TargetType.AVAILABILITY, 60);

        SlaTarget update = new SlaTarget();
        update.setName("Updated Name");
        update.setMetricName("nexus.updated");
        update.setTargetValue(99.9);
        update.setTargetType(SlaTarget.TargetType.AVAILABILITY);
        update.setWindowMinutes(120);
        update.setEnabled(false);

        mockMvc.perform(put("/api/v1/sla/targets/" + saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.metricName").value("nexus.updated"))
                .andExpect(jsonPath("$.targetValue").value(99.9))
                .andExpect(jsonPath("$.windowMinutes").value(120))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("PUT /api/v1/sla/targets/{id} — 不存在的 ID 返回 404")
    void testUpdateTargetNotFound() throws Exception {
        SlaTarget update = new SlaTarget();
        update.setName("Updated");
        update.setMetricName("nexus.updated");
        update.setTargetValue(99.9);
        update.setTargetType(SlaTarget.TargetType.AVAILABILITY);
        update.setWindowMinutes(60);

        mockMvc.perform(put("/api/v1/sla/targets/99999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/sla/targets/{id} — 删除 SLA 目标")
    void testDeleteTarget() throws Exception {
        SlaTarget saved = createAndSaveTarget("To Delete", "nexus.delete",
                99.0, SlaTarget.TargetType.AVAILABILITY, 60);

        mockMvc.perform(delete("/api/v1/sla/targets/" + saved.getId()))
                .andExpect(status().isNoContent());

        assertFalse(targetRepository.findById(saved.getId()).isPresent());
    }

    @Test
    @DisplayName("DELETE /api/v1/sla/targets/{id} — 不存在的 ID 返回 404")
    void testDeleteTargetNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/sla/targets/99999"))
                .andExpect(status().isNotFound());
    }

    // === 测量记录查询测试 ===

    @Test
    @DisplayName("GET /api/v1/sla/measurements — 查询所有测量记录")
    void testQueryAllMeasurements() throws Exception {
        SlaTarget target = createAndSaveTarget("Test", "nexus.test",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);
        createAndSaveMeasurement(target.getId(), 99.95, 99.9, true);
        createAndSaveMeasurement(target.getId(), 98.0, 99.9, false);

        mockMvc.perform(get("/api/v1/sla/measurements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/sla/measurements?targetId= — 按目标 ID 过滤")
    void testQueryMeasurementsByTargetId() throws Exception {
        SlaTarget target1 = createAndSaveTarget("T1", "nexus.t1",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);
        SlaTarget target2 = createAndSaveTarget("T2", "nexus.t2",
                5000, SlaTarget.TargetType.LATENCY, 30);

        createAndSaveMeasurement(target1.getId(), 99.95, 99.9, true);
        createAndSaveMeasurement(target2.getId(), 3000, 5000, true);

        mockMvc.perform(get("/api/v1/sla/measurements").param("targetId", target1.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].targetId").value(target1.getId()));
    }

    // === 报告生成测试 ===

    @Test
    @DisplayName("GET /api/v1/sla/report — 生成日报")
    void testGenerateDailyReport() throws Exception {
        SlaTarget target = createAndSaveTarget("Availability", "nexus.test",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);
        createAndSaveMeasurement(target.getId(), 99.95, 99.9, true);
        createAndSaveMeasurement(target.getId(), 98.0, 99.9, false);

        String today = java.time.LocalDate.now().toString();

        mockMvc.perform(get("/api/v1/sla/report")
                        .param("period", "daily")
                        .param("from", today)
                        .param("to", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("daily"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.overallComplianceRate").isNumber())
                .andExpect(jsonPath("$.summary").isString());
    }

    // === 仪表盘测试 ===

    @Test
    @DisplayName("GET /api/v1/sla/dashboard — 获取仪表盘数据")
    void testDashboard() throws Exception {
        SlaTarget target1 = createAndSaveTarget("Availability", "nexus.avail",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);
        SlaTarget target2 = createAndSaveTarget("Latency", "nexus.latency",
                5000, SlaTarget.TargetType.LATENCY, 30);

        createAndSaveMeasurement(target1.getId(), 99.95, 99.9, true);
        createAndSaveMeasurement(target2.getId(), 6000, 5000, false);

        mockMvc.perform(get("/api/v1/sla/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets").isArray())
                .andExpect(jsonPath("$.totalTargets").isNumber())
                .andExpect(jsonPath("$.metTargets").isNumber())
                .andExpect(jsonPath("$.breachedTargets").isNumber());
    }

    // === 辅助方法 ===

    private SlaTarget createAndSaveTarget(String name, String metricName,
                                           double targetValue,
                                           SlaTarget.TargetType targetType,
                                           int windowMinutes) {
        SlaTarget target = new SlaTarget();
        target.setName(name);
        target.setMetricName(metricName);
        target.setTargetValue(targetValue);
        target.setTargetType(targetType);
        target.setWindowMinutes(windowMinutes);
        target.setEnabled(true);
        return targetRepository.save(target);
    }

    private void createAndSaveMeasurement(Long targetId, double measuredValue,
                                          double targetValue, boolean isMet) {
        SlaMeasurement measurement = new SlaMeasurement();
        measurement.setTargetId(targetId);
        measurement.setMeasuredValue(measuredValue);
        measurement.setTargetValue(targetValue);
        measurement.setMet(isMet);
        measurement.setMeasuredAt(LocalDateTime.now());
        measurement.setWindowStart(LocalDateTime.now().minusMinutes(60));
        measurement.setWindowEnd(LocalDateTime.now());
        measurementRepository.save(measurement);
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}