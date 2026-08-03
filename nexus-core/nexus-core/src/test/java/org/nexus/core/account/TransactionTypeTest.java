package org.nexus.core.account;

import org.nexus.core.account.Transaction;
import org.nexus.core.account.Transaction.Type;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Transaction.Type 枚举完整性测试。
 *
 * <p>验证 NexusChain 交易类型枚举的结构完整性，包括：
 * <ul>
 *   <li>枚举值总数为 27（0-26）</li>
 *   <li>TYPES_TABLE、GAS_TABLE 长度与 TYPE_MAX 一致</li>
 *   <li>11 种支付扩展类型的 ordinal 正确</li>
 *   <li>getTypeFromInput() 能正确解析新类型名称和数字</li>
 *   <li>isPaymentExtensionType()、isChannelTransaction()、isStableCoinTransaction()、
 *       isBridgeTransaction() 分类正确</li>
 *   <li>getTypeName() 返回正确的字符串</li>
 * </ul></p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class TransactionTypeTest {

    // ==================== 枚举数量验证 ====================

    /**
     * 验证 Type 枚举有 27 个值
     */
    @Test
    public void testTypeEnumHas27Values() {
        // 枚举应包含 27 个值（0-26）
        assertEquals(27, Type.values().length);
    }

    /**
     * 验证 TYPES_TABLE 长度 == 27
     */
    @Test
    public void testTypesTableLength() {
        assertEquals(27, Transaction.TYPES_TABLE.length);
    }

    /**
     * 验证 GAS_TABLE 长度 == 27
     */
    @Test
    public void testGasTableLength() {
        assertEquals(27, Transaction.GAS_TABLE.length);
    }

    /**
     * 验证 TYPE_MAX == 27
     */
    @Test
    public void testTypeMax() {
        assertEquals(27, Transaction.TYPE_MAX);
    }

    /**
     * 验证 TYPES_TABLE 与 Type 枚举完全一致
     */
    @Test
    public void testTypesTableMatchesEnum() {
        Type[] enumValues = Type.values();
        assertEquals(enumValues.length, Transaction.TYPES_TABLE.length);
        for (int i = 0; i < Transaction.TYPES_TABLE.length; i++) {
            assertEquals(enumValues[i], Transaction.TYPES_TABLE[i]);
        }
    }

    // ==================== 新类型 ordinal 验证 ====================

    /**
     * 验证每个新类型的 ordinal 值正确
     * CHANNEL_OPEN=16, CHANNEL_UPDATE=17, CHANNEL_CLOSE=18,
     * BATCH_TRANSFER=19, MINT_STABLECOIN=20, REDEEM_STABLECOIN=21,
     * BRIDGE_LOCK=22, BRIDGE_MINT=23, BRIDGE_BURN=24,
     * IDENTITY_REGISTER=25, SUBSCRIPTION_AUTH=26
     */
    @Test
    public void testNewTypeOrdinals() {
        // 支付通道类型
        assertEquals(16, Type.CHANNEL_OPEN.ordinal());
        assertEquals(17, Type.CHANNEL_UPDATE.ordinal());
        assertEquals(18, Type.CHANNEL_CLOSE.ordinal());
        // 批量转账
        assertEquals(19, Type.BATCH_TRANSFER.ordinal());
        // 稳定币类型
        assertEquals(20, Type.MINT_STABLECOIN.ordinal());
        assertEquals(21, Type.REDEEM_STABLECOIN.ordinal());
        // 跨链桥类型
        assertEquals(22, Type.BRIDGE_LOCK.ordinal());
        assertEquals(23, Type.BRIDGE_MINT.ordinal());
        assertEquals(24, Type.BRIDGE_BURN.ordinal());
        // 身份与订阅
        assertEquals(25, Type.IDENTITY_REGISTER.ordinal());
        assertEquals(26, Type.SUBSCRIPTION_AUTH.ordinal());
    }

    // ==================== getTypeFromInput 验证 ====================

    /**
     * 验证 getTypeFromInput() 能正确解析新类型名称（大写）
     */
    @Test
    public void testGetTypeFromInputByName() {
        assertEquals(Integer.valueOf(16), Transaction.getTypeFromInput("CHANNEL_OPEN"));
        assertEquals(Integer.valueOf(17), Transaction.getTypeFromInput("CHANNEL_UPDATE"));
        assertEquals(Integer.valueOf(18), Transaction.getTypeFromInput("CHANNEL_CLOSE"));
        assertEquals(Integer.valueOf(19), Transaction.getTypeFromInput("BATCH_TRANSFER"));
        assertEquals(Integer.valueOf(20), Transaction.getTypeFromInput("MINT_STABLECOIN"));
        assertEquals(Integer.valueOf(21), Transaction.getTypeFromInput("REDEEM_STABLECOIN"));
        assertEquals(Integer.valueOf(22), Transaction.getTypeFromInput("BRIDGE_LOCK"));
        assertEquals(Integer.valueOf(23), Transaction.getTypeFromInput("BRIDGE_MINT"));
        assertEquals(Integer.valueOf(24), Transaction.getTypeFromInput("BRIDGE_BURN"));
        assertEquals(Integer.valueOf(25), Transaction.getTypeFromInput("IDENTITY_REGISTER"));
        assertEquals(Integer.valueOf(26), Transaction.getTypeFromInput("SUBSCRIPTION_AUTH"));
    }

    /**
     * 验证 getTypeFromInput() 能正确解析小写名称（内部转大写）
     */
    @Test
    public void testGetTypeFromInputLowercase() {
        assertEquals(Integer.valueOf(16), Transaction.getTypeFromInput("channel_open"));
        assertEquals(Integer.valueOf(19), Transaction.getTypeFromInput("batch_transfer"));
        assertEquals(Integer.valueOf(26), Transaction.getTypeFromInput("subscription_auth"));
    }

    /**
     * 验证 getTypeFromInput() 能正确解析数字字符串
     */
    @Test
    public void testGetTypeFromInputByNumber() {
        assertEquals(Integer.valueOf(16), Transaction.getTypeFromInput("16"));
        assertEquals(Integer.valueOf(20), Transaction.getTypeFromInput("20"));
        assertEquals(Integer.valueOf(26), Transaction.getTypeFromInput("26"));
    }

    /**
     * 验证 getTypeFromInput() 对非法输入返回 null
     */
    @Test
    public void testGetTypeFromInputInvalid() {
        // 未知类型名
        assertNull(Transaction.getTypeFromInput("INVALID_TYPE"));
        assertNull(Transaction.getTypeFromInput("FOO_BAR"));
        // 越界数字
        assertNull(Transaction.getTypeFromInput("27"));
        assertNull(Transaction.getTypeFromInput("-1"));
        assertNull(Transaction.getTypeFromInput("100"));
        // 空值
        assertNull(Transaction.getTypeFromInput(""));
        assertNull(Transaction.getTypeFromInput(null));
    }

    // ==================== isPaymentExtensionType 验证 ====================

    /**
     * 验证 isPaymentExtensionType() 对新类型返回 true，对旧类型返回 false
     */
    @Test
    public void testIsPaymentExtensionType() {
        // 新类型（16-26）应返回 true
        for (int i = 16; i <= 26; i++) {
            Transaction tx = Transaction.createEmpty();
            tx.type = i;
            assertTrue("type " + i + " 应为支付扩展类型", tx.isPaymentExtensionType());
        }
        // 旧类型（0-15）应返回 false
        for (int i = 0; i <= 15; i++) {
            Transaction tx = Transaction.createEmpty();
            tx.type = i;
            assertFalse("type " + i + " 不应为支付扩展类型", tx.isPaymentExtensionType());
        }
    }

    // ==================== isChannelTransaction 验证 ====================

    /**
     * 验证 isChannelTransaction() 分类正确
     * CHANNEL_OPEN(16), CHANNEL_UPDATE(17), CHANNEL_CLOSE(18) 为通道交易
     */
    @Test
    public void testIsChannelTransaction() {
        // 通道类型应为 true
        int[] channelTypes = {16, 17, 18};
        for (int t : channelTypes) {
            Transaction tx = Transaction.createEmpty();
            tx.type = t;
            assertTrue("type " + t + " 应为通道交易", tx.isChannelTransaction());
        }
        // 非通道类型应为 false
        int[] nonChannelTypes = {0, 1, 15, 19, 20, 21, 22, 25, 26};
        for (int t : nonChannelTypes) {
            Transaction tx = Transaction.createEmpty();
            tx.type = t;
            assertFalse("type " + t + " 不应为通道交易", tx.isChannelTransaction());
        }
    }

    // ==================== isStableCoinTransaction 验证 ====================

    /**
     * 验证 isStableCoinTransaction() 分类正确
     * MINT_STABLECOIN(20), REDEEM_STABLECOIN(21) 为稳定币交易
     */
    @Test
    public void testIsStableCoinTransaction() {
        // 稳定币类型应为 true
        int[] stablecoinTypes = {20, 21};
        for (int t : stablecoinTypes) {
            Transaction tx = Transaction.createEmpty();
            tx.type = t;
            assertTrue("type " + t + " 应为稳定币交易", tx.isStableCoinTransaction());
        }
        // 非稳定币类型应为 false
        int[] nonStablecoinTypes = {0, 1, 16, 17, 18, 19, 22, 25, 26};
        for (int t : nonStablecoinTypes) {
            Transaction tx = Transaction.createEmpty();
            tx.type = t;
            assertFalse("type " + t + " 不应为稳定币交易", tx.isStableCoinTransaction());
        }
    }

    // ==================== isBridgeTransaction 验证 ====================

    /**
     * 验证 isBridgeTransaction() 分类正确
     * BRIDGE_LOCK(22), BRIDGE_MINT(23), BRIDGE_BURN(24) 为跨链桥交易
     */
    @Test
    public void testIsBridgeTransaction() {
        // 桥类型应为 true
        int[] bridgeTypes = {22, 23, 24};
        for (int t : bridgeTypes) {
            Transaction tx = Transaction.createEmpty();
            tx.type = t;
            assertTrue("type " + t + " 应为跨链桥交易", tx.isBridgeTransaction());
        }
        // 非桥类型应为 false
        int[] nonBridgeTypes = {0, 1, 16, 19, 20, 21, 25, 26};
        for (int t : nonBridgeTypes) {
            Transaction tx = Transaction.createEmpty();
            tx.type = t;
            assertFalse("type " + t + " 不应为跨链桥交易", tx.isBridgeTransaction());
        }
    }

    // ==================== getTypeName 验证 ====================

    /**
     * 验证 getTypeName() 对新类型返回正确的字符串
     */
    @Test
    public void testGetTypeNameForNewTypes() {
        Transaction tx = Transaction.createEmpty();

        tx.type = 16;
        assertEquals("CHANNEL_OPEN", tx.getTypeName());

        tx.type = 17;
        assertEquals("CHANNEL_UPDATE", tx.getTypeName());

        tx.type = 18;
        assertEquals("CHANNEL_CLOSE", tx.getTypeName());

        tx.type = 19;
        assertEquals("BATCH_TRANSFER", tx.getTypeName());

        tx.type = 20;
        assertEquals("MINT_STABLECOIN", tx.getTypeName());

        tx.type = 21;
        assertEquals("REDEEM_STABLECOIN", tx.getTypeName());

        tx.type = 22;
        assertEquals("BRIDGE_LOCK", tx.getTypeName());

        tx.type = 23;
        assertEquals("BRIDGE_MINT", tx.getTypeName());

        tx.type = 24;
        assertEquals("BRIDGE_BURN", tx.getTypeName());

        tx.type = 25;
        assertEquals("IDENTITY_REGISTER", tx.getTypeName());

        tx.type = 26;
        assertEquals("SUBSCRIPTION_AUTH", tx.getTypeName());
    }

    /**
     * 验证 getTypeName() 对旧类型也返回正确字符串
     */
    @Test
    public void testGetTypeNameForOldTypes() {
        Transaction tx = Transaction.createEmpty();

        tx.type = 0;
        assertEquals("COINBASE", tx.getTypeName());

        tx.type = 1;
        assertEquals("TRANSFER", tx.getTypeName());

        tx.type = 2;
        assertEquals("VOTE", tx.getTypeName());

        tx.type = 15;
        assertEquals("EXIT_MORTGAGE", tx.getTypeName());
    }

    /**
     * 验证 getTypeName() 对越界值返回 UNKNOWN 前缀
     */
    @Test
    public void testGetTypeNameOutOfRange() {
        Transaction tx = Transaction.createEmpty();

        tx.type = 27;
        assertTrue(tx.getTypeName().startsWith("UNKNOWN"));

        tx.type = -1;
        assertTrue(tx.getTypeName().startsWith("UNKNOWN"));

        tx.type = 100;
        assertTrue(tx.getTypeName().startsWith("UNKNOWN"));
    }

    // ==================== GAS_TABLE 值验证 ====================

    /**
     * 验证 GAS_TABLE 中新类型的 gas 值正确
     */
    @Test
    public void testGasTableValuesForNewTypes() {
        // 支付通道类型 gas
        assertEquals(100000L, Transaction.GAS_TABLE[16]); // CHANNEL_OPEN
        assertEquals(20000L, Transaction.GAS_TABLE[17]);  // CHANNEL_UPDATE
        assertEquals(50000L, Transaction.GAS_TABLE[18]);   // CHANNEL_CLOSE
        // 批量转账与稳定币
        assertEquals(50000L, Transaction.GAS_TABLE[19]);  // BATCH_TRANSFER
        assertEquals(100000L, Transaction.GAS_TABLE[20]); // MINT_STABLECOIN
        assertEquals(100000L, Transaction.GAS_TABLE[21]); // REDEEM_STABLECOIN
        // 跨链桥
        assertEquals(100000L, Transaction.GAS_TABLE[22]); // BRIDGE_LOCK
        assertEquals(100000L, Transaction.GAS_TABLE[23]); // BRIDGE_MINT
        assertEquals(100000L, Transaction.GAS_TABLE[24]); // BRIDGE_BURN
        // 身份与订阅
        assertEquals(50000L, Transaction.GAS_TABLE[25]);   // IDENTITY_REGISTER
        assertEquals(30000L, Transaction.GAS_TABLE[26]);   // SUBSCRIPTION_AUTH
    }

    /**
     * 验证 GAS_TABLE 中旧类型的 gas 值正确
     */
    @Test
    public void testGasTableValuesForOldTypes() {
        assertEquals(0L, Transaction.GAS_TABLE[0]);       // COINBASE
        assertEquals(50000L, Transaction.GAS_TABLE[1]);    // TRANSFER
        assertEquals(20000L, Transaction.GAS_TABLE[2]);    // VOTE
    }

    /**
     * 验证 GAS_TABLE 所有值非负
     */
    @Test
    public void testGasTableAllNonNegative() {
        for (int i = 0; i < Transaction.GAS_TABLE.length; i++) {
            assertTrue("GAS_TABLE[" + i + "] 应为非负值",
                    Transaction.GAS_TABLE[i] >= 0);
        }
    }

    // ==================== getFee 验证 ====================

    /**
     * 验证 getFee() 对新类型计算正确（gasPrice * GAS_TABLE[type]）
     */
    @Test
    public void testGetFeeForNewTypes() {
        Transaction tx = Transaction.createEmpty();
        tx.gasPrice = 100;

        tx.type = 16; // CHANNEL_OPEN, gas=100000
        assertEquals(10000000L, tx.getFee());

        tx.type = 17; // CHANNEL_UPDATE, gas=20000
        assertEquals(2000000L, tx.getFee());

        tx.type = 26; // SUBSCRIPTION_AUTH, gas=30000
        assertEquals(3000000L, tx.getFee());
    }

    // ==================== hasPayload 验证 ====================

    /**
     * 验证 hasPayload() 对有/无 payload 的交易正确判断
     */
    @Test
    public void testHasPayload() {
        Transaction tx = Transaction.createEmpty();
        // createEmpty 不设置 payload，应为 null
        assertFalse(tx.hasPayload());

        // 设置非空 payload
        tx.payload = new byte[]{0x01, 0x02, 0x03};
        assertTrue(tx.hasPayload());

        // 设置空 payload
        tx.payload = new byte[0];
        assertFalse(tx.hasPayload());

        // 设置 null payload
        tx.payload = null;
        assertFalse(tx.hasPayload());
    }
}
