package org.nexus.integration;

import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.nexus.core.validate.BasicRule;
import org.nexus.core.validate.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证管线集成测试。
 *
 * <p>验证 BasicRule 对 NexusChain 支付扩展交易类型的新类型验证逻辑，
 * 覆盖 CHANNEL_OPEN、BATCH_TRANSFER、MINT_STABLECOIN、BRIDGE_LOCK、
 * SUBSCRIPTION_AUTH 的合法与非法用例。</p>
 *
 * <p>不依赖 Spring 容器，BasicRule 手动构造，为纯集成测试。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class ValidationPipelineIntegrationTest {

    /** 测试用 32 字节公钥（from 字段）。 */
    private static final byte[] FROM_PUBKEY = new byte[Transaction.PUBLIC_KEY_SIZE];
    /** 测试用 20 字节公钥哈希（to 字段）。 */
    private static final byte[] TO_PUBKEY_HASH = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
    /** 测试用 64 字节签名。 */
    private static final byte[] SIGNATURE = new byte[Transaction.SIGNATURE_SIZE];

    /** CHANNEL_OPEN payload。 */
    private static final byte[] CHANNEL_OPEN_PAYLOAD =
            ("{\"channelId\":\"nexus-ch-001\",\"participant1\":\"addr1\",\"participant2\":\"addr2\","
                    + "\"balance1\":500000,\"balance2\":500000,\"lockTime\":1000}")
                    .getBytes(StandardCharsets.UTF_8);
    /** BATCH_TRANSFER payload。 */
    private static final byte[] BATCH_TRANSFER_PAYLOAD =
            ("{\"total_count\":2,\"total_amount\":3000,\"items\":["
                    + "{\"address\":\"NEX_addr_1\",\"amount\":1000},"
                    + "{\"address\":\"NEX_addr_2\",\"amount\":2000}"
                    + "]}")
                    .getBytes(StandardCharsets.UTF_8);
    /** MINT_STABLECOIN payload。 */
    private static final byte[] MINT_STABLECOIN_PAYLOAD =
            "{\"collateral\":1000000,\"mintAmount\":500000,\"owner\":\"addr1\"}"
                    .getBytes(StandardCharsets.UTF_8);
    /** BRIDGE_LOCK payload。 */
    private static final byte[] BRIDGE_LOCK_PAYLOAD =
            "{\"targetChain\":\"eth\",\"recipient\":\"0xabc123\"}"
                    .getBytes(StandardCharsets.UTF_8);
    /** SUBSCRIPTION_AUTH payload。 */
    private static final byte[] SUBSCRIPTION_AUTH_PAYLOAD =
            "{\"subscriber\":\"addr1\",\"merchant\":\"addr2\",\"amount\":1000,\"interval\":100}"
                    .getBytes(StandardCharsets.UTF_8);

    /** 验证规则。 */
    private BasicRule basicRule;

    /**
     * 测试初始化：手动构造 BasicRule。
     */
    @BeforeEach
    public void setUp() {
        basicRule = new BasicRule(new Block(), "test");
    }

    // ==================== CHANNEL_OPEN 验证 ====================

    /**
     * 测试正确的 CHANNEL_OPEN 交易验证通过。
     */
    @Test
    public void testValidChannelOpen() {
        // 构造正确的 CHANNEL_OPEN 交易：有 payload，amount > 0
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

        // 验证返回 SUCCESS
        Result result = basicRule.validateTransaction(tx);
        assertTrue(result.isSuccess(), "正确的 CHANNEL_OPEN 验证应返回 SUCCESS: "
                        + (result.getMessage() != null ? result.getMessage() : ""));
    }

    /**
     * 测试 CHANNEL_OPEN 无 payload 验证失败。
     */
    @Test
    public void testInvalidChannelOpenNoPayload() {
        // 构造无 payload 的 CHANNEL_OPEN 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.CHANNEL_OPEN.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                500000L,
                null,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证失败
        Result result = basicRule.validateTransaction(tx);
        assertFalse(result.isSuccess(), "无 payload 的 CHANNEL_OPEN 验证应失败");
    }

    /**
     * 测试 CHANNEL_OPEN amount=0 验证失败。
     */
    @Test
    public void testInvalidChannelOpenZeroAmount() {
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

        // 验证失败
        Result result = basicRule.validateTransaction(tx);
        assertFalse(result.isSuccess(), "amount=0 的 CHANNEL_OPEN 验证应失败");
    }

    // ==================== BATCH_TRANSFER 验证 ====================

    /**
     * 测试正确的 BATCH_TRANSFER（有 payload, amount=0）验证通过。
     */
    @Test
    public void testValidBatchTransfer() {
        // 构造正确的 BATCH_TRANSFER 交易：有 payload，amount=0
        Transaction tx = new Transaction(
                1,
                Transaction.Type.BATCH_TRANSFER.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                0L,
                BATCH_TRANSFER_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证通过
        Result result = basicRule.validateTransaction(tx);
        assertTrue(result.isSuccess(), "正确的 BATCH_TRANSFER 验证应通过: "
                        + (result.getMessage() != null ? result.getMessage() : ""));
    }

    /**
     * 测试 BATCH_TRANSFER amount!=0 验证失败。
     */
    @Test
    public void testInvalidBatchTransferWithAmount() {
        // 构造 amount!=0 的 BATCH_TRANSFER 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.BATCH_TRANSFER.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                1000L,
                BATCH_TRANSFER_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证失败
        Result result = basicRule.validateTransaction(tx);
        assertFalse(result.isSuccess(), "amount!=0 的 BATCH_TRANSFER 验证应失败");
    }

    // ==================== MINT_STABLECOIN 验证 ====================

    /**
     * 测试正确的 MINT_STABLECOIN 交易验证通过。
     */
    @Test
    public void testValidStableCoinMint() {
        // 构造正确的 MINT_STABLECOIN 交易：有 payload，amount > 0
        Transaction tx = new Transaction(
                1,
                Transaction.Type.MINT_STABLECOIN.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                500000L,
                MINT_STABLECOIN_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证通过
        Result result = basicRule.validateTransaction(tx);
        assertTrue(result.isSuccess(), "正确的 MINT_STABLECOIN 验证应通过: "
                        + (result.getMessage() != null ? result.getMessage() : ""));
    }

    /**
     * 测试 MINT_STABLECOIN 无 payload 验证失败。
     */
    @Test
    public void testInvalidStableCoinMintNoPayload() {
        // 构造无 payload 的 MINT_STABLECOIN 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.MINT_STABLECOIN.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                500000L,
                null,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证失败
        Result result = basicRule.validateTransaction(tx);
        assertFalse(result.isSuccess(), "无 payload 的 MINT_STABLECOIN 验证应失败");
    }

    // ==================== BRIDGE_LOCK 验证 ====================

    /**
     * 测试正确的 BRIDGE_LOCK 交易验证通过。
     */
    @Test
    public void testValidBridgeLock() {
        // 构造正确的 BRIDGE_LOCK 交易：有 payload，amount > 0
        Transaction tx = new Transaction(
                1,
                Transaction.Type.BRIDGE_LOCK.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                1000000L,
                BRIDGE_LOCK_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证通过
        Result result = basicRule.validateTransaction(tx);
        assertTrue(result.isSuccess(), "正确的 BRIDGE_LOCK 验证应通过: "
                        + (result.getMessage() != null ? result.getMessage() : ""));
    }

    /**
     * 测试 BRIDGE_LOCK amount=0 验证失败。
     */
    @Test
    public void testInvalidBridgeLockZeroAmount() {
        // 构造 amount=0 的 BRIDGE_LOCK 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.BRIDGE_LOCK.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                0L,
                BRIDGE_LOCK_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证失败
        Result result = basicRule.validateTransaction(tx);
        assertFalse(result.isSuccess(), "amount=0 的 BRIDGE_LOCK 验证应失败");
    }

    // ==================== SUBSCRIPTION_AUTH 验证 ====================

    /**
     * 测试正确的 SUBSCRIPTION_AUTH 交易验证通过。
     */
    @Test
    public void testValidSubscriptionAuth() {
        // 构造正确的 SUBSCRIPTION_AUTH 交易：有 payload，amount >= 0
        Transaction tx = new Transaction(
                1,
                Transaction.Type.SUBSCRIPTION_AUTH.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                0L,
                SUBSCRIPTION_AUTH_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证通过
        Result result = basicRule.validateTransaction(tx);
        assertTrue(result.isSuccess(), "正确的 SUBSCRIPTION_AUTH 验证应通过: "
                        + (result.getMessage() != null ? result.getMessage() : ""));
    }

    /**
     * 测试 SUBSCRIPTION_AUTH 无 payload 验证失败。
     */
    @Test
    public void testInvalidSubscriptionAuthNoPayload() {
        // 构造无 payload 的 SUBSCRIPTION_AUTH 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.SUBSCRIPTION_AUTH.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                0L,
                null,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证失败
        Result result = basicRule.validateTransaction(tx);
        assertFalse(result.isSuccess(), "无 payload 的 SUBSCRIPTION_AUTH 验证应失败");
    }
}
