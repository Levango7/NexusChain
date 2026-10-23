package org.nexus.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.p2p.entity.GetBlockQuery;
import org.nexus.p2p.entity.Status;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PLAN-029（F2 修复）：出块节点也主动对账拉块。
 *
 * <p>背景（PLAN-027 多机实证）：{@code getStatus()} 旧行为
 * {@code if (enableMining) return;} 让出块节点永不主动补块——错过广播的块
 * 永久缺失（B 停 162、对端 164，重启后仍 162）。修复：删除门禁，
 * mining/非 mining 行为统一（对端不高于本地时窗口自然为空）。</p>
 */
class SyncClientMiningSyncTest {

    private SyncClient syncClient;
    private org.nexus.controller.RPCClient rpcClient;
    private org.nexus.consensus.pow.ConsensusConfig consensusConfig;

    @BeforeEach
    void setUp() throws Exception {
        syncClient = new SyncClient();
        rpcClient = mock(RPCClient.class);
        consensusConfig = mock(org.nexus.consensus.pow.ConsensusConfig.class);
        org.mockito.Mockito.when(consensusConfig.getPeers())
                .thenReturn(List.of("10.0.0.9:19585"));
        set("rpcClient", rpcClient);
        set("consensusConfig", consensusConfig);
    }

    private void set(String name, Object value) throws Exception {
        Field f = SyncClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(syncClient, value);
    }

    @Test
    void miningNodeStillIssuesStatusPoll() {
        // 核心断言：enableMining=true 时 getStatus() 也必须发起对端状态请求
        syncClient.enableMining = true;
        syncClient.getStatus();
        verify(rpcClient, times(1))
                .get(startsWith("http://10.0.0.9:19585"), anyMap(), any());
    }

    @Test
    void catchesBehindPeerWithClippedWindow() {
        Status remote = new Status();
        remote.currentHeight = 170L;
        GetBlockQuery q = syncClient.buildCatchUpQuery(100L, remote);
        assertNotNull(q, "对端更高 → 追块窗口必须非空");
        assertEquals(100L, q.start);
        assertEquals(149L, q.stop, "窗口 100..170 溢出 → 从起点裁剪为 100..149");
    }

    @Test
    void catchesBehindPeerWithinWindow() {
        Status remote = new Status();
        remote.currentHeight = 30L;
        GetBlockQuery q = syncClient.buildCatchUpQuery(1L, remote);
        assertNotNull(q);
        assertEquals(1L, q.start);
        assertEquals(30L, q.stop);
    }

    @Test
    void noCatchUpWhenPeerNotAheadOrInvalid() {
        assertNull(syncClient.buildCatchUpQuery(165L, null), "无效状态 → null");
        Status even = new Status();
        even.currentHeight = 165L;
        assertNull(syncClient.buildCatchUpQuery(165L, even), "对端不高 → null");
        Status behind = new Status();
        behind.currentHeight = 160L;
        assertNull(syncClient.buildCatchUpQuery(165L, behind), "对端更低 → null");
    }
}
