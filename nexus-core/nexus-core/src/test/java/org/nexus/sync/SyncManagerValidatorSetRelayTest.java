package org.nexus.sync;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.consensus.finality.net.ValidatorSetCodec;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.core.Block;
import org.nexus.db.StateDB;
import org.nexus.p2p.Context;
import org.nexus.p2p.PeerServer;
import org.nexus.p2p.NexusChainOuterClass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PLAN-030（F1 修复）：验证人集中继与全表回放。
 *
 * <p>背景（PLAN-027 多机实证）：validator-set 广播只发直连对端 ⇒ 星型拓扑下
 * C 的验证人到不了 B（C 的块在 B 处 {@code unknown proposer} 无法落库）；
 * 且 B 重启后无人重放历史 add ⇒ C 缺行靠人工插行绕行。修复两手：
 * （a）收到 add/remove 成功处理后按载荷指纹转发一次；（b）对端咨询状态
 * （onGetStatus）时回放本节点注册表全量。</p>
 */
class SyncManagerValidatorSetRelayTest {

    private SyncManager syncManager;
    private PeerServer server;
    private ValidatorRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        syncManager = new SyncManager();
        server = mock(PeerServer.class);
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        set("server", server);
        set("validatorRegistry", registry);
    }

    private void set(String name, Object value) throws Exception {
        Field f = SyncManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(syncManager, value);
    }

    private void handle(byte[] payload) throws Exception {
        Method m = SyncManager.class.getDeclaredMethod("handleValidatorSet", byte[].class);
        m.setAccessible(true);
        m.invoke(syncManager, (Object) payload);
    }

    @Test
    void relaysFirstSeenAddToPeers() throws Exception {
        byte[] payload = ValidatorSetCodec.encodeAdd("addr-C", "pubkey-C", "1000");
        handle(payload);
        assertTrue(registry.getValidator("addr-C") != null, "收到 add 应本地注册");
        ArgumentCaptor<NexusChainOuterClass.Transactions> captor =
                ArgumentCaptor.forClass(NexusChainOuterClass.Transactions.class);
        verify(server, times(1)).broadcast(captor.capture());
        NexusChainOuterClass.Transactions msg = captor.getValue();
        assertEquals(1, msg.getTransactionsCount());
        byte[] relayed = msg.getTransactions(0).getPayload().toByteArray();
        assertArrayEquals(payload, relayed, "转发载荷必须与收到载荷逐字节一致");
    }

    @Test
    void relayedPayloadNotForwardedAgain() throws Exception {
        byte[] payload = ValidatorSetCodec.encodeAdd("addr-C", "pubkey-C", "1000");
        handle(payload);    // 第一次：转发
        handle(payload);    // 第二次：同载荷 → 不再转发（防环）
        verify(server, times(1)).broadcast(any(NexusChainOuterClass.Transactions.class));
    }

    @Test
    void removeAlsoRelayedOnce() throws Exception {
        registry.register("addr-D", "pubkey-D", new BigDecimal("1000"), 0.1);
        byte[] payload = ValidatorSetCodec.encodeRemove("addr-D");
        handle(payload);
        handle(payload);
        verify(server, times(1)).broadcast(any(NexusChainOuterClass.Transactions.class));
        // unregister 的语义是置 INACTIVE（不删行）——与 ValidatorRegistry.unregister 一致
        assertEquals(org.nexus.consensus.pos.ValidatorStatus.INACTIVE,
                registry.getValidator("addr-D").getStatus(), "remove 应置 INACTIVE");
    }

    @Test
    void malformedPayloadNotRelayed() throws Exception {
        byte[] garbage = "not-a-validator-set".getBytes();
        handle(garbage);
        verify(server, never()).broadcast(any(NexusChainOuterClass.Transactions.class));
    }

    @Test
    void fullTableReplayOnGetStatus() throws Exception {
        // 注册 2 本地验证人（模拟 A 的注册表含 B/C）
        registry.register("addr-B", "pubkey-B", new BigDecimal("1000"), 0.1);
        registry.register("addr-C", "pubkey-C", new BigDecimal("1000"), 0.1);

        StateDB stateDB = mock(StateDB.class);
        Block best = mock(Block.class);
        when(best.getHash()).thenReturn(new byte[32]);
        when(stateDB.getBestBlock()).thenReturn(best);
        set("stateDB", stateDB);
        Block genesis = mock(Block.class);
        when(genesis.getHash()).thenReturn(new byte[32]);
        set("genesis", genesis);
        Context context = mock(Context.class);
        reset(server);   // 清空 setUp 后的其余交互

        Method m = SyncManager.class.getDeclaredMethod("onGetStatus", Context.class, PeerServer.class);
        m.setAccessible(true);
        m.invoke(syncManager, context, server);

        verify(context, times(1)).response(any(NexusChainOuterClass.Status.class));
        ArgumentCaptor<NexusChainOuterClass.Transactions> captor =
                ArgumentCaptor.forClass(NexusChainOuterClass.Transactions.class);
        verify(server, times(1)).broadcast(captor.capture());
        NexusChainOuterClass.Transactions replay = captor.getValue();
        assertEquals(2, replay.getTransactionsCount(), "注册表 2 人 → 单消息携带 2 条 add");
        var decoded = ValidatorSetCodec.decode(replay.getTransactions(0).getPayload().toByteArray());
        assertNotNull(decoded);
        assertTrue(decoded.isAdd());
        assertEquals("addr-B", decoded.address());
        var decoded2 = ValidatorSetCodec.decode(replay.getTransactions(1).getPayload().toByteArray());
        assertEquals("addr-C", decoded2.address());
    }

    @Test
    void noServerNoReplay() throws Exception {
        set("server", null);
        registry.register("addr-B", "pubkey-B", new BigDecimal("1000"), 0.1);
        syncManager.replayValidatorSetTable();
        verify(server, never()).broadcast(any());
    }
}
