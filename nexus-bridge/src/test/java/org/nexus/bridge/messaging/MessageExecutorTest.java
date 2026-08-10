package org.nexus.bridge.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.bridge.adapter.ChainAdapter;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MessageExecutor} 单元测试：覆盖消息验证、执行、记录与合约白名单。
 *
 * <p>使用简化的 {@link StubChainAdapter} 模拟目标链交互，避免真实 RPC 依赖。</p>
 */
class MessageExecutorTest {

    private MessageFormatter formatter;
    private InMemoryMessageStore store;
    private MessageConfig config;
    private StubChainAdapter nexusAdapter;
    private StubChainAdapter ethAdapter;
    private MessageExecutor executor;

    @BeforeEach
    void setUp() {
        formatter = new MessageFormatter();
        store = new InMemoryMessageStore();
        config = new MessageConfig();
        config.setRequiredSignatures(2);
        config.setMessageTimeout(3600);
        config.setMaxPayloadSize(32768);

        nexusAdapter = new StubChainAdapter("nexus");
        ethAdapter = new StubChainAdapter("ethereum");
        Map<String, ChainAdapter> adapters = new HashMap<>();
        adapters.put("nexus", nexusAdapter);
        adapters.put("ethereum", ethAdapter);

        executor = new MessageExecutor(formatter, store, config, adapters);
    }

    // ==================== validateMessage 测试 ====================

    @Test
    @DisplayName("validateMessage: 满足所有条件应通过")
    void validateMessage_valid_returnsTrue() {
        CrossChainMessage msg = buildSignedMessage("solana-mainnet", "nexus", 1L, 2);
        executor.registerContract("nexus", msg.getTargetContract());

        assertTrue(executor.validateMessage(msg));
    }

    @Test
    @DisplayName("validateMessage: null 消息返回 false")
    void validateMessage_null_returnsFalse() {
        assertFalse(executor.validateMessage(null));
    }

    @Test
    @DisplayName("validateMessage: 签名数不足返回 false")
    void validateMessage_insufficientSigs_returnsFalse() {
        CrossChainMessage msg = buildSignedMessage("a", "nexus", 1L, 1); // 仅 1 签名
        assertFalse(executor.validateMessage(msg));
    }

    @Test
    @DisplayName("validateMessage: 过期消息返回 false")
    void validateMessage_expired_returnsFalse() {
        CrossChainMessage msg = buildSignedMessage("a", "nexus", 1L, 2);
        msg.setTimestamp(System.currentTimeMillis() / 1000 - 7200);
        assertFalse(executor.validateMessage(msg));
    }

    @Test
    @DisplayName("validateMessage: 目标合约不在白名单返回 false")
    void validateMessage_unregisteredContract_returnsFalse() {
        CrossChainMessage msg = buildSignedMessage("a", "nexus", 1L, 2);
        executor.registerContract("nexus", "0xdifferentContract");
        assertFalse(executor.validateMessage(msg));
    }

    @Test
    @DisplayName("validateMessage: 目标链无适配器返回 false")
    void validateMessage_noAdapter_returnsFalse() {
        CrossChainMessage msg = buildSignedMessage("a", "unknown-chain", 1L, 2);
        assertFalse(executor.validateMessage(msg));
    }

    @Test
    @DisplayName("validateMessage: 白名单为空时不检查合约")
    void validateMessage_emptyWhitelist_skipsContractCheck() {
        CrossChainMessage msg = buildSignedMessage("a", "nexus", 1L, 2);
        // 不注册任何合约
        assertTrue(executor.validateMessage(msg));
    }

    // ==================== executeMessage 测试 ====================

    @Test
    @DisplayName("executeMessage: 验证通过应提交到目标链并记录")
    void executeMessage_valid_submitsAndRecords() {
        CrossChainMessage msg = buildSignedMessage("solana-mainnet", "nexus", 1L, 2);
        store.save(msg);

        String txHash = executor.executeMessage(msg);

        assertNotNull(txHash);
        assertTrue(txHash.startsWith("0x"));
        assertEquals(MessageStatus.EXECUTED, msg.getStatus());
        assertEquals(1, nexusAdapter.getSubmittedTransactions().size());

        Optional<String> recorded = store.getExecutionTxHash(msg.getMessageId());
        assertTrue(recorded.isPresent());
        assertEquals(txHash, recorded.get());
    }

    @Test
    @DisplayName("executeMessage: 验证失败应抛异常并标记 FAILED")
    void executeMessage_validationFails_throwsAndMarksFailed() {
        CrossChainMessage msg = buildSignedMessage("a", "nexus", 1L, 1); // 签名不足

        assertThrows(IllegalArgumentException.class, () -> executor.executeMessage(msg));
        assertEquals(MessageStatus.FAILED, msg.getStatus());
    }

    @Test
    @DisplayName("executeMessage: 链适配器抛异常应传播并标记 FAILED")
    void executeMessage_adapterThrows_propagatesAndMarksFailed() {
        CrossChainMessage msg = buildSignedMessage("a", "nexus", 1L, 2);
        store.save(msg);
        nexusAdapter.setThrowOnSend(new RuntimeException("chain rejected"));

        assertThrows(RuntimeException.class, () -> executor.executeMessage(msg));
        assertEquals(MessageStatus.FAILED, msg.getStatus());
    }

    @Test
    @DisplayName("executeMessage: 不同目标链应路由到对应适配器")
    void executeMessage_routesToCorrectAdapter() {
        CrossChainMessage msg1 = buildSignedMessage("nexus", "ethereum", 1L, 2);
        store.save(msg1);
        executor.executeMessage(msg1);
        assertEquals(1, ethAdapter.getSubmittedTransactions().size());
        assertEquals(0, nexusAdapter.getSubmittedTransactions().size());

        CrossChainMessage msg2 = buildSignedMessage("ethereum", "nexus", 1L, 2);
        store.save(msg2);
        executor.executeMessage(msg2);
        assertEquals(1, nexusAdapter.getSubmittedTransactions().size());
    }

    // ==================== recordExecution 测试 ====================

    @Test
    @DisplayName("recordExecution: 消息存在应记录成功")
    void recordExecution_messageExists_succeeds() {
        CrossChainMessage msg = buildSignedMessage("a", "nexus", 1L, 2);
        store.save(msg);

        assertTrue(executor.recordExecution(msg.getMessageId(), "0xtxhash"));
        Optional<String> recorded = store.getExecutionTxHash(msg.getMessageId());
        assertTrue(recorded.isPresent());
        assertEquals("0xtxhash", recorded.get());
    }

    @Test
    @DisplayName("recordExecution: 消息不存在应返回 false")
    void recordExecution_messageNotExists_returnsFalse() {
        assertFalse(executor.recordExecution("0xnonexistent", "0xtxhash"));
    }

    @Test
    @DisplayName("recordExecution: null 参数返回 false")
    void recordExecution_nullParams_returnsFalse() {
        assertFalse(executor.recordExecution(null, "0xtxhash"));
        assertFalse(executor.recordExecution("0xabc", null));
    }

    @Test
    @DisplayName("getExecutionTxHash: 未执行消息返回 empty")
    void getExecutionTxHash_notExecuted_returnsEmpty() {
        Optional<String> result = executor.getExecutionTxHash("0xnonexistent");
        assertTrue(result.isEmpty());
    }

    // ==================== registerContract / addAdapter 测试 ====================

    @Test
    @DisplayName("registerContract: 注册后白名单生效")
    void registerContract_addsToWhitelist() {
        executor.registerContract("nexus", "0xcontract1");
        executor.registerContract("nexus", "0xcontract2");

        CrossChainMessage msg1 = buildSignedMessage("a", "nexus", 1L, 2);
        msg1.setTargetContract("0xcontract1");
        assertTrue(executor.validateMessage(msg1));

        CrossChainMessage msg2 = buildSignedMessage("a", "nexus", 2L, 2);
        msg2.setTargetContract("0xcontract2");
        assertTrue(executor.validateMessage(msg2));

        CrossChainMessage msg3 = buildSignedMessage("a", "nexus", 3L, 2);
        msg3.setTargetContract("0xunregistered");
        assertFalse(executor.validateMessage(msg3));
    }

    @Test
    @DisplayName("addAdapter: 添加新适配器后可执行到该链")
    void addAdapter_enablesNewTargetChain() {
        StubChainAdapter bscAdapter = new StubChainAdapter("bsc");
        executor.addAdapter("bsc", bscAdapter);

        CrossChainMessage msg = buildSignedMessage("a", "bsc", 1L, 2);
        store.save(msg);
        String txHash = executor.executeMessage(msg);

        assertNotNull(txHash);
        assertEquals(1, bscAdapter.getSubmittedTransactions().size());
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造一条已签名消息（签名数为 sigCount，签名内容为占位符）。
     */
    private CrossChainMessage buildSignedMessage(String sourceChain, String targetChain,
                                                 long nonce, int sigCount) {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.TOKEN_TRANSFER,
                "{\"amount\":1000,\"to\":\"0xrecipient\"}");
        CrossChainMessage msg = formatter.formatMessage(
                sourceChain, targetChain,
                "0xsourceContract", "0xtargetContract",
                payload, nonce, System.currentTimeMillis() / 1000);
        for (int i = 0; i < sigCount; i++) {
            msg.addSignature("0xsignature" + i);
        }
        return msg;
    }

    // ==================== 测试用 Stub ChainAdapter ====================

    /**
     * 简单的内存 ChainAdapter 实现，记录所有提交的交易。
     */
    static class StubChainAdapter implements ChainAdapter {

        private final String chainId;
        private final java.util.List<byte[]> submittedTransactions = new java.util.ArrayList<>();
        private RuntimeException throwOnSend = null;

        StubChainAdapter(String chainId) {
            this.chainId = chainId;
        }

        void setThrowOnSend(RuntimeException ex) {
            this.throwOnSend = ex;
        }

        java.util.List<byte[]> getSubmittedTransactions() {
            return submittedTransactions;
        }

        @Override
        public String getChainId() {
            return chainId;
        }

        @Override
        public long getBlockHeight() {
            return 100L;
        }

        @Override
        public String sendTransaction(byte[] tx) {
            if (throwOnSend != null) {
                throw throwOnSend;
            }
            submittedTransactions.add(tx);
            // 生成确定性 tx hash
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(tx);
                return "0x" + java.util.HexFormat.of().formatHex(hash);
            } catch (Exception e) {
                return "0x" + Integer.toHexString(tx.hashCode());
            }
        }

        @Override
        public Object getTransactionReceipt(String hash) {
            return "receipt:" + hash;
        }

        @Override
        public String callContract(String address, String data) {
            return "0xresult";
        }
    }
}