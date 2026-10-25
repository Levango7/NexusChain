package org.nexus.bridge.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息中继循环测试（PLAN-037 切片 2）。
 *
 * <p>锁定：① 按 (sourceChain, nonce) 顺序消费；② 签名不足 → FAILED 且不执行；
 * ③ 执行开关关闭时只验签观察；④ 开启 + 有适配器 → 执行并记账（幂等不重跑）。</p>
 */
class MessageRelayWorkerTest {

    private InMemoryMessageStore store;
    private MessageRelayer relayer;
    private MessageExecutor executor;
    private MessageConfig config;
    private MessageRelayWorker worker;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryMessageStore();
        MessageFormatter formatter = new MessageFormatter();
        config = new MessageConfig();
        config.setRequiredSignatures(1);
        relayer = new MessageRelayer(formatter, store, config);
        executor = new MessageExecutor(formatter, store, config,
                java.util.Map.of("L2-fake", (org.nexus.bridge.adapter.ChainAdapter) new org.nexus.bridge.adapter.ChainAdapter() {
                    @Override public String getChainId() { return "L2-fake"; }
                    @Override public long getBlockHeight() { return 7L; }
                    @Override public String sendTransaction(byte[] tx) { return "0xdead"; }
                    @Override public Object getTransactionReceipt(String hash) { return "0x01"; }
                    @Override public String callContract(String address, String data) { return "0x"; }
                }));
    }

    private CrossChainMessage newMessage(String id, String chain, long nonce, String payload) {
        MessageFormatter formatter = new MessageFormatter();
        CrossChainMessage m = formatter.formatMessage(
                chain, "L2-fake", "0xsrc", "0xdst",
                new MessagePayload(MessagePayload.Type.ARBITRARY, payload).encode(),
                nonce, System.currentTimeMillis() / 1000L);
        m.setMessageId(id); // formatMessage 已算过；这里显式固定便于断言
        return m;
    }

    @Test
    void consumesPendingInSourceChainNonceOrder() throws Exception {
        java.security.KeyPair kp = MessageRelayer.generateKeyPair();
        String pk = MessageRelayer.privateKeyToHex(kp);
        // relayer 的顺序保证要求同链 nonce 严格递增 → 按 1/2/3 灌
        relayer.relayMessage(newMessage("m-1", "L1", 1L, "a"), pk);
        relayer.relayMessage(newMessage("m-2", "L1", 2L, "b"), pk);
        relayer.relayMessage(newMessage("m-3", "L1", 3L, "c"), pk);

        // findPending 必须按 (sourceChain, nonce) 升序返回
        java.util.List<CrossChainMessage> pending = store.findPending(50);
        assertEquals(3, pending.size());
        assertEquals("m-1", pending.get(0).getMessageId());
        assertEquals("m-2", pending.get(1).getMessageId());
        assertEquals("m-3", pending.get(2).getMessageId());

        worker = new MessageRelayWorker(store, relayer, executor, config, 50, false);
        assertEquals(3, worker.runOnce(), "三条待处理应全部消费");
    }

    @Test
    void belowThresholdIsMarkedFailedAndNotExecuted() {
        // 直接入库 0 签消息：绕过 relayer 的阈值/顺序前置检查（正路径不会到这），
        // 专门验证 worker 的"签名不足 → FAILED，不执行"兜底。
        CrossChainMessage msg = newMessage("m-1", "L1", 1L, "a");
        store.save(msg);

        worker = new MessageRelayWorker(store, relayer, executor, config, 50, true);
        worker.runOnce();
        assertEquals(MessageStatus.FAILED, store.findById("m-1").get().getStatus(),
                "签名不足必须置 FAILED，不得执行");
        assertFalse(store.getExecutionTxHash("m-1").isPresent());
    }

    @Test
    void executionDisabledOnlyObserves() throws Exception {
        java.security.KeyPair kp = MessageRelayer.generateKeyPair();
        // relayMessage = 签名 + 入库一体（生产中继正路径）；不再预 save
        relayer.relayMessage(newMessage("m-1", "L1", 1L, "a"), MessageRelayer.privateKeyToHex(kp));

        worker = new MessageRelayWorker(store, relayer, executor, config, 50, false);
        worker.runOnce();
        assertFalse(store.getExecutionTxHash("m-1").isPresent(),
                "execute-enabled=false 不得产真实交易");
    }

    @Test
    void executionEnabledRecordsTxHashOnce() throws Exception {
        java.security.KeyPair kp = MessageRelayer.generateKeyPair();
        relayer.relayMessage(newMessage("m-1", "L1", 1L, "a"), MessageRelayer.privateKeyToHex(kp));

        worker = new MessageRelayWorker(store, relayer, executor, config, 50, true);
        worker.runOnce();
        assertNotNull(store.getExecutionTxHash("m-1").orElse(null),
                "开启执行 + 有适配器必须记账");
        assertEquals(MessageStatus.EXECUTED, store.findById("m-1").get().getStatus());

        // 幂等：已执行的消息不再出现在待处理里
        assertEquals(0, worker.runOnce(), "执行完成后不应再次处理");
    }

    @Test
    void storageOutageReturnsZeroWithoutThrowing() {
        store.save(newMessage("m-1", "L1", 1L, "a"));
        MessageStore broken = new MessageStore() {
            @Override public boolean save(CrossChainMessage m) { return false; }
            @Override public java.util.Optional<CrossChainMessage> findById(String id) { return java.util.Optional.empty(); }
            @Override public boolean existsById(String id) { return false; }
            @Override public long getMaxNonce(String chain) { return -1L; }
            @Override public boolean recordExecution(String id, String tx) { return false; }
            @Override public java.util.Optional<String> getExecutionTxHash(String id) { return java.util.Optional.empty(); }
            @Override public void clear() { }
            @Override public java.util.List<CrossChainMessage> findPending(int limit) {
                throw new RuntimeException("connection reset");
            }
        };
        worker = new MessageRelayWorker(broken, relayer, executor, config, 50, false);
        assertEquals(0, worker.runOnce(), "存储故障记 0，不得把异常抛给调度器");
    }
}
