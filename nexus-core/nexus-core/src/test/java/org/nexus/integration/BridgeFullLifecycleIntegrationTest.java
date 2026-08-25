package org.nexus.integration;

import org.apache.commons.codec.binary.Hex;
import org.nexus.core.InMemoryPaymentStateStore;
import org.nexus.core.PaymentTransactionProcessor;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BridgeLifecycleReplayGuard;
import org.nexus.core.payment.BridgePayloadCodec;
import org.nexus.core.payment.BridgeTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨链桥全生命周期集成测试（v2.3.0 生命周期键统一验证）。
 *
 * <p>验证 BRIDGE_LOCK → BRIDGE_MINT → BRIDGE_BURN 三阶段共享同一条
 * {@link BridgeTransaction} 记录：锁定创建语义键记录，铸造与销毁通过
 * payload 尾部携带的 bridgeTxId 命中同一记录并驱动状态机推进，
 * 重放交易被幂等跳过。</p>
 */
public class BridgeFullLifecycleIntegrationTest {

    private static final byte[] FROM_PUBKEY = new byte[32];
    private static final byte[] TO_PUBKEY_HASH = new byte[20];
    private static final byte[] SIGNATURE = new byte[64];

    private static final String TARGET_CHAIN = "eth";
    private static final String RECIPIENT = "0xabc123";
    private static final long AMOUNT = 1000L;

    private PaymentTransactionProcessor processor;

    @BeforeEach
    public void setUp() {
        FROM_PUBKEY[31] = 0x01; // 非零公钥，保证语义键确定性
        processor = new PaymentTransactionProcessor(new InMemoryPaymentStateStore());
    }

    // ==================== payload 构造工具 ====================

    private Transaction buildLockTx(long nonce) {
        String payloadJson = "{\"targetChain\":\"" + TARGET_CHAIN
                + "\",\"recipient\":\"" + RECIPIENT + "\"}";
        return new Transaction(1, Transaction.Type.BRIDGE_LOCK.ordinal(), nonce,
                FROM_PUBKEY, 1L, AMOUNT,
                payloadJson.getBytes(StandardCharsets.UTF_8), TO_PUBKEY_HASH, SIGNATURE);
    }

    /** 构造带 bridgeTxId 尾部的 mint payload（多签字段以零填充，处理器不验签）。 */
    private byte[] buildMintPayload(String bridgeTxId) {
        int n = 3;
        byte[] idBytes = bridgeTxId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + 1 + 32 + n * 96
                + BridgePayloadCodec.TRAILER_OVERHEAD + idBytes.length);
        buf.putLong(0L);           // timelock 已过期（0 < 当前时间）
        buf.put((byte) n);         // sigCount
        buf.put(new byte[32]);     // messageHash 占位
        buf.put(new byte[n * 96]); // 签名条目占位
        buf.putShort((short) idBytes.length);
        buf.put(idBytes);
        return buf.array();
    }

    private Transaction buildMintTx(String bridgeTxId, long nonce) {
        return new Transaction(1, Transaction.Type.BRIDGE_MINT.ordinal(), nonce,
                FROM_PUBKEY, 1L, AMOUNT, buildMintPayload(bridgeTxId),
                TO_PUBKEY_HASH, SIGNATURE);
    }

    private Transaction buildBurnTx(String bridgeTxId, long nonce) {
        byte[] payload = bridgeTxId != null
                ? BridgePayloadCodec.buildBurnPayload(1700000000L, 0, bridgeTxId)
                : new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0};
        return new Transaction(1, Transaction.Type.BRIDGE_BURN.ordinal(), nonce,
                FROM_PUBKEY, 1L, AMOUNT, payload, TO_PUBKEY_HASH, SIGNATURE);
    }

    private String expectedLockKey() {
        return BridgeLifecycleReplayGuard.computeLockKey(
                Hex.encodeHexString(FROM_PUBKEY), TARGET_CHAIN, RECIPIENT, AMOUNT);
    }

    // ==================== 全生命周期测试 ====================

    /**
     * 核心：lock → mint → burn 三阶段共享同一条记录，状态机完整推进。
     */
    @Test
    public void fullLifecycleSharesSingleRecord() {
        String lifecycleId = expectedLockKey();

        // === LOCK：创建语义键记录，状态 LOCKED ===
        processor.processTransaction(buildLockTx(1L), 100L);
        BridgeTransaction afterLock = processor.getBridgeTransaction(lifecycleId);
        assertNotNull(afterLock, "LOCK 后应存在生命周期记录");
        assertEquals(BridgeTransaction.State.LOCKED, afterLock.getState());

        // === MINT：payload 尾部携带同一 ID，命中同一条记录 → MINTED ===
        processor.processTransaction(buildMintTx(lifecycleId, 2L), 200L);
        BridgeTransaction afterMint = processor.getBridgeTransaction(lifecycleId);
        assertNotNull(afterMint, "MINT 应命中既有生命周期记录");
        assertEquals(BridgeTransaction.State.MINTED, afterMint.getState(),
                "同一记录应从 LOCKED 推进为 MINTED");

        // === BURN：payload 尾部携带同一 ID，命中同一条记录 → BURNED ===
        processor.processTransaction(buildBurnTx(lifecycleId, 3L), 300L);
        BridgeTransaction afterBurn = processor.getBridgeTransaction(lifecycleId);
        assertEquals(BridgeTransaction.State.BURNED, afterBurn.getState(),
                "同一记录应从 MINTED 推进为 BURNED");
    }

    /**
     * burn 重放：重复 burn 同一生命周期 ID 被幂等跳过，状态保持 BURNED。
     */
    @Test
    public void burnReplayIsSkipped() {
        String lifecycleId = expectedLockKey();
        processor.processTransaction(buildLockTx(1L), 100L);
        processor.processTransaction(buildMintTx(lifecycleId, 2L), 200L);
        processor.processTransaction(buildBurnTx(lifecycleId, 3L), 300L);

        // 重放 burn（不同 nonce/txHash）
        processor.processTransaction(buildBurnTx(lifecycleId, 99L), 400L);

        assertEquals(BridgeTransaction.State.BURNED,
                processor.getBridgeTransaction(lifecycleId).getState(),
                "重放 burn 不应改变状态");
    }

    /**
     * mint 重放：不同 messageHash 但相同 bridgeTxId 的重复铸造，
     * 记录保持 MINTED，不会产生第二份入账。
     */
    @Test
    public void duplicateMintOnSameLifecycleIsIdempotent() {
        String lifecycleId = expectedLockKey();
        processor.processTransaction(buildLockTx(1L), 100L);
        processor.processTransaction(buildMintTx(lifecycleId, 2L), 200L);

        processor.processTransaction(buildMintTx(lifecycleId, 50L), 250L);

        BridgeTransaction tx = processor.getBridgeTransaction(lifecycleId);
        assertEquals(BridgeTransaction.State.MINTED, tx.getState(),
                "重复铸造后记录应保持 MINTED");
    }

    /**
     * 旧格式兼容：不带尾部的 mint payload 仍按 messageHash 独立记录（回退路径）。
     */
    @Test
    public void legacyMintWithoutTrailerStillWorks() {
        ByteBuffer buf = ByteBuffer.allocate(8 + 1 + 32 + 3 * 96);
        buf.putLong(0L);
        buf.put((byte) 3);
        buf.put(new byte[32]);
        buf.put(new byte[3 * 96]);
        Transaction legacyMint = new Transaction(1,
                Transaction.Type.BRIDGE_MINT.ordinal(), 7L,
                FROM_PUBKEY, 1L, AMOUNT, buf.array(), TO_PUBKEY_HASH, SIGNATURE);

        processor.processTransaction(legacyMint, 150L);

        // 旧格式：以 messageHash（payload 字节 9-40 的 hex）为 ID 创建独立记录
        String legacyId = Hex.encodeHexString(java.util.Arrays.copyOfRange(
                legacyMint.payload, 9, 41)).toLowerCase();
        BridgeTransaction legacyRecord = processor.getBridgeTransaction(legacyId);
        assertNotNull(legacyRecord, "旧格式 mint 应按 messageHash 建立记录");
        assertEquals(BridgeTransaction.State.MINTED, legacyRecord.getState());
    }
}
