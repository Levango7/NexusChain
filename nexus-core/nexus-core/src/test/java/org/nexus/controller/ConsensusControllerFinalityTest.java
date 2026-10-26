package org.nexus.controller;

import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.ChainHeadProvider;
import org.nexus.encoding.JSONEncodeDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GET /consensus/finality（PLAN-044）：只读内存链头 + 协调器快照，不查数据库；
 * 未装配最终性共识时返回 enabled=false。
 */
public class ConsensusControllerFinalityTest {

    @Test
    void encodesFinalitySnapshotWithoutTouchingChain() {
        ConsensusController controller = new ConsensusController();
        ChainHeadProvider provider = mock(ChainHeadProvider.class);
        JSONEncodeDecoder codec = mock(JSONEncodeDecoder.class);
        org.nexus.consensus.finality.FinalityCoordinator coordinator =
                mock(org.nexus.consensus.finality.FinalityCoordinator.class);
        Block header = mock(Block.class);
        header.nHeight = 1366L;

        when(provider.currentHeader()).thenReturn(header);
        when(codec.encode(any())).thenReturn(new byte[]{0x02});
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("height", 1366L);
        snapshot.put("checkpoint", true);
        when(coordinator.finalitySnapshot(1366L)).thenReturn(snapshot);

        ReflectionTestUtils.setField(controller, "chainHeadProvider", provider);
        ReflectionTestUtils.setField(controller, "codec", codec);
        ReflectionTestUtils.setField(controller, "finalityCoordinator", coordinator);

        Object result = controller.getFinalityStatus();

        assertArrayEquals(new byte[]{0x02}, (byte[]) result);
        ArgumentCaptor<Map> captured = ArgumentCaptor.forClass(Map.class);
        verify(codec).encode(captured.capture());
        assertEquals(true, captured.getValue().get("enabled"));
        assertEquals(1366L, captured.getValue().get("height"));
    }

    @Test
    void withoutCoordinatorReportsDisabledButStillHeight() {
        ConsensusController controller = new ConsensusController();
        ChainHeadProvider provider = mock(ChainHeadProvider.class);
        JSONEncodeDecoder codec = mock(JSONEncodeDecoder.class);
        Block header = mock(Block.class);
        header.nHeight = 42L;

        when(provider.currentHeader()).thenReturn(header);
        when(codec.encode(any())).thenReturn(new byte[]{0x03});

        ReflectionTestUtils.setField(controller, "chainHeadProvider", provider);
        ReflectionTestUtils.setField(controller, "codec", codec);

        Object result = controller.getFinalityStatus();

        assertArrayEquals(new byte[]{0x03}, (byte[]) result);
        ArgumentCaptor<Map> captured = ArgumentCaptor.forClass(Map.class);
        verify(codec).encode(captured.capture());
        assertFalse((Boolean) captured.getValue().get("enabled"));
        assertEquals(42L, captured.getValue().get("height"));
        assertTrue(captured.getValue().containsKey("height"));
    }
}
