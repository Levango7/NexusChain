package org.nexus.gateway.orchestration.routing.experiment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.config.RoutingWave16Properties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 路由实验统计持久化测试（Wave 16 模块四边界收尾，V91）。
 *
 * <p>覆盖：write-through 累加（行存在走原子 UPDATE / 行不存在插入）、
 * 启动回灌基线（evaluate 用回灌后的累计值）、持久化失败不阻断统计、
 * 未注入仓库时退化纯内存（原行为）。</p>
 */
@ExtendWith(MockitoExtension.class)
class RoutingExperimentStatsPersistenceTest {

    private static final String EXP = "exp_stats";

    @Mock private RoutingExperimentRepository repository;
    @Mock private RoutingExperimentStatRepository statRepository;

    private RoutingWave16Properties properties;

    @BeforeEach
    void setUp() {
        properties = new RoutingWave16Properties();
        lenient().when(statRepository.findAll()).thenReturn(List.of());
    }

    private RoutingExperimentStat persisted(long count, long successes, long latency, long cost) {
        RoutingExperimentStat row = new RoutingExperimentStat();
        row.setExperimentId(EXP);
        row.setGroupId("g1");
        row.setEventCount(count);
        row.setSuccessCount(successes);
        row.setTotalLatencyMs(latency);
        row.setTotalCostBps(cost);
        return row;
    }

    @Test
    @DisplayName("recordOutcome: 行不存在 → 插入首条；行存在 → 原子 UPDATE 累加")
    void writeThroughAccumulates() {
        when(statRepository.accumulate(eq(EXP), eq("g1"), anyLong(), anyLong(), anyLong()))
                .thenReturn(0)    // 首条：UPDATE 未命中 → 插入
                .thenReturn(1);   // 次条：UPDATE 命中
        RoutingExperimentService service =
                new RoutingExperimentService(repository, properties, statRepository);

        service.recordOutcome(EXP, "g1", true, 100, 10);
        verify(statRepository).save(any(RoutingExperimentStat.class));

        service.recordOutcome(EXP, "g1", false, 50, 5);
        verify(statRepository, times(2)).accumulate(eq(EXP), eq("g1"), anyLong(), anyLong(), anyLong());
        // 命中 UPDATE 后不再插入
        verify(statRepository, times(1)).save(any(RoutingExperimentStat.class));
        // 进程内统计不受持久化路径影响
        RoutingExperimentService.Evaluation evaluation = minimalEvaluate(service);
        RoutingExperimentService.GroupStat stat = evaluation.groupStats().get("g1");
        assertNotNull(stat);
        assertEquals(2, stat.count());
        assertEquals(0.5, stat.successRate(), 1e-9);
    }

    @Test
    @DisplayName("启动回灌: 持久化基线进入进程内统计，重启后继续累计")
    void startupRestoreContinuesAccumulation() {
        when(statRepository.findAll()).thenReturn(List.of(persisted(100, 90, 10_000, 500)));
        RoutingExperimentService service =
                new RoutingExperimentService(repository, properties, statRepository);

        // 重启后新增一条成功事件（持久化路径 unstubbed mock——本测试只关注进程内基线）
        service.recordOutcome(EXP, "g1", true, 0, 0);

        RoutingExperimentService.Evaluation evaluation = minimalEvaluate(service);
        // 回灌 100 条 + 新增 1 条 = 101
        assertEquals(101, evaluation.groupStats().get("g1").count());
        // 成功率 = (90 + 1) / 101
        assertEquals(91.0 / 101, evaluation.groupStats().get("g1").successRate(), 1e-9);
    }

    @Test
    @DisplayName("持久化异常: 仅告警，进程内统计与调用方不受影响")
    void persistFailureDoesNotBlock() {
        when(statRepository.accumulate(any(), any(), anyLong(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("db down"));
        RoutingExperimentService service =
                new RoutingExperimentService(repository, properties, statRepository);

        assertDoesNotThrow(() -> service.recordOutcome(EXP, "g1", true, 100, 10));
        RoutingExperimentService.Evaluation evaluation = minimalEvaluate(service);
        assertEquals(1, evaluation.groupStats().get("g1").count());
    }

    @Test
    @DisplayName("未注入仓库: 退化纯内存（原行为），零持久化调用")
    void nullRepositoryDegradesToInMemory() {
        RoutingExperimentService service = new RoutingExperimentService(repository, properties, null);
        service.recordOutcome(EXP, "g1", true, 100, 10);
        RoutingExperimentService.Evaluation evaluation = minimalEvaluate(service);
        assertEquals(1, evaluation.groupStats().get("g1").count());
        // statRepository 是 mock 且从未被该 service 引用（构造时传 null）——无交互可验
    }

    /** 构造一个最小 RUNNING 实验（对照 control + g1）供 evaluate 读取统计。 */
    private RoutingExperimentService.Evaluation minimalEvaluate(RoutingExperimentService service) {
        RoutingExperiment experiment = new RoutingExperiment();
        experiment.setExperimentId(EXP);
        experiment.setName("stats-test");
        experiment.setControlGroupJson("{\"groupId\":\"control\",\"connectorIds\":[]}");
        experiment.setExperimentGroupsJson("[{\"groupId\":\"g1\",\"weight\":100,\"connectorIds\":[\"mock\"]}]");
        experiment.setTargetMetric("SUCCESS_RATE");
        experiment.setMinSampleSize(1);
        experiment.setSignificanceThreshold(0.05);
        when(repository.findById(EXP)).thenReturn(java.util.Optional.of(experiment));
        return service.evaluate(EXP);
    }
}
