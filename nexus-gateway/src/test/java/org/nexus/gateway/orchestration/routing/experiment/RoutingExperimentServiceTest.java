package org.nexus.gateway.orchestration.routing.experiment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.RoutingWave16Properties;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RoutingExperimentService} 单元测试：确定性分流、统计聚合、z 检验、生命周期校验。
 */
class RoutingExperimentServiceTest {

    private RoutingExperimentRepository repository;
    private RoutingWave16Properties properties;
    private RoutingExperimentService service;

    private static final String EXP = "exp_test";

    @BeforeEach
    void setUp() {
        repository = mock(RoutingExperimentRepository.class);
        properties = new RoutingWave16Properties();
        service = new RoutingExperimentService(repository, properties);
    }

    private RoutingExperiment experiment(String controlJson, String groupsJson) {
        RoutingExperiment experiment = new RoutingExperiment();
        experiment.setExperimentId(EXP);
        experiment.setName("test");
        experiment.setControlGroupJson(controlJson);
        experiment.setExperimentGroupsJson(groupsJson);
        experiment.setTargetMetric("SUCCESS_RATE");
        experiment.setSignificanceThreshold(0.05);
        experiment.setMinSampleSize(100);
        return experiment;
    }

    // ==================== 分流 ====================

    @Test
    @DisplayName("assign: 同 paymentId 分配结果确定；RUNNING 才参与分流")
    void assignmentDeterministic() {
        when(repository.findById(EXP)).thenReturn(Optional.of(experiment(
                "{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[{\"groupId\":\"g1\",\"weight\":50,\"connectorIds\":[\"mock\"]}]")));

        // 非 RUNNING → 空
        assertTrue(service.assign(EXP, "pay-1").isEmpty());

        RoutingExperiment running = experiment(
                "{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[{\"groupId\":\"g1\",\"weight\":50,\"connectorIds\":[\"mock\"]}]");
        running.setStatus("RUNNING");
        when(repository.findById(EXP)).thenReturn(Optional.of(running));

        Optional<RoutingExperimentService.Assignment> first = service.assign(EXP, "pay-1");
        assertTrue(first.isPresent());
        assertEquals(first, service.assign(EXP, "pay-1"));
        // 大样本下两组都应被命中（50/50）
        long controlCount = 0;
        long groupCount = 0;
        for (int i = 0; i < 200; i++) {
            Optional<RoutingExperimentService.Assignment> a = service.assign(EXP, "pay-" + i);
            if (a.isPresent() && a.get().control()) controlCount++;
            else groupCount++;
        }
        assertTrue(controlCount > 50 && groupCount > 50, "50/50 分流应两组均有流量: c=" + controlCount + ", g=" + groupCount);
    }

    @Test
    @DisplayName("activeAssignment: 只返回实验组命中；对照组返回空")
    void activeAssignmentSkipsControl() {
        RoutingExperiment running = experiment(
                "{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[{\"groupId\":\"g1\",\"weight\":100,\"connectorIds\":[\"mock\"]}]");
        running.setStatus("RUNNING");
        when(repository.findByStatus("RUNNING")).thenReturn(List.of(running));

        // 找一个落在 g1（bucket ≥ 0，controlWeight=0 → 全部进 g1）的 paymentId
        Optional<RoutingExperimentService.Assignment> hit = service.activeAssignment("pay-1");
        assertTrue(hit.isPresent());
        assertEquals("g1", hit.get().groupId());
        assertEquals(List.of("mock"), hit.get().connectorIds());

        // 对照组实验（0 权重实验组）→ 恒空
        RoutingExperiment controlOnly = experiment(
                "{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[{\"groupId\":\"g1\",\"weight\":0,\"connectorIds\":[\"mock\"]}]");
        controlOnly.setStatus("RUNNING");
        when(repository.findByStatus("RUNNING")).thenReturn(List.of(controlOnly));
        assertTrue(service.activeAssignment("pay-1").isEmpty());
    }

    // ==================== 统计与显著性 ====================

    @Test
    @DisplayName("evaluate: 样本不足 → INSUFFICIENT_SAMPLE；显著差异 → WINNER")
    void evaluateFlow() {
        RoutingExperiment exp = experiment(
                "{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[{\"groupId\":\"g1\",\"weight\":100,\"connectorIds\":[\"mock\"]}]");
        exp.setStatus("RUNNING");
        exp.setMinSampleSize(10);
        when(repository.findById(EXP)).thenReturn(Optional.of(exp));

        // 样本不足
        for (int i = 0; i < 5; i++) {
            service.recordOutcome(EXP, "control", false, 100, 10);
            service.recordOutcome(EXP, "g1", false, 100, 10);
        }
        assertEquals(RoutingExperimentService.Evaluation.CONCLUSION_INSUFFICIENT,
                service.evaluate(EXP).conclusion());

        // control 成功率 0.1，g1 成功率 0.9 → 显著
        for (int i = 0; i < 100; i++) {
            service.recordOutcome(EXP, "control", i < 10, 100, 10);
            service.recordOutcome(EXP, "g1", i < 90, 100, 10);
        }
        RoutingExperimentService.Evaluation evaluation = service.evaluate(EXP);
        assertEquals(RoutingExperimentService.Evaluation.CONCLUSION_WINNER, evaluation.conclusion());
        assertEquals("g1", evaluation.winnerGroupId());
        assertTrue(evaluation.pValue() < 0.05);
    }

    @Test
    @DisplayName("evaluate: 无差异 → NO_SIGNIFICANT_DIFFERENCE；LATENCY 目标 → 仅报均值")
    void evaluateNoDifferenceAndMeansOnly() {
        RoutingExperiment exp = experiment(
                "{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[{\"groupId\":\"g1\",\"weight\":100,\"connectorIds\":[\"mock\"]}]");
        exp.setStatus("RUNNING");
        exp.setMinSampleSize(10);
        when(repository.findById(EXP)).thenReturn(Optional.of(exp));

        for (int i = 0; i < 50; i++) {
            service.recordOutcome(EXP, "control", i < 25, 100, 10);
            service.recordOutcome(EXP, "g1", i < 25, 100, 10);
        }
        assertEquals(RoutingExperimentService.Evaluation.CONCLUSION_NO_DIFFERENCE,
                service.evaluate(EXP).conclusion());

        exp.setTargetMetric("LATENCY");
        assertEquals(RoutingExperimentService.Evaluation.CONCLUSION_MEANS_ONLY,
                service.evaluate(EXP).conclusion());
    }

    @Test
    @DisplayName("twoProportionZTestPValue: 完全相同比例 → p≈1；剧烈差异 → p 极小")
    void zTestMath() {
        double pSame = RoutingExperimentService.twoProportionZTestPValue(0.5, 1000, 0.5, 1000);
        // erf 数值近似（A&S 7.1.26，误差 ~1e-7）使 p 在 1 附近有微小偏差
        assertEquals(1.0, pSame, 1e-6);
        double pExtreme = RoutingExperimentService.twoProportionZTestPValue(0.1, 1000, 0.9, 1000);
        assertTrue(pExtreme < 1e-10);
    }

    // ==================== 生命周期 ====================

    @Test
    @DisplayName("生命周期: CREATED→RUNNING→PAUSED→COMPLETED；非法流转被拒绝")
    void lifecycleTransitions() {
        RoutingExperiment exp = experiment("{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[]");
        exp.setExperimentId(EXP);
        when(repository.findById(EXP)).thenReturn(Optional.of(exp));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(IllegalStateException.class, () -> service.pause(EXP));
        assertEquals("RUNNING", service.start(EXP).getStatus());
        assertNotNull(exp.getStartTime());
        assertEquals("PAUSED", service.pause(EXP).getStatus());
        assertEquals("COMPLETED", service.finish(EXP, true).getStatus());
        assertNotNull(exp.getEndTime());
        assertThrows(IllegalStateException.class, () -> service.start(EXP));
    }

    @Test
    @DisplayName("create: 权重和超 100 / 阈值越界 / 组缺 groupId 被拒绝")
    void createValidation() {
        RoutingExperiment overWeight = experiment("{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[{\"groupId\":\"g1\",\"weight\":60},{\"groupId\":\"g2\",\"weight\":60}]");
        assertThrows(IllegalArgumentException.class, () -> service.create(overWeight, "op"));

        RoutingExperiment badThreshold = experiment("{\"groupId\":\"control\",\"connectorIds\":[]}", "[]");
        badThreshold.setSignificanceThreshold(1.5);
        assertThrows(IllegalArgumentException.class, () -> service.create(badThreshold, "op"));

        RoutingExperiment noGroupId = experiment("{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[{\"weight\":30}]");
        assertThrows(IllegalArgumentException.class, () -> service.create(noGroupId, "op"));

        RoutingExperiment ok = experiment("{\"groupId\":\"control\",\"connectorIds\":[]}",
                "[{\"groupId\":\"g1\",\"weight\":30,\"connectorIds\":[\"mock\"]}]");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertEquals("CREATED", service.create(ok, "op").getStatus());
    }
}
