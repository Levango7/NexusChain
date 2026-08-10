package org.nexus.bridge.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.bridge.adapter.ChainAdapter;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨链消息传递端到端集成测试。
 *
 * <p>模拟 Solana ↔ NEX 与 Avalanche ↔ NEX 双向消息传递完整流程，
 * 验证 {@link MessageFormatter} / {@link MessageRelayer} / {@link MessageExecutor}
 * 三层组件的协同工作能力。</p>
 *
 * <h2>测试场景</h2>
 * <ul>
 *   <li>场景 1：Solana → NEX 代币转移消息（多签中继 + 目标链执行）</li>
 *   <li>场景 2：NEX → Solana 合约调用消息（反向）</li>
 *   <li>场景 3：Avalanche → NEX 任意数据消息</li>
 *   <li>场景 4：NEX → Avalanche 代币转移消息（反向）</li>
 *   <li>场景 5：多消息顺序保证（同源链 nonce 单调递增）</li>
 *   <li>场景 6：去重保护（重复消息拒绝）</li>
 *   <li>场景 7：多签不足拒绝执行</li>
 * </ul>
 *
 * @since 1.9.2
 */
class CrossChainMessageIntegrationTest {

    private MessageFormatter formatter;
    private InMemoryMessageStore store;
    private MessageConfig config;
    private MessageRelayer relayer;
    private MessageExecutor executor;

    /** 模拟链适配器。 */
    private StubChainAdapter nexusAdapter;
    private StubChainAdapter solanaAdapter;
    private StubChainAdapter avalancheAdapter;

    /** 多 relayer 密钥对（模拟 3-of-3 多签网络，本测试用 2-of-3）。 */
    private List<KeyPair> relayerKeyPairs;
    private List<String> relayerPrivKeys;
    private List<String> relayerPubKeys;

    @BeforeEach
    void setUp() {
        formatter = new MessageFormatter();
        store = new InMemoryMessageStore();
        config = new MessageConfig();
        config.setEnabled(true);
        config.setRequiredSignatures(2);
        config.setMessageTimeout(3600);
        config.setMaxPayloadSize(32768);

        // 生成 3 个 relayer 密钥对
        relayerKeyPairs = new ArrayList<>();
        relayerPrivKeys = new ArrayList<>();
        relayerPubKeys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            KeyPair kp = MessageRelayer.generateKeyPair();
            relayerKeyPairs.add(kp);
            relayerPrivKeys.add(MessageRelayer.privateKeyToHex(kp));
            relayerPubKeys.add(MessageRelayer.publicKeyToHex(kp));
        }

        relayer = new MessageRelayer(formatter, store, config);

        // 初始化链适配器
        nexusAdapter = new StubChainAdapter("nexus");
        solanaAdapter = new StubChainAdapter("solana-mainnet");
        avalancheAdapter = new StubChainAdapter("avalanche");

        Map<String, ChainAdapter> adapters = new HashMap<>();
        adapters.put("nexus", nexusAdapter);
        adapters.put("solana-mainnet", solanaAdapter);
        adapters.put("avalanche", avalancheAdapter);

        executor = new MessageExecutor(formatter, store, config, adapters);

        // 注册目标合约白名单
        executor.registerContract("nexus", "0xNexusBridge");
        executor.registerContract("solana-mainnet", "TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN");
        executor.registerContract("avalanche", "0xAvalancheBridge");
    }

    // ==================== 场景 1: Solana → NEX 代币转移 ====================

    @Test
    @DisplayName("场景1: Solana → NEX 代币转移消息端到端")
    void scenario1_solanaToNex_tokenTransfer() {
        // 1. 构造消息
        MessagePayload payload = new MessagePayload(MessagePayload.Type.TOKEN_TRANSFER,
                "{\"amount\":1000000,\"from\":\"SolOwner\",\"to\":\"0xNexRecipient\",\"token\":\"SOL\"}");
        CrossChainMessage msg = formatter.formatMessage(
                "solana-mainnet", "nexus",
                "TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN",
                "0xNexusBridge",
                payload, 1L, System.currentTimeMillis() / 1000);

        assertEquals(MessageStatus.PENDING, msg.getStatus());

        // 2. 多 relayer 签名中继（2-of-3）
        MessageRelayRecord record1 = relayer.relayMessage(msg, relayerPrivKeys.get(0));
        assertEquals(MessageStatus.RELAYED, msg.getStatus());
        assertEquals(1, msg.signatureCount());

        // 第二个 relayer 签名（绕过 store 去重，直接签名累积）
        addSignatureFromRelayer(msg, relayerKeyPairs.get(1));
        assertEquals(2, msg.signatureCount());

        // 3. 多签验证
        boolean verified = relayer.verifySignatures(msg, 2, relayerPubKeys);
        assertTrue(verified, "2-of-3 多签应验证通过");

        // 4. 在 NEX 链执行
        String txHash = executor.executeMessage(msg);
        assertNotNull(txHash);
        assertEquals(MessageStatus.EXECUTED, msg.getStatus());

        // 5. 验证执行记录
        Optional<String> recorded = store.getExecutionTxHash(msg.getMessageId());
        assertTrue(recorded.isPresent());
        assertEquals(txHash, recorded.get());
        assertEquals(1, nexusAdapter.getSubmittedTransactions().size());
    }

    // ==================== 场景 2: NEX → Solana 合约调用 ====================

    @Test
    @DisplayName("场景2: NEX → Solana 合约调用消息端到端")
    void scenario2_nexToSolana_contractCall() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.CONTRACT_CALL,
                "{\"function\":\"updateState\",\"args\":[\"key1\",\"value1\"]}");
        CrossChainMessage msg = formatter.formatMessage(
                "nexus", "solana-mainnet",
                "0xNexusBridge",
                "TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN",
                payload, 1L, System.currentTimeMillis() / 1000);

        // 中继 + 多签
        relayer.relayMessage(msg, relayerPrivKeys.get(0));
        addSignatureFromRelayer(msg, relayerKeyPairs.get(1));
        assertTrue(relayer.verifySignatures(msg, 2, relayerPubKeys));

        // 执行
        String txHash = executor.executeMessage(msg);
        assertEquals(MessageStatus.EXECUTED, msg.getStatus());
        assertEquals(1, solanaAdapter.getSubmittedTransactions().size());
        assertNotNull(txHash);
    }

    // ==================== 场景 3: Avalanche → NEX 任意数据 ====================

    @Test
    @DisplayName("场景3: Avalanche → NEX 任意数据消息端到端")
    void scenario3_avalancheToNex_arbitraryData() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.ARBITRARY,
                "custom-business-payload: governance vote proposal #42");
        CrossChainMessage msg = formatter.formatMessage(
                "avalanche", "nexus",
                "0xAvalancheBridge",
                "0xNexusBridge",
                payload, 1L, System.currentTimeMillis() / 1000);

        relayer.relayMessage(msg, relayerPrivKeys.get(0));
        addSignatureFromRelayer(msg, relayerKeyPairs.get(1));
        assertTrue(relayer.verifySignatures(msg, 2, relayerPubKeys));

        String txHash = executor.executeMessage(msg);
        assertEquals(MessageStatus.EXECUTED, msg.getStatus());
        assertEquals(1, nexusAdapter.getSubmittedTransactions().size());
        assertNotNull(txHash);
    }

    // ==================== 场景 4: NEX → Avalanche 代币转移 ====================

    @Test
    @DisplayName("场景4: NEX → Avalanche 代币转移消息端到端")
    void scenario4_nexToAvalanche_tokenTransfer() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.TOKEN_TRANSFER,
                "{\"amount\":500000,\"from\":\"0xNexSender\",\"to\":\"0xAvalRecipient\",\"token\":\"NEX\"}");
        CrossChainMessage msg = formatter.formatMessage(
                "nexus", "avalanche",
                "0xNexusBridge",
                "0xAvalancheBridge",
                payload, 1L, System.currentTimeMillis() / 1000);

        relayer.relayMessage(msg, relayerPrivKeys.get(0));
        addSignatureFromRelayer(msg, relayerKeyPairs.get(1));
        assertTrue(relayer.verifySignatures(msg, 2, relayerPubKeys));

        String txHash = executor.executeMessage(msg);
        assertEquals(MessageStatus.EXECUTED, msg.getStatus());
        assertEquals(1, avalancheAdapter.getSubmittedTransactions().size());
        assertNotNull(txHash);
    }

    // ==================== 场景 5: 多消息顺序保证 ====================

    @Test
    @DisplayName("场景5: 同源链多消息 nonce 顺序保证")
    void scenario5_multipleMessages_orderPreserved() {
        String sourceChain = "solana-mainnet";
        long baseTs = System.currentTimeMillis() / 1000;

        // 按顺序中继 3 条消息
        for (int i = 1; i <= 3; i++) {
            MessagePayload payload = new MessagePayload(MessagePayload.Type.TOKEN_TRANSFER,
                    "{\"seq\":" + i + "}");
            CrossChainMessage msg = formatter.formatMessage(
                    sourceChain, "nexus",
                    "TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN",
                    "0xNexusBridge",
                    payload, i, baseTs);

            relayer.relayMessage(msg, relayerPrivKeys.get(0));
            addSignatureFromRelayer(msg, relayerKeyPairs.get(1));
            executor.executeMessage(msg);
            assertEquals(MessageStatus.EXECUTED, msg.getStatus());
        }

        // 第 4 条消息 nonce=4 应通过顺序检查
        assertTrue(relayer.checkOrder(sourceChain, 4L));
        // nonce=3 应失败（已用）
        assertFalse(relayer.checkOrder(sourceChain, 3L));
        // nonce=2 应失败
        assertFalse(relayer.checkOrder(sourceChain, 2L));

        assertEquals(3, nexusAdapter.getSubmittedTransactions().size());
        assertEquals(3L, store.getMaxNonce(sourceChain));
    }

    // ==================== 场景 6: 去重保护 ====================

    @Test
    @DisplayName("场景6: 重复消息应被拒绝")
    void scenario6_duplicateMessage_rejected() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.ARBITRARY, "data");
        CrossChainMessage msg = formatter.formatMessage(
                "avalanche", "nexus",
                "0xAvalancheBridge", "0xNexusBridge",
                payload, 1L, System.currentTimeMillis() / 1000);

        // 首次中继成功
        relayer.relayMessage(msg, relayerPrivKeys.get(0));
        assertTrue(relayer.checkDuplicate(msg.getMessageId()));

        // 构造相同 messageId 的消息尝试再次中继
        CrossChainMessage dup = formatter.formatMessage(
                "avalanche", "nexus",
                "0xAvalancheBridge", "0xNexusBridge",
                new MessagePayload(MessagePayload.Type.ARBITRARY, "data"),
                1L, msg.getTimestamp());
        dup.setMessageId(msg.getMessageId());

        assertThrows(IllegalStateException.class, () ->
                relayer.relayMessage(dup, relayerPrivKeys.get(0)));
    }

    // ==================== 场景 7: 多签不足拒绝执行 ====================

    @Test
    @DisplayName("场景7: 多签不足应拒绝执行")
    void scenario7_insufficientSignatures_rejected() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.TOKEN_TRANSFER,
                "{\"amount\":100,\"to\":\"0xabc\"}");
        CrossChainMessage msg = formatter.formatMessage(
                "solana-mainnet", "nexus",
                "TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN",
                "0xNexusBridge",
                payload, 1L, System.currentTimeMillis() / 1000);

        // 仅 1 个 relayer 签名（要求 2）
        relayer.relayMessage(msg, relayerPrivKeys.get(0));
        assertEquals(1, msg.signatureCount());

        // 多签验证应失败
        assertFalse(relayer.verifySignatures(msg, 2, relayerPubKeys));

        // 执行应失败（签名不足）
        assertThrows(IllegalArgumentException.class, () -> executor.executeMessage(msg));
        assertEquals(MessageStatus.FAILED, msg.getStatus());
    }

    // ==================== 场景 8: 编码 / 解码跨链传输模拟 ====================

    @Test
    @DisplayName("场景8: 消息编码→传输→解码→执行 模拟跨进程传递")
    void scenario8_encodeTransmitDecodeExecute() {
        // 源端构造消息
        MessagePayload payload = new MessagePayload(MessagePayload.Type.CONTRACT_CALL,
                "{\"action\":\"updateConfig\",\"param\":\"newValue\"}");
        CrossChainMessage original = formatter.formatMessage(
                "avalanche", "nexus",
                "0xAvalancheBridge", "0xNexusBridge",
                payload, 99L, System.currentTimeMillis() / 1000);

        // 源端签名
        relayer.relayMessage(original, relayerPrivKeys.get(0));
        addSignatureFromRelayer(original, relayerKeyPairs.get(1));

        // 编码（模拟网络传输）
        String wire = formatter.encode(original);

        // 目标端解码
        CrossChainMessage decoded = formatter.decodeMessage(wire);
        // 签名不在传输编码中，需单独传递（此处直接复制）
        decoded.setSignatures(original.getSignatures());

        // 验证解码后消息可执行
        assertEquals(original.getMessageId(), decoded.getMessageId());
        assertEquals(original.getNonce(), decoded.getNonce());
        assertEquals(original.getSourceChain(), decoded.getSourceChain());

        // 重新存储（模拟目标端从存储恢复）
        store.save(decoded);
        String txHash = executor.executeMessage(decoded);
        assertEquals(MessageStatus.EXECUTED, decoded.getStatus());
        assertNotNull(txHash);
    }

    // ==================== 辅助方法 ====================

    /**
     * 用指定 relayer 密钥对消息追加签名（绕过 store 去重，模拟多 relayer 签名聚合）。
     */
    private void addSignatureFromRelayer(CrossChainMessage msg, KeyPair relayerKeys) {
        try {
            byte[] signingBytes = formatter.encodeForSigning(msg);
            java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
            sig.initSign(relayerKeys.getPrivate());
            sig.update(signingBytes);
            msg.addSignature(java.util.HexFormat.of().formatHex(sig.sign()));
        } catch (Exception e) {
            fail("Failed to add signature: " + e.getMessage());
        }
    }

    // ==================== 测试用 Stub ChainAdapter ====================

    static class StubChainAdapter implements ChainAdapter {

        private final String chainId;
        private final java.util.List<byte[]> submittedTransactions = new java.util.ArrayList<>();

        StubChainAdapter(String chainId) {
            this.chainId = chainId;
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
            return 1000L;
        }

        @Override
        public String sendTransaction(byte[] tx) {
            submittedTransactions.add(tx);
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