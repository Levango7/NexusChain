package org.nexus.bridge.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桥消息事件源中继 worker 测试（PLAN-046）。
 *
 * <p>锁定：① 事件 → 消息 → 真实签名入库（RELAYED，1 签）；② 连续两轮 nonce 递增；
 * ③ 存储失败（save 抛错）逐事件容错、不抛出不打死循环；④ 乱序（relayer 抛错）
 * 同样跳过并计数；⑤ 快照字段是活值；⑥ 事件源 poll 失败 ⇒ 返回 0 + lastError。</p>
 */
class BridgeEventSourceWorkerTest {

    private InMemoryMessageStore store;
    private MessageRelayer relayer;
    private MessageFormatter formatter;
    private MessageConfig config;
    private BridgeEventSourceWorker worker;
    private SimulatedChainEventSource source;
    private String privateKeyHex;

    @BeforeEach
    void setUp() {
        formatter = new MessageFormatter();
        store = new InMemoryMessageStore();
        config = new MessageConfig();
        config.setRequiredSignatures(1);
        relayer = new MessageRelayer(formatter, store, config);
        source = new SimulatedChainEventSource("L1-sim", "L2-sim", "0xsrc", "0xdst", 0L);
        privateKeyHex = MessageRelayer.privateKeyToHex(MessageRelayer.generateKeyPair());
        worker = new BridgeEventSourceWorker(source, formatter, relayer, privateKeyHex, 1);
    }

    @Test
    void eventsAreSignedAndStoredAsRelayed() {
        assertEquals(1, worker.runOnce(), "batchSize=1 + 模拟源一轮最多产 1 条");

        List<CrossChainMessage> pending = store.findPending(10);
        assertEquals(1, pending.size());
        CrossChainMessage message = pending.get(0);
        assertEquals(MessageStatus.RELAYED, message.getStatus());
        assertEquals(1, message.signatureCount(), "必须带 relayer 真实签名");
        assertEquals("L1-sim", message.getSourceChain());
        assertEquals("L2-sim", message.getTargetChain());
        assertEquals(0L, message.getNonce());
        assertNotNull(store.getMaxNonce("L1-sim"));
        assertEquals(0L, store.getMaxNonce("L1-sim"));
        assertNull(worker.lastError());
    }

    @Test
    void consecutiveRoundsAdvanceNonce() {
        worker.runOnce();
        worker.runOnce();
        assertEquals(2, store.findPending(10).size());
        assertEquals(1L, store.getMaxNonce("L1-sim"), "nonce 必须随事件单调递增");
        assertEquals(1L, worker.lastNonce());
        assertEquals(2L, worker.relayedCount());
    }

    @Test
    void storageFailureIsSkippedPerEventAndLoopSurvives() {
        MessageStore flaky = new MessageStore() {
            @Override public boolean save(CrossChainMessage m) { throw new IllegalStateException("db down"); }
            @Override public java.util.Optional<CrossChainMessage> findById(String id) { return java.util.Optional.empty(); }
            @Override public boolean existsById(String id) { return false; }
            @Override public long getMaxNonce(String chain) { return -1L; }
            @Override public boolean recordExecution(String id, String tx) { return false; }
            @Override public java.util.Optional<String> getExecutionTxHash(String id) { return java.util.Optional.empty(); }
            @Override public void clear() { }
        };
        BridgeEventSourceWorker flakyWorker = new BridgeEventSourceWorker(
                new SimulatedChainEventSource("L1-sim", "L2-sim", "0xsrc", "0xdst", 0L),
                formatter, new MessageRelayer(formatter, flaky, config), privateKeyHex, 1);

        assertEquals(0, flakyWorker.runOnce(), "存储失败不得抛出");
        assertEquals(1L, flakyWorker.failedCount(), "失败必须计数");
        assertNotNull(flakyWorker.lastError(), "错误摘要必须可观测");
        assertTrue(flakyWorker.lastError().contains("db down"));
    }

    @Test
    void outOfOrderEventsAreSkippedNotFatal() {
        // 预置库里的 max nonce=5：源从 0 起产的事件必然乱序 → 逐条跳过
        CrossChainMessage ahead = formatter.formatMessage("L1-sim", "L2-sim", "0xsrc", "0xdst",
                new MessagePayload(MessagePayload.Type.ARBITRARY, "future").encode(),
                5L, System.currentTimeMillis() / 1000L);
        relayer.relayMessage(ahead, privateKeyHex);

        assertEquals(0, worker.runOnce(), "乱序事件全部被跳过");
        assertEquals(1L, worker.failedCount());
        assertTrue(worker.lastError().contains("Out-of-order"), "错误摘要需指明乱序");
        // 循环不死：再跑一轮仍然返回 0 且不抛
        assertEquals(0, worker.runOnce());
        assertEquals(2L, worker.failedCount(), "持续乱序持续计数（可观测）");
    }

    @Test
    void pollFailureReturnsZeroAndRecordsError() {
        ChainEventSource broken = new ChainEventSource() {
            @Override public List<ChainEvent> pollEvents(int max) { throw new IllegalStateException("rpc down"); }
            @Override public String getSourceChain() { return "L1-sim"; }
            @Override public String getTargetChain() { return "L2-sim"; }
        };
        BridgeEventSourceWorker brokenWorker =
                new BridgeEventSourceWorker(broken, formatter, relayer, privateKeyHex, 1);

        assertEquals(0, brokenWorker.runOnce());
        assertTrue(brokenWorker.lastError().contains("rpc down"));
        assertEquals(0L, brokenWorker.relayedCount());
    }

    @Test
    void snapshotExposesLiveCounters() {
        worker.runOnce();
        worker.runOnce();
        BridgeEventSourceStatus snapshot = worker.snapshot();
        assertTrue(snapshot.sourceEnabled());
        assertEquals("L1-sim", snapshot.sourceChain());
        assertEquals("L2-sim", snapshot.targetChain());
        assertEquals("SimulatedChainEventSource", snapshot.sourceType());
        assertEquals(2L, snapshot.pollCount());
        assertEquals(2L, snapshot.relayedCount());
        assertEquals(0L, snapshot.failedCount());
        assertEquals(1L, snapshot.lastNonce());
        assertFalse(snapshot.lastError() != null && snapshot.lastError().contains("db down"));
    }
}
