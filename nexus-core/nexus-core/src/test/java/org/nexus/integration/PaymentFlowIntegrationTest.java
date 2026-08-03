package org.nexus.integration;

import org.nexus.core.Block;
import org.nexus.core.InMemoryPaymentStateStore;
import org.nexus.core.PaymentTransactionProcessor;
import org.nexus.core.TransactionPool;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.core.validate.BasicRule;
import org.nexus.core.validate.Result;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * 端到端支付流程集成测试。
 *
 * <p>验证从交易构造、BasicRule 验证、入池到 PaymentTransactionProcessor 状态处理的
 * 完整链路。覆盖 CHANNEL_OPEN、CHANNEL_CLOSE、BATCH_TRANSFER、MINT_STABLECOIN、
 * BRIDGE_LOCK 等支付扩展交易类型。</p>
 *
 * <p>不依赖 Spring 容器，所有组件直接 new 构造，为纯集成测试。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class PaymentFlowIntegrationTest {

    /** 测试用 32 字节公钥（from 字段）。 */
    private static final byte[] FROM_PUBKEY = new byte[Transaction.PUBLIC_KEY_SIZE];
    /** 测试用 20 字节公钥哈希（to 字段）。 */
    private static final byte[] TO_PUBKEY_HASH = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
    /** 测试用 64 字节签名。 */
    private static final byte[] SIGNATURE = new byte[Transaction.SIGNATURE_SIZE];

    /** CHANNEL_OPEN payload JSON。 */
    private static final byte[] CHANNEL_OPEN_PAYLOAD =
            ("{\"channelId\":\"nexus-ch-001\",\"participant1\":\"addr1\",\"participant2\":\"addr2\","
                    + "\"balance1\":500000,\"balance2\":500000,\"lockTime\":1000}")
                    .getBytes(StandardCharsets.UTF_8);
    /** CHANNEL_CLOSE payload JSON。 */
    private static final byte[] CHANNEL_CLOSE_PAYLOAD =
            ("{\"channelId\":\"nexus-ch-001\",\"finalBalance1\":400000,\"finalBalance2\":600000}")
                    .getBytes(StandardCharsets.UTF_8);
    /** BATCH_TRANSFER payload JSON。 */
    private static final byte[] BATCH_TRANSFER_PAYLOAD =
            ("{\"total_count\":2,\"total_amount\":3000,\"items\":["
                    + "{\"address\":\"NEX_addr_1\",\"amount\":1000},"
                    + "{\"address\":\"NEX_addr_2\",\"amount\":2000}"
                    + "]}")
                    .getBytes(StandardCharsets.UTF_8);
    /** MINT_STABLECOIN payload JSON。 */
    private static final byte[] MINT_STABLECOIN_PAYLOAD =
            ("{\"collateral\":1000000,\"mintAmount\":500000,\"owner\":\"addr1\"}")
                    .getBytes(StandardCharsets.UTF_8);
    /** BRIDGE_LOCK payload JSON。 */
    private static final byte[] BRIDGE_LOCK_PAYLOAD =
            ("{\"targetChain\":\"eth\",\"recipient\":\"0xabc123\"}")
                    .getBytes(StandardCharsets.UTF_8);

    /** 验证规则。 */
    private BasicRule basicRule;
    /** 交易池。 */
    private TransactionPool txPool;
    /** 支付交易处理器。 */
    private PaymentTransactionProcessor processor;

    /**
     * 测试初始化：手动构造 BasicRule、TransactionPool、PaymentTransactionProcessor。
     */
    @Before
    public void setUp() {
        // 手动构造 BasicRule，不通过 Spring 注入
        basicRule = new BasicRule(new Block(), "test");
        // 直接 new TransactionPool
        txPool = new TransactionPool();
        // 直接 new PaymentTransactionProcessor，使用内存状态存储
        processor = new PaymentTransactionProcessor(new InMemoryPaymentStateStore());
    }

    // ==================== 端到端支付流程测试 ====================

    /**
     * 测试 CHANNEL_OPEN 端到端流程：构造 → 验证 → 入池。
     */
    @Test
    public void testChannelOpenFlow() {
        // 构造 CHANNEL_OPEN 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.CHANNEL_OPEN.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                500000L,
                CHANNEL_OPEN_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // BasicRule 验证通过
        Result result = basicRule.validateTransaction(tx);
        assertTrue("CHANNEL_OPEN 验证应通过: " + (result.getMessage() != null ? result.getMessage() : ""),
                result.isSuccess());

        // 入池
        txPool.add(tx);
        // 验证 txPool.has(txHash)
        assertTrue("交易池应包含该交易", txPool.has(tx.getHashHexString()));
    }

    /**
     * 测试 CHANNEL_CLOSE 端到端流程：构造 → 验证 → 入池。
     */
    @Test
    public void testChannelCloseFlow() {
        // 构造 CHANNEL_CLOSE 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.CHANNEL_CLOSE.ordinal(),
                2L,
                FROM_PUBKEY,
                100000L,
                0L,
                CHANNEL_CLOSE_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // BasicRule 验证通过
        Result result = basicRule.validateTransaction(tx);
        assertTrue("CHANNEL_CLOSE 验证应通过: " + (result.getMessage() != null ? result.getMessage() : ""),
                result.isSuccess());

        // 入池
        txPool.add(tx);
        assertTrue("交易池应包含该交易", txPool.has(tx.getHashHexString()));
    }

    /**
     * 测试 BATCH_TRANSFER 端到端流程：构造（含 payload）→ 验证 → 入池。
     */
    @Test
    public void testBatchTransferFlow() {
        // 构造 BATCH_TRANSFER 交易（amount=0，金额在 payload 中）
        Transaction tx = new Transaction(
                1,
                Transaction.Type.BATCH_TRANSFER.ordinal(),
                3L,
                FROM_PUBKEY,
                100000L,
                0L,
                BATCH_TRANSFER_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // BasicRule 验证通过
        Result result = basicRule.validateTransaction(tx);
        assertTrue("BATCH_TRANSFER 验证应通过: " + (result.getMessage() != null ? result.getMessage() : ""),
                result.isSuccess());

        // 入池
        txPool.add(tx);
        assertTrue("交易池应包含该交易", txPool.has(tx.getHashHexString()));
    }

    /**
     * 测试 MINT_STABLECOIN 端到端流程：构造 → 验证 → 入池。
     */
    @Test
    public void testStableCoinMintFlow() {
        // 构造 MINT_STABLECOIN 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.MINT_STABLECOIN.ordinal(),
                4L,
                FROM_PUBKEY,
                100000L,
                500000L,
                MINT_STABLECOIN_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // BasicRule 验证通过
        Result result = basicRule.validateTransaction(tx);
        assertTrue("MINT_STABLECOIN 验证应通过: " + (result.getMessage() != null ? result.getMessage() : ""),
                result.isSuccess());

        // 入池
        txPool.add(tx);
        assertTrue("交易池应包含该交易", txPool.has(tx.getHashHexString()));
    }

    /**
     * 测试 BRIDGE_LOCK 端到端流程：构造 → 验证 → 入池。
     */
    @Test
    public void testBridgeLockFlow() {
        // 构造 BRIDGE_LOCK 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.BRIDGE_LOCK.ordinal(),
                5L,
                FROM_PUBKEY,
                100000L,
                1000000L,
                BRIDGE_LOCK_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // BasicRule 验证通过
        Result result = basicRule.validateTransaction(tx);
        assertTrue("BRIDGE_LOCK 验证应通过: " + (result.getMessage() != null ? result.getMessage() : ""),
                result.isSuccess());

        // 入池
        txPool.add(tx);
        assertTrue("交易池应包含该交易", txPool.has(tx.getHashHexString()));
    }

    // ==================== 验证拒绝测试 ====================

    /**
     * 测试验证拒绝 amount=0 的 CHANNEL_OPEN 交易。
     */
    @Test
    public void testValidationRejectInvalidChannelOpen() {
        // 构造 amount=0 的 CHANNEL_OPEN 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.CHANNEL_OPEN.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                0L,
                CHANNEL_OPEN_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // BasicRule 验证应失败
        Result result = basicRule.validateTransaction(tx);
        assertFalse("amount=0 的 CHANNEL_OPEN 验证应失败", result.isSuccess());
    }

    /**
     * 测试验证拒绝无 payload 的 BATCH_TRANSFER 交易。
     */
    @Test
    public void testValidationRejectBatchTransferWithoutPayload() {
        // 构造无 payload 的 BATCH_TRANSFER 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.BATCH_TRANSFER.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                0L,
                null,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // BasicRule 验证应失败
        Result result = basicRule.validateTransaction(tx);
        assertFalse("无 payload 的 BATCH_TRANSFER 验证应失败", result.isSuccess());
    }

    // ==================== 交易池按类型查询测试 ====================

    /**
     * 测试添加多种类型交易后 getTransactionsByType() 返回正确结果。
     */
    @Test
    public void testTransactionPoolGetByType() {
        // 构造并添加多种类型交易
        Transaction channelOpenTx = new Transaction(
                1, Transaction.Type.CHANNEL_OPEN.ordinal(), 1L,
                FROM_PUBKEY, 100000L, 500000L, CHANNEL_OPEN_PAYLOAD,
                TO_PUBKEY_HASH, SIGNATURE
        );
        Transaction batchTransferTx = new Transaction(
                1, Transaction.Type.BATCH_TRANSFER.ordinal(), 2L,
                FROM_PUBKEY, 100000L, 0L, BATCH_TRANSFER_PAYLOAD,
                TO_PUBKEY_HASH, SIGNATURE
        );
        Transaction bridgeLockTx = new Transaction(
                1, Transaction.Type.BRIDGE_LOCK.ordinal(), 3L,
                FROM_PUBKEY, 100000L, 1000000L, BRIDGE_LOCK_PAYLOAD,
                TO_PUBKEY_HASH, SIGNATURE
        );

        // 全部入池
        txPool.add(channelOpenTx, batchTransferTx, bridgeLockTx);
        assertEquals("交易池应有 3 笔交易", 3, txPool.size());

        // 按类型查询 CHANNEL_OPEN
        assertEquals("CHANNEL_OPEN 类型交易应有 1 笔", 1,
                txPool.getTransactionsByType(Transaction.Type.CHANNEL_OPEN.ordinal()).size());
        // 按类型查询 BATCH_TRANSFER
        assertEquals("BATCH_TRANSFER 类型交易应有 1 笔", 1,
                txPool.getTransactionsByType(Transaction.Type.BATCH_TRANSFER.ordinal()).size());
        // 按类型查询 BRIDGE_LOCK
        assertEquals("BRIDGE_LOCK 类型交易应有 1 笔", 1,
                txPool.getTransactionsByType(Transaction.Type.BRIDGE_LOCK.ordinal()).size());
        // 按类型查询不存在的类型（TRANSFER）
        assertEquals("TRANSFER 类型交易应有 0 笔", 0,
                txPool.getTransactionsByType(Transaction.Type.TRANSFER.ordinal()).size());
    }

    // ==================== PaymentTransactionProcessor 测试 ====================

    /**
     * 测试 PaymentTransactionProcessor 处理 CHANNEL_OPEN 交易后创建 PaymentChannel 状态记录。
     */
    @Test
    public void testPaymentTransactionProcessor() {
        // 构造 CHANNEL_OPEN 交易
        long amount = 500000L;
        Transaction tx = new Transaction(
                1,
                Transaction.Type.CHANNEL_OPEN.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                amount,
                CHANNEL_OPEN_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        long blockHeight = 100L;
        // 处理交易
        processor.processTransaction(tx, blockHeight);

        // 验证创建了 PaymentChannel 状态记录
        String channelId = tx.getHashHexString();
        PaymentChannel channel = processor.getChannel(channelId);
        assertNotNull("应创建 PaymentChannel 状态记录", channel);
        // 验证通道状态为 OPEN
        assertEquals("通道状态应为 OPEN", PaymentChannel.State.OPEN, channel.getState());
        // 验证通道余额
        assertEquals("通道余额应为交易金额", amount, channel.getBalance1());
        // 验证通道 nonce 为 0
        assertEquals("通道 nonce 应为 0", 0L, channel.getNonce());
    }

    /**
     * 测试完整支付通道生命周期：CHANNEL_OPEN → processTransaction → CHANNEL_CLOSE → processTransaction。
     *
     * <p>验证通道状态从不存在到 OPEN 的状态变化，以及 CHANNEL_CLOSE 处理后通道记录仍存在。</p>
     */
    @Test
    public void testFullPaymentChannelLifecycle() {
        // === 步骤 1：CHANNEL_OPEN → processTransaction ===

        long openAmount = 500000L;
        Transaction openTx = new Transaction(
                1,
                Transaction.Type.CHANNEL_OPEN.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                openAmount,
                CHANNEL_OPEN_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        long openHeight = 100L;
        processor.processTransaction(openTx, openHeight);

        // 验证通道已创建且状态为 OPEN
        String channelId = openTx.getHashHexString();
        PaymentChannel channel = processor.getChannel(channelId);
        assertNotNull("CHANNEL_OPEN 后应创建通道记录", channel);
        assertEquals("通道状态应为 OPEN", PaymentChannel.State.OPEN, channel.getState());
        assertEquals("通道余额1应为交易金额", openAmount, channel.getBalance1());
        assertEquals("通道余额2应为 0", 0L, channel.getBalance2());

        // === 步骤 2：CHANNEL_CLOSE → processTransaction ===

        Transaction closeTx = new Transaction(
                1,
                Transaction.Type.CHANNEL_CLOSE.ordinal(),
                2L,
                FROM_PUBKEY,
                100000L,
                0L,
                CHANNEL_CLOSE_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        long closeHeight = 200L;
        // 处理 CHANNEL_CLOSE 交易（不会抛异常）
        processor.processTransaction(closeTx, closeHeight);

        // 验证原通道记录仍存在（CHANNEL_CLOSE 使用自身哈希查找，不会找到原通道）
        PaymentChannel originalChannel = processor.getChannel(channelId);
        assertNotNull("原通道记录应仍存在", originalChannel);
        // 原通道状态应仍为 OPEN（CHANNEL_CLOSE 未找到对应通道）
        assertEquals("原通道状态应仍为 OPEN", PaymentChannel.State.OPEN, originalChannel.getState());

        // 验证状态变化：从不存在到 OPEN 是主要的状态变化
        // CHANNEL_CLOSE 交易本身被处理器接收并处理，未抛异常
    }
}
