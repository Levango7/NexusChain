package org.nexus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.TransactionPool;
import org.nexus.core.account.Transaction;
import org.nexus.core.validate.BridgeRule;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PLAN-004 往返测试：BridgeService.mint 构造 → payload 二进制格式对齐 BridgeRule（v1.9.4）。
 *
 * <p>验证：</p>
 * <ul>
 *   <li>payload = [8B timelock][1B sigCount][32B messageHash][N×(32B pubkey + 64B sig)]，
 *       3 签名时总长 329 字节</li>
 *   <li>messageHash 与 {@link BridgeService#computeMintMessageHash} 域分隔哈希一致</li>
 *   <li>签名使用真实 Ed25519 密钥对，BridgeRule 逐签名验签通过（构造→校验闭环）</li>
 *   <li>tx.to 填充真实 recipient（pubkeyHash），不再占位</li>
 * </ul>
 */
class BridgeMintPayloadFormatTest {

    private BridgeService bridgeService;
    private BridgeRule bridgeRule;
    private TransactionPool txPool;
    private List<Transaction> poolTxs;

    /** 固定时间锁（过去时间戳，满足 BridgeRule "timelock 已过期"），并保证 messageHash 确定性。 */
    private static final long TIMELOCK_EXPIRY = 1_700_000_000L;

    private static final String BRIDGE_TX_ID = "br_test_001";
    private static final String SOURCE_CHAIN = "ethereum";
    private static final long AMOUNT = 1000L;

    // recipient = 20 字节 pubkeyHash hex（40 字符）
    private static final String RECIPIENT_HASH =
            "aabbccddeeff00112233445566778899aabbccdd";

    // 3 个真实 Ed25519 密钥对及其对规范化 messageHash 的签名（匹配 min-validators=3）
    private List<String> pubkeysHex;
    private List<String> signaturesHex;
    private byte[] messageHash;

    @BeforeEach
    void setUp() throws Exception {
        bridgeService = new BridgeService();
        txPool = mock(TransactionPool.class);
        poolTxs = new ArrayList<>();
        doAnswer(inv -> { poolTxs.add(inv.getArgument(0)); return null; })
                .when(txPool).add(any(Transaction.class));

        setField(bridgeService, "txPool", txPool);
        setField(bridgeService, "minValidators", 3);

        messageHash = BridgeService.computeMintMessageHash(
                BRIDGE_TX_ID, SOURCE_CHAIN, RECIPIENT_HASH, AMOUNT, TIMELOCK_EXPIRY);
        pubkeysHex = new ArrayList<>();
        signaturesHex = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Ed25519KeyPair kp = Ed25519.generateKeyPair();
            pubkeysHex.add(java.util.HexFormat.of().formatHex(kp.getPublicKey().getEncoded()));
            signaturesHex.add(java.util.HexFormat.of()
                    .formatHex(kp.getPrivateKey().sign(messageHash)));
        }

        // BridgeRule：minValidators=3；v2.1.0 fail-closed 要求归属校验允许集合非空，
        // 将测试生成的验证人公钥注入配置白名单
        bridgeRule = new BridgeRule();
        setField(bridgeRule, "minValidators", 3);
        setField(bridgeRule, "validatorPubkeysConfig", String.join(",", pubkeysHex));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void mintConstructsBinaryPayloadMatchingBridgeRule() {
        var result = bridgeService.mint(BRIDGE_TX_ID, SOURCE_CHAIN, RECIPIENT_HASH,
                AMOUNT, TIMELOCK_EXPIRY, pubkeysHex, signaturesHex);
        assertEquals(2000, result.getCode(), "mint 应成功（APIResult 约定成功码 2000）: " + result.getMessage());

        assertEquals(1, poolTxs.size(), "交易应进入 txPool");
        Transaction tx = poolTxs.get(0);
        assertEquals(Transaction.Type.BRIDGE_MINT.ordinal(), tx.type);

        // 1. payload 二进制格式：[8B timelock][1B sigCount][32B messageHash][3×(32B pubkey+64B sig)]
        //    v2.3.0：追加 [2B idLen][bridgeTxId] 尾部，显式携带生命周期统一 ID
        byte[] payload = tx.payload;
        byte[] idBytes = BRIDGE_TX_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int baseLength = 8 + 1 + 32 + 3 * (32 + 64);
        int expectedLength = baseLength
                + org.nexus.core.payment.BridgePayloadCodec.TRAILER_OVERHEAD + idBytes.length;
        assertEquals(expectedLength, payload.length,
                "payload 长度应为 " + expectedLength + " 字节（v1.9.4 基础 " + baseLength
                        + " + 尾部 " + (expectedLength - baseLength) + "）");
        // 前 8 字节 = 到期时间戳（显式传入的固定值）
        assertEquals(TIMELOCK_EXPIRY, ByteBuffer.wrap(payload, 0, 8).getLong(), "前 8 字节应为时间锁");
        // 第 9 字节 = 签名数 3
        assertEquals(3, payload[8] & 0xFF, "第 9 字节应为签名数");
        // 字节 9-40 = 规范化 messageHash
        assertArrayEquals(messageHash, java.util.Arrays.copyOfRange(payload, 9, 41),
                "messageHash 应等于域分隔哈希 computeMintMessageHash(...)");
        // 后续每个 (pubkey, sig) 对应与传入一致
        for (int i = 0; i < 3; i++) {
            byte[] expectedPk = java.util.HexFormat.of().parseHex(pubkeysHex.get(i));
            byte[] expectedSig = java.util.HexFormat.of().parseHex(signaturesHex.get(i));
            int entryOffset = 41 + i * 96;
            assertArrayEquals(expectedPk,
                    java.util.Arrays.copyOfRange(payload, entryOffset, entryOffset + 32),
                    "公钥 " + i + " 应与传入一致");
            assertArrayEquals(expectedSig,
                    java.util.Arrays.copyOfRange(payload, entryOffset + 32, entryOffset + 96),
                    "签名 " + i + " 应与传入一致");
        }

        // 尾部回读：extractIdTrailer 应还原出与传入一致的 bridgeTxId
        String extractedId = org.nexus.core.payment.BridgePayloadCodec.extractIdTrailer(
                payload, baseLength);
        assertEquals(BRIDGE_TX_ID, extractedId, "尾部应能还原生命周期统一 bridgeTxId");

        // 2. tx.to 填充真实 recipient pubkeyHash
        assertFalse(java.util.Arrays.equals(tx.to, new byte[Transaction.PUBLIC_KEY_HASH_SIZE]),
                "tx.to 不应为占位零字节");
        assertArrayEquals(java.util.HexFormat.of().parseHex(RECIPIENT_HASH), tx.to,
                "tx.to 应等于 recipient pubkeyHash");
    }

    @Test
    void bridgeRuleValidatesMintedPayload() {
        bridgeService.mint(BRIDGE_TX_ID, SOURCE_CHAIN, RECIPIENT_HASH,
                AMOUNT, TIMELOCK_EXPIRY, pubkeysHex, signaturesHex);
        Transaction tx = poolTxs.get(0);

        // BridgeRule.validateTransaction 应通过（构造→校验闭环：逐签名 Ed25519 验签）
        var result = bridgeRule.validateTransaction(tx);
        assertTrue(result.isSuccess(), "BridgeRule 应接受 mint 构造的交易: " + result.getMessage());
    }

    @Test
    void tamperedSignatureRejected() {
        // 篡改最后一个签名的最后一个 hex 字符 → 预验签失败，拒绝入池
        String lastSig = signaturesHex.get(2);
        char tamperedChar = lastSig.charAt(lastSig.length() - 1) == '0' ? '1' : '0';
        String tampered = lastSig.substring(0, lastSig.length() - 1) + tamperedChar;
        List<String> badSigs = List.of(signaturesHex.get(0), signaturesHex.get(1), tampered);

        var result = bridgeService.mint(BRIDGE_TX_ID, SOURCE_CHAIN, RECIPIENT_HASH,
                AMOUNT, TIMELOCK_EXPIRY, pubkeysHex, badSigs);
        assertNotEquals(2000, result.getCode(), "篡改后的签名应被预验签拒绝");
        assertEquals(0, poolTxs.size(), "拒绝时不应入池");
    }

    @Test
    void insufficientSignaturesRejected() {
        var result = bridgeService.mint(BRIDGE_TX_ID, SOURCE_CHAIN, RECIPIENT_HASH,
                AMOUNT, TIMELOCK_EXPIRY,
                pubkeysHex.subList(0, 2), signaturesHex.subList(0, 2));  // 2 < minValidators=3
        assertNotEquals(2000, result.getCode(), "签名不足应被拒绝");
        assertEquals(0, poolTxs.size(), "拒绝时不应入池");
    }

    @Test
    void invalidRecipientRejected() {
        var result = bridgeService.mint(BRIDGE_TX_ID, SOURCE_CHAIN, "zz-not-hex",
                AMOUNT, TIMELOCK_EXPIRY, pubkeysHex, signaturesHex);
        assertNotEquals(2000, result.getCode(), "非法 recipient hex 应被拒绝");
    }

    @Test
    void emptyAllowlistRejected() throws Exception {
        // v2.1.0 fail-closed：归属校验允许集合为空（未配置白名单且注册表无验证人）时，
        // 即使多签验签全部有效也必须拒绝
        BridgeRule emptyAllowlistRule = new BridgeRule();
        setField(emptyAllowlistRule, "minValidators", 3);

        Transaction tx = mintedTx();
        var result = emptyAllowlistRule.validateTransaction(tx);
        assertFalse(result.isSuccess(), "空白名单应拒绝 BRIDGE_MINT");
        assertTrue(result.getMessage().contains("allowlist is empty"),
                "错误信息应指明白名单为空: " + result.getMessage());
    }

    @Test
    void replayedMessageHashRejected() throws Exception {
        // v2.1.0 重放防护：同一规范化 messageHash 只允许铸造一次；
        // 已消费后，即使换 nonce 生成新交易（payload 相同），验证层也应拒绝
        org.nexus.core.payment.BridgeMintReplayGuard guard =
                new org.nexus.core.payment.BridgeMintReplayGuard();
        setField(bridgeRule, "replayGuard", guard);
        guard.markConsumed(java.util.HexFormat.of().formatHex(messageHash));

        Transaction tx = mintedTx();
        var result = bridgeRule.validateTransaction(tx);
        assertFalse(result.isSuccess(), "已消费的 messageHash 应被拒绝（重放防护）");
        assertTrue(result.getMessage().contains("replay detected"),
                "错误信息应指明重放: " + result.getMessage());
    }

    /** 通过 service 构造一笔合法的 BRIDGE_MINT 交易并从池中取出。 */
    private Transaction mintedTx() {
        var r = bridgeService.mint(BRIDGE_TX_ID, SOURCE_CHAIN, RECIPIENT_HASH,
                AMOUNT, TIMELOCK_EXPIRY, pubkeysHex, signaturesHex);
        assertEquals(2000, r.getCode(), "构造交易应成功: " + r.getMessage());
        assertEquals(1, poolTxs.size());
        return poolTxs.get(0);
    }
}
