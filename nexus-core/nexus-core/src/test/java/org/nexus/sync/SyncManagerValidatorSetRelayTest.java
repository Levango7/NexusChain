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

    // ==================== PLAN-001 签名扩展（切片 2）接线用例 ====================

    private static java.security.KeyPair ed25519() throws Exception {
        return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String rawSeedHex(java.security.KeyPair kp) {
        byte[] der = kp.getPrivate().getEncoded();
        return java.util.HexFormat.of().formatHex(java.util.Arrays.copyOfRange(der, der.length - 32, der.length));
    }

    private static String rawPubHex(java.security.KeyPair kp) {
        byte[] der = kp.getPublic().getEncoded();
        return java.util.HexFormat.of().formatHex(java.util.Arrays.copyOfRange(der, der.length - 32, der.length));
    }

    private static String signAdd(String address, String pub, String stake, long nonce,
                                  java.security.KeyPair kp) {
        return new org.nexus.sync.validator.ValidatorSetSignatureVerifier().sign(
                ValidatorSetCodec.canonicalSigningBytes(ValidatorSetCodec.ACTION_ADD, address, pub, stake, nonce),
                rawSeedHex(kp));
    }

    @Test
    void signedInvalidNotRelayedNorRegistered() throws Exception {
        java.security.KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        String sig = signAdd("addr-X", pub, "1000", 1_000L, kp);
        // 篡改质押额（签名仍来自原载荷）→ 验签失败 → 不注册、不中继
        byte[] tampered = ValidatorSetCodec.encodeAdd("addr-X", pub, "999999", 1_000L, sig);
        handle(tampered);
        assertNull(registry.getValidator("addr-X"), "验签失败的消息不得注册");
        verify(server, never()).broadcast(any(NexusChainOuterClass.Transactions.class));
    }

    @Test
    void signedReplayWithOlderNonceDroppedAtWiring() throws Exception {
        java.security.KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        // 新消息（nonce=2000）：受理 + 中继 + 推进水位
        byte[] fresh = ValidatorSetCodec.encodeAdd("addr-X", pub, "2000",
                2_000L, signAdd("addr-X", pub, "2000", 2_000L, kp));
        handle(fresh);
        assertNotNull(registry.getValidator("addr-X"));
        verify(server, times(1)).broadcast(any(NexusChainOuterClass.Transactions.class));

        reset(server);
        // 历史消息重放（nonce=1500，签名对它自身有效；载荷指纹不同 → 不依赖 LRU 防环）
        byte[] replayed = ValidatorSetCodec.encodeAdd("addr-X", pub, "1500",
                1_500L, signAdd("addr-X", pub, "1500", 1_500L, kp));
        handle(replayed);
        verify(server, never()).broadcast(any(NexusChainOuterClass.Transactions.class));
    }

    @Test
    void unsignedDroppedWhenSignatureEnforced() throws Exception {
        set("validatorSetSignatureEnforce", true);
        byte[] payload = ValidatorSetCodec.encodeAdd("addr-Y", "pubkey-Y", "1000");
        handle(payload);
        assertNull(registry.getValidator("addr-Y"), "enforce 模式下未签名消息不得注册");
        verify(server, never()).broadcast(any(NexusChainOuterClass.Transactions.class));
    }

    // ==================== PLAN-001 切片 4：回放签名语义 ====================

    @Test
    void fullTableReplayReusesStoredSignedPayloadVerbatim() throws Exception {
        registry.register("addr-B", "pubkey-B", new BigDecimal("1000"), 0.1);
        registry.register("addr-C", "pubkey-C", new BigDecimal("1000"), 0.1);

        // addr-B 有落库的原始签名载荷（v2，含 nonce+signature）；addr-C 无（老行）
        java.security.KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        long nonce = 9_000L;
        byte[] signedV2 = ValidatorSetCodec.encodeAdd("addr-B", pub, "1000", nonce,
                signAdd("addr-B", pub, "1000", nonce, kp));
        var persistence = mock(org.nexus.consensus.finality.persistence.ValidatorSetPersistence.class);
        when(persistence.loadSignedPayloads(anyInt())).thenReturn(java.util.Map.of(
                "addr-B", java.util.Base64.getEncoder().encodeToString(signedV2)));
        set("validatorSetPersistence", persistence);

        syncManager.replayValidatorSetTable();

        ArgumentCaptor<NexusChainOuterClass.Transactions> captor =
                ArgumentCaptor.forClass(NexusChainOuterClass.Transactions.class);
        verify(server, times(1)).broadcast(captor.capture());
        NexusChainOuterClass.Transactions replay = captor.getValue();
        assertEquals(2, replay.getTransactionsCount());
        byte[] b = replay.getTransactions(0).getPayload().toByteArray();
        assertArrayEquals(signedV2, b, "有存量的行必须逐字节复用原始签名载荷");
        assertTrue(ValidatorSetCodec.decode(b).hasSignature(), "复用载荷必须带签名");
        byte[] c = replay.getTransactions(1).getPayload().toByteArray();
        assertArrayEquals(ValidatorSetCodec.encodeAdd("addr-C", "pubkey-C", "1000"), c,
                "无存量的老行退化为无签名重建（兼容模式可受理）");
    }

    @Test
    void signedAddStoresRawPayloadForReplay() throws Exception {
        var persistence = mock(org.nexus.consensus.finality.persistence.ValidatorSetPersistence.class);
        set("validatorSetPersistence", persistence);

        java.security.KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        long nonce = 1_234L;
        byte[] signed = ValidatorSetCodec.encodeAdd("addr-Z", pub, "1000", nonce,
                signAdd("addr-Z", pub, "1000", nonce, kp));
        handle(signed);

        String expected = java.util.Base64.getEncoder().encodeToString(signed);
        verify(persistence).upsert(org.mockito.ArgumentMatchers.eq("addr-Z"),
                org.mockito.ArgumentMatchers.eq(pub),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("1000")),
                org.mockito.ArgumentMatchers.eq(nonce),
                org.mockito.ArgumentMatchers.eq(expected));
    }

    // ==================== PLAN-032：rest 验证人集分发（同一来源/同一路径） ====================

    @Test
    void restPayloadEntryProcessesSignedAddThroughSamePath() throws Exception {
        java.security.KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        long nonce = 7_000L;
        byte[] signed = ValidatorSetCodec.encodeAdd("addr-R", pub, "1000", nonce,
                signAdd("addr-R", pub, "1000", nonce, kp));

        // rest 客户端入口（HTTP 分发到达）：与 grpc handle 同一处理路径
        syncManager.onValidatorSetPayload(signed);

        assertNotNull(registry.getValidator("addr-R"), "rest 到达的签名 add 必须注册");
        verify(server, times(1)).broadcast(any(NexusChainOuterClass.Transactions.class));
    }

    @Test
    void restPayloadListReusesStoredSignedPayloadVerbatim() throws Exception {
        registry.register("addr-B", "pubkey-B", new BigDecimal("1000"), 0.1);
        registry.register("addr-C", "pubkey-C", new BigDecimal("1000"), 0.1);

        java.security.KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        long nonce = 9_500L;
        byte[] signedV2 = ValidatorSetCodec.encodeAdd("addr-B", pub, "1000", nonce,
                signAdd("addr-B", pub, "1000", nonce, kp));
        var persistence = mock(org.nexus.consensus.finality.persistence.ValidatorSetPersistence.class);
        when(persistence.loadSignedPayloads(anyInt())).thenReturn(java.util.Map.of(
                "addr-B", java.util.Base64.getEncoder().encodeToString(signedV2)));
        set("validatorSetPersistence", persistence);

        java.util.List<byte[]> payloads = syncManager.validatorSetReplayPayloadsForRest();

        assertEquals(2, payloads.size());
        assertArrayEquals(signedV2, payloads.get(0), "REST 分发与 grpc 回放同源：存量载荷逐字节复用");
        assertTrue(ValidatorSetCodec.decode(payloads.get(0)).hasSignature());
        assertArrayEquals(ValidatorSetCodec.encodeAdd("addr-C", "pubkey-C", "1000"), payloads.get(1),
                "老行退化为无签名重建（与 grpc 回放一致）");
    }
}
