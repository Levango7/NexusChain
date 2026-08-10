package org.nexus.bridge.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.bridge.BridgeState;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BridgeTransaction} 单元测试：覆盖字段读写、终态判断、equals/hashCode/toString。
 */
class BridgeTransactionTest {

    @Test
    @DisplayName("默认构造产生空对象")
    void defaultConstructor_emptyObject() {
        BridgeTransaction tx = new BridgeTransaction();
        assertNull(tx.getTxId());
        assertNull(tx.getStatus());
        assertNull(tx.getOperationType());
        assertEquals(0, tx.getAmount());
        assertNotNull(tx.getValidatorIds());
        assertTrue(tx.getValidatorIds().isEmpty());
    }

    @Test
    @DisplayName("所有 setter/getter 正确往返")
    void settersGetters_roundTrip() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("tx-001");
        tx.setOperationType(BridgeTransaction.BridgeOperationType.BRIDGE_LOCK);
        tx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);
        tx.setSourceChainId("ethereum");
        tx.setTargetChainId("bsc");
        tx.setAmount(1000L);
        tx.setUserAddress("0xUser");
        tx.setTargetAddress("0xTarget");
        tx.setSourceTxHash("0xSrcHash");
        tx.setTargetTxHash("0xTgtHash");
        tx.setRelatedTxId("related-001");
        Set<String> validators = new HashSet<>(Arrays.asList("v1", "v2"));
        tx.setValidatorIds(validators);
        Instant createdAt = Instant.now();
        Instant updatedAt = Instant.now();
        tx.setCreatedAt(createdAt);
        tx.setUpdatedAt(updatedAt);
        tx.setTimelockExpiresAt(createdAt.plusSeconds(3600));
        tx.setFailureReason("test failure");
        tx.setMemo("test memo");

        assertEquals("tx-001", tx.getTxId());
        assertEquals(BridgeTransaction.BridgeOperationType.BRIDGE_LOCK, tx.getOperationType());
        assertEquals(BridgeTransaction.BridgeTxStatus.LOCKED, tx.getStatus());
        assertEquals("ethereum", tx.getSourceChainId());
        assertEquals("bsc", tx.getTargetChainId());
        assertEquals(1000L, tx.getAmount());
        assertEquals("0xUser", tx.getUserAddress());
        assertEquals("0xTarget", tx.getTargetAddress());
        assertEquals("0xSrcHash", tx.getSourceTxHash());
        assertEquals("0xTgtHash", tx.getTargetTxHash());
        assertEquals("related-001", tx.getRelatedTxId());
        assertEquals(validators, tx.getValidatorIds());
        assertEquals(createdAt, tx.getCreatedAt());
        assertEquals(updatedAt, tx.getUpdatedAt());
        assertNotNull(tx.getTimelockExpiresAt());
        assertEquals("test failure", tx.getFailureReason());
        assertEquals("test memo", tx.getMemo());
    }

    @Test
    @DisplayName("BridgeOperationType 枚举应包含 4 种操作")
    void operationType_enumValues() {
        BridgeTransaction.BridgeOperationType[] types = BridgeTransaction.BridgeOperationType.values();
        assertEquals(4, types.length);
        assertTrue(Arrays.asList(types).containsAll(Arrays.asList(
                BridgeTransaction.BridgeOperationType.BRIDGE_LOCK,
                BridgeTransaction.BridgeOperationType.BRIDGE_MINT,
                BridgeTransaction.BridgeOperationType.BRIDGE_BURN,
                BridgeTransaction.BridgeOperationType.BRIDGE_UNLOCK)));
    }

    @Test
    @DisplayName("BridgeTxStatus 枚举应包含 11 种状态")
    void txStatus_enumValues() {
        assertEquals(11, BridgeTransaction.BridgeTxStatus.values().length);
    }

    @Test
    @DisplayName("isTerminal: MINTED/UNLOCKED/FAILED/CANCELLED/TIMEOUT 为终态")
    void isTerminal_terminalStates() {
        BridgeTransaction tx = new BridgeTransaction();
        for (BridgeTransaction.BridgeTxStatus s : Arrays.asList(
                BridgeTransaction.BridgeTxStatus.MINTED,
                BridgeTransaction.BridgeTxStatus.UNLOCKED,
                BridgeTransaction.BridgeTxStatus.FAILED,
                BridgeTransaction.BridgeTxStatus.CANCELLED,
                BridgeTransaction.BridgeTxStatus.TIMEOUT)) {
            tx.setStatus(s);
            assertTrue(tx.isTerminal(), s + " should be terminal");
        }
    }

    @Test
    @DisplayName("isTerminal: 非终态状态返回 false")
    void isTerminal_nonTerminalStates() {
        BridgeTransaction tx = new BridgeTransaction();
        for (BridgeTransaction.BridgeTxStatus s : Arrays.asList(
                BridgeTransaction.BridgeTxStatus.LOCK_PENDING,
                BridgeTransaction.BridgeTxStatus.LOCKED,
                BridgeTransaction.BridgeTxStatus.MINT_PENDING,
                BridgeTransaction.BridgeTxStatus.BURN_PENDING,
                BridgeTransaction.BridgeTxStatus.BURNED,
                BridgeTransaction.BridgeTxStatus.UNLOCK_PENDING)) {
            tx.setStatus(s);
            assertFalse(tx.isTerminal(), s + " should not be terminal");
        }
    }

    @Test
    @DisplayName("equals/hashCode 基于 txId")
    void equalsHashcode_basedOnTxId() {
        BridgeTransaction t1 = new BridgeTransaction();
        t1.setTxId("tx-1");
        BridgeTransaction t2 = new BridgeTransaction();
        t2.setTxId("tx-1");
        t2.setAmount(999L);
        BridgeTransaction t3 = new BridgeTransaction();
        t3.setTxId("tx-2");

        assertEquals(t1, t2);
        assertEquals(t1.hashCode(), t2.hashCode());
        assertNotEquals(t1, t3);
        assertEquals(t1, t1);
        assertNotEquals(t1, null);
        assertNotEquals(t1, "string");
    }

    @Test
    @DisplayName("equals: null txId 的两个对象应相等")
    void equals_nullTxId() {
        BridgeTransaction t1 = new BridgeTransaction();
        BridgeTransaction t2 = new BridgeTransaction();
        assertEquals(t1, t2);
    }

    @Test
    @DisplayName("toString 应包含关键字段")
    void toString_containsKeyFields() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("tx-001");
        tx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);
        tx.setSourceChainId("ethereum");
        tx.setTargetChainId("bsc");
        tx.setAmount(1000L);
        String str = tx.toString();
        assertTrue(str.contains("tx-001"));
        assertTrue(str.contains("LOCKED"));
        assertTrue(str.contains("ethereum"));
        assertTrue(str.contains("bsc"));
        assertTrue(str.startsWith("BridgeTransaction{"));
    }

    @Test
    @DisplayName("validatorIds 可设置为空集合")
    void validatorIds_emptySet() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setValidatorIds(Collections.emptySet());
        assertNotNull(tx.getValidatorIds());
        assertTrue(tx.getValidatorIds().isEmpty());
    }
}