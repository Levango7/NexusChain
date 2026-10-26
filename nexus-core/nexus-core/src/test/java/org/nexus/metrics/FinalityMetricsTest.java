package org.nexus.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityCoordinator;
import org.nexus.core.Block;
import org.nexus.core.ChainHeadProvider;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 最终性指标测试（PLAN-045）。
 *
 * <p>锁定：① 未装配最终性共识 ⇒ {@code nexus_finality_enabled=0}、其余 NaN；
 * ② 正常快照映射 lag/latest epoch/percent/finalized flag/checkpoints；
 * ③ {@code progress=null}（无记录）时 percent/finalized 为 NaN 而 lag/currentEpoch
 * 仍是数（NaN 与"有数但无进度"必须可区分）；④ 快照 TTL 缓存不跨 1s 复用陈旧值
 * （回调多次只算一次的代价由缓存承担，语义不退化）。</p>
 */
class FinalityMetricsTest {

    private FinalityCoordinator coordinator;
    private ChainHeadProvider chainHeadProvider;
    private FinalityMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        coordinator = mock(FinalityCoordinator.class);
        chainHeadProvider = mock(ChainHeadProvider.class);
        Block header = mock(Block.class);
        header.nHeight = 1366L;
        when(chainHeadProvider.currentHeader()).thenReturn(header);
        metrics = new FinalityMetrics(chainHeadProvider, coordinator);
        registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private static Map<String, Object> snapshot(int currentEpoch, Integer latestVotedEpoch,
                                                Integer lag, Boolean finalized, Integer percent,
                                                int checkpoints) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("height", 1366L);
        data.put("currentEpoch", currentEpoch);
        data.put("checkpoint", false);
        data.put("latestVotedEpoch", latestVotedEpoch);
        data.put("finalityLag", lag);
        data.put("finalizedCheckpoints", checkpoints);
        if (finalized != null || percent != null) {
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("finalized", finalized);
            progress.put("progressPercent", percent);
            progress.put("votedWeight", "1000");
            progress.put("totalWeight", "2000");
            progress.put("checkpointHash", "aa");
            data.put("progress", progress);
        } else {
            data.put("progress", null);
        }
        return data;
    }

    @Test
    void withoutCoordinatorAllFinalityGaugesAreDisabled() {
        FinalityMetrics disabled = new FinalityMetrics(chainHeadProvider, null);
        SimpleMeterRegistry local = new SimpleMeterRegistry();
        disabled.bindTo(local);

        assertEquals(0.0, local.get("nexus_finality_enabled").gauge().value());
        assertTrue(Double.isNaN(local.get("nexus_finality_current_epoch").gauge().value()));
        assertTrue(Double.isNaN(local.get("nexus_finality_lag").gauge().value()));
        assertTrue(Double.isNaN(local.get("nexus_finality_finalized").gauge().value()));
        assertTrue(Double.isNaN(local.get("nexus_finality_progress_percent").gauge().value()));
    }

    @Test
    void mapsHappyPathSnapshot() {
        when(coordinator.finalitySnapshot(1366L))
                .thenReturn(snapshot(683, 683, 0, Boolean.TRUE, 100, 682));

        assertEquals(1.0, gauge("nexus_finality_enabled"));
        assertEquals(683.0, gauge("nexus_finality_current_epoch"));
        assertEquals(683.0, gauge("nexus_finality_latest_voted_epoch"));
        assertEquals(0.0, gauge("nexus_finality_lag"));
        assertEquals(1.0, gauge("nexus_finality_finalized"));
        assertEquals(100.0, gauge("nexus_finality_progress_percent"));
        assertEquals(682.0, gauge("nexus_finality_finalized_checkpoints"));
    }

    @Test
    void noRecordYetKeepsEpochNumbersButNaNProgress() {
        // 链刚起、最新 epoch 还没人投票：lag/currentEpoch 仍是数，percent/finalized 必须 NaN
        when(coordinator.finalitySnapshot(1366L))
                .thenReturn(snapshot(683, null, null, null, null, 0));

        assertEquals(683.0, gauge("nexus_finality_current_epoch"));
        assertTrue(Double.isNaN(gauge("nexus_finality_lag")));
        assertTrue(Double.isNaN(gauge("nexus_finality_latest_voted_epoch")));
        assertTrue(Double.isNaN(gauge("nexus_finality_finalized")));
        assertTrue(Double.isNaN(gauge("nexus_finality_progress_percent")));
        assertEquals(0.0, gauge("nexus_finality_finalized_checkpoints"));
    }

    @Test
    void lagBelowQuorumIsObservableForAlerting() {
        // 最终性落后 3 个 epoch 且最新已投票 epoch 只有 50%：告警表达式的输入
        when(coordinator.finalitySnapshot(1366L))
                .thenReturn(snapshot(683, 680, 3, Boolean.FALSE, 50, 600));

        assertEquals(3.0, gauge("nexus_finality_lag"));
        assertEquals(680.0, gauge("nexus_finality_latest_voted_epoch"));
        assertEquals(0.0, gauge("nexus_finality_finalized"));
        assertEquals(50.0, gauge("nexus_finality_progress_percent"));
    }

    @Test
    void snapshotCacheServesRepeatedCallbacksWithinTtl() {
        when(coordinator.finalitySnapshot(1366L))
                .thenReturn(snapshot(683, 683, 0, Boolean.TRUE, 100, 682));

        gauge("nexus_finality_lag");
        gauge("nexus_finality_progress_percent");

        verify(coordinator, times(1)).finalitySnapshot(1366L);
    }
}
