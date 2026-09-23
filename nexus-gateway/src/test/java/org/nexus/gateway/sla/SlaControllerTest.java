package org.nexus.gateway.sla;

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
 * SlaController 单元测试 — 使用 MockMvc + Mockito（standaloneSetup，无 Spring 上下文）。
 *
 * <p>与项目中 LimitControllerTest、AlertControllerTest 保持一致的测试策略。
 * 所有依赖通过 Mockito mock，不依赖数据库或 Spring 容器。</p>
 */
class SlaControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private SlaTargetRepository targetRepository;
    private SlaMeasurementRepository measurementRepository;
    private SlaReportService reportService;

    @BeforeEach
    void setUp() {
        targetRepository = mock(SlaTargetRepository.class);
        measurementRepository = mock(SlaMeasurementRepository.class);
        reportService = mock(SlaReportService.class);

        SlaController controller = new SlaController(targetRepository, measurementRepository, reportService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
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

        SlaTarget saved = cloneTarget(target);
        saved.setId(1L);
        when(targetRepository.save(any(SlaTarget.class))).thenReturn(saved);

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
        SlaTarget target1 = createTarget(1L, "Target 1", "nexus.metric1",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);
        SlaTarget target2 = createTarget(2L, "Target 2", "nexus.metric2",
                5000, SlaTarget.TargetType.LATENCY, 30);

        when(targetRepository.findAll()).thenReturn(List.of(target1, target2));

        mockMvc.perform(get("/api/v1/sla/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Target 1"))
                .andExpect(jsonPath("$[1].name").value("Target 2"));
    }

    @Test
    @DisplayName("PUT /api/v1/sla/targets/{id} — 更新 SLA 目标")
    void testUpdateTarget() throws Exception {
        SlaTarget existing = createTarget(1L, "Original", "nexus.original",
                99.0, SlaTarget.TargetType.AVAILABILITY, 60);

        SlaTarget update = new SlaTarget();
        update.setName("Updated Name");
        update.setMetricName("nexus.updated");
        update.setTargetValue(99.9);
        update.setTargetType(SlaTarget.TargetType.AVAILABILITY);
        update.setWindowMinutes(120);
        update.setEnabled(false);

        when(targetRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(targetRepository.save(any(SlaTarget.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/sla/targets/1")
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
        when(targetRepository.findById(99999L)).thenReturn(Optional.empty());

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
        SlaTarget existing = createTarget(1L, "To Delete", "nexus.delete",
                99.0, SlaTarget.TargetType.AVAILABILITY, 60);

        when(targetRepository.findById(1L)).thenReturn(Optional.of(existing));
        doNothing().when(targetRepository).delete(any(SlaTarget.class));

        mockMvc.perform(delete("/api/v1/sla/targets/1"))
                .andExpect(status().isNoContent());

        verify(targetRepository).delete(existing);
    }

    @Test
    @DisplayName("DELETE /api/v1/sla/targets/{id} — 不存在的 ID 返回 404")
    void testDeleteTargetNotFound() throws Exception {
        when(targetRepository.findById(99999L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/sla/targets/99999"))
                .andExpect(status().isNotFound());
    }

    // === 测量记录查询测试 ===

    @Test
    @DisplayName("GET /api/v1/sla/measurements — 查询所有测量记录")
    void testQueryAllMeasurements() throws Exception {
        SlaMeasurement m1 = createMeasurement(1L, 1L, 99.95, 99.9, true);
        SlaMeasurement m2 = createMeasurement(2L, 1L, 98.0, 99.9, false);

        when(measurementRepository.findAll()).thenReturn(List.of(m1, m2));

        mockMvc.perform(get("/api/v1/sla/measurements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/sla/measurements?targetId= — 按目标 ID 过滤")
    void testQueryMeasurementsByTargetId() throws Exception {
        SlaMeasurement m1 = createMeasurement(1L, 1L, 99.95, 99.9, true);

        when(measurementRepository.findByTargetIdOrderByMeasuredAtDesc(1L))
                .thenReturn(List.of(m1));

        mockMvc.perform(get("/api/v1/sla/measurements").param("targetId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].targetId").value(1));
    }

    // === 报告生成测试 ===

    @Test
    @DisplayName("GET /api/v1/sla/report — 生成日报")
    void testGenerateDailyReport() throws Exception {
        SlaReportService.SlaReport report = new SlaReportService.SlaReport();
        report.setPeriod("daily");
        report.setItems(List.of());
        report.setOverallComplianceRate(95.0);
        report.setSummary("All good");

        when(reportService.generateReport(any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(report);

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
        SlaTarget target1 = createTarget(1L, "Availability", "nexus.avail",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);
        SlaTarget target2 = createTarget(2L, "Latency", "nexus.latency",
                5000, SlaTarget.TargetType.LATENCY, 30);

        when(targetRepository.findByEnabledTrue()).thenReturn(List.of(target1, target2));

        SlaMeasurement m1 = createMeasurement(1L, 1L, 99.95, 99.9, true);
        SlaMeasurement m2 = createMeasurement(2L, 2L, 6000, 5000, false);

        when(measurementRepository.findByTargetIdOrderByMeasuredAtDesc(1L))
                .thenReturn(List.of(m1));
        when(measurementRepository.findByTargetIdOrderByMeasuredAtDesc(2L))
                .thenReturn(List.of(m2));

        mockMvc.perform(get("/api/v1/sla/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets").isArray())
                .andExpect(jsonPath("$.totalTargets").isNumber())
                .andExpect(jsonPath("$.metTargets").isNumber())
                .andExpect(jsonPath("$.breachedTargets").isNumber());
    }

    // === 辅助方法 ===

    private SlaTarget createTarget(Long id, String name, String metricName,
                                     double targetValue,
                                     SlaTarget.TargetType targetType,
                                     int windowMinutes) {
        SlaTarget target = new SlaTarget();
        target.setId(id);
        target.setName(name);
        target.setMetricName(metricName);
        target.setTargetValue(targetValue);
        target.setTargetType(targetType);
        target.setWindowMinutes(windowMinutes);
        target.setEnabled(true);
        return target;
    }

    private SlaTarget cloneTarget(SlaTarget source) {
        SlaTarget target = new SlaTarget();
        target.setName(source.getName());
        target.setMetricName(source.getMetricName());
        target.setTargetValue(source.getTargetValue());
        target.setTargetType(source.getTargetType());
        target.setWindowMinutes(source.getWindowMinutes());
        target.setEnabled(source.isEnabled());
        return target;
    }

    private SlaMeasurement createMeasurement(Long id, Long targetId,
                                                double measuredValue,
                                                double targetValue, boolean isMet) {
        SlaMeasurement measurement = new SlaMeasurement();
        measurement.setId(id);
        measurement.setTargetId(targetId);
        measurement.setMeasuredValue(measuredValue);
        measurement.setTargetValue(targetValue);
        measurement.setMet(isMet);
        measurement.setMeasuredAt(LocalDateTime.now());
        measurement.setWindowStart(LocalDateTime.now().minusMinutes(60));
        measurement.setWindowEnd(LocalDateTime.now());
        return measurement;
    }
}
