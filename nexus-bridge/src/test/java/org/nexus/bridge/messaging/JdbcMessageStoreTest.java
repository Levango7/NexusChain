package org.nexus.bridge.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.bridge.repository.BridgeMessageRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcMessageStore} 落库语义测试（PLAN-026 桥持久化）。
 *
 * <p>核心断言：与 {@link InMemoryMessageStore} 语义逐条对齐，并且状态
 * <b>跨实例存在</b>（等价于进程重启后不丢）—— 用"同一 Repository 上
 * 新建第二个 store 实例"来模拟重启。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
class JdbcMessageStoreTest {

    @Autowired
    private BridgeMessageRecordRepository repository;

    private JdbcMessageStore store;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        store = new JdbcMessageStore(repository);
    }

    private CrossChainMessage message(String id, String sourceChain, long nonce) {
        CrossChainMessage message = new CrossChainMessage(
                id, sourceChain, "nexus",
                "0xSource", "0xTarget",
                new MessagePayload(MessagePayload.Type.TOKEN_TRANSFER,
                        "amount=100;recipient=0xabc"),
                nonce,
                1_700_000_000L);
        message.addSignature("aabb");
        message.addSignature("ccdd");
        return message;
    }

    @Test
    void save_newMessage_returnsTrue_andFindByIdRoundTrips() {
        assertTrue(store.save(message("msg-1", "ethereum", 1)));

        Optional<CrossChainMessage> found = store.findById("msg-1");
        assertTrue(found.isPresent());
        CrossChainMessage restored = found.get();
        assertEquals("msg-1", restored.getMessageId());
        assertEquals("ethereum", restored.getSourceChain());
        assertEquals("nexus", restored.getTargetChain());
        assertEquals(1, restored.getNonce());
        assertEquals(MessageStatus.PENDING, restored.getStatus());
        assertEquals(2, restored.signatureCount());
        assertEquals(List.of("aabb", "ccdd"), restored.getSignatures());
        assertTrue(restored.getPayload() != null);
        assertEquals(MessagePayload.Type.TOKEN_TRANSFER, restored.getPayload().getType());
        assertEquals("amount=100;recipient=0xabc", restored.getPayload().getData());
    }

    @Test
    void save_duplicateMessageId_returnsFalse() {
        assertTrue(store.save(message("msg-dup", "ethereum", 1)));
        assertFalse(store.save(message("msg-dup", "ethereum", 2)),
                "相同 messageId 的重复消息必须被拒绝（去重语义）");
    }

    @Test
    void getMaxNonce_perChain_isolated_andEmptyChainReturnsMinusOne() {
        assertEquals(-1L, store.getMaxNonce("ethereum"), "尚无消息的链应返回 -1");

        assertTrue(store.save(message("m-eth-1", "ethereum", 5)));
        assertTrue(store.save(message("m-eth-2", "ethereum", 9)));
        assertTrue(store.save(message("m-bsc-1", "bsc", 2)));

        assertEquals(9L, store.getMaxNonce("ethereum"));
        assertEquals(2L, store.getMaxNonce("bsc"));
        assertEquals(-1L, store.getMaxNonce("solana"), "其它链不受影响");
        assertEquals(-1L, store.getMaxNonce(null), "null 链名应安全返回 -1");
    }

    @Test
    void recordExecution_setsExecutedAndTxHash_missingMessageReturnsFalse() {
        assertTrue(store.save(message("msg-exec", "ethereum", 1)));

        assertTrue(store.recordExecution("msg-exec", "0xTargetTx"));
        Optional<String> txHash = store.getExecutionTxHash("msg-exec");
        assertTrue(txHash.isPresent());
        assertEquals("0xTargetTx", txHash.get());
        assertEquals(MessageStatus.EXECUTED, store.findById("msg-exec").get().getStatus());

        assertFalse(store.recordExecution("missing", "0xWhatever"),
                "消息不存在时 recordExecution 必须返回 false");
        assertTrue(store.getExecutionTxHash("missing").isEmpty());
    }

    @Test
    void newStoreInstanceOverSameRepository_seesData_restartSemantics() {
        assertTrue(store.save(message("msg-restart", "ethereum", 7)));
        assertTrue(store.recordExecution("msg-restart", "0xExecTx"));

        // 模拟进程重启：同一数据库上新建 store 实例
        JdbcMessageStore restarted = new JdbcMessageStore(repository);

        assertTrue(restarted.existsById("msg-restart"), "重启后去重状态必须还在");
        assertEquals(7L, restarted.getMaxNonce("ethereum"), "重启后 nonce 水位必须还在");
        assertEquals("0xExecTx", restarted.getExecutionTxHash("msg-restart").get(),
                "重启后执行追溯必须还在");
        // 重复保存仍必须被拒绝（重启不丢去重能力）
        assertFalse(restarted.save(message("msg-restart", "ethereum", 8)));
    }

    @Test
    void clear_removesEverything() {
        assertTrue(store.save(message("msg-clear", "ethereum", 1)));
        assertTrue(store.recordExecution("msg-clear", "0xTx"));
        assertTrue(store.existsById("msg-clear"));

        store.clear();

        assertFalse(store.existsById("msg-clear"));
        assertEquals(-1L, store.getMaxNonce("ethereum"));
        assertTrue(store.getExecutionTxHash("msg-clear").isEmpty());
    }
}
