package org.nexus.core.account;

import org.junit.jupiter.api.Test;
import org.nexus.core.account.Transaction.Type;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transaction 模型层单测（A 项覆盖率提升：0.25→0.30）。
 * 断言基于源码语义逐条核对（Transaction.java:46-612）：
 * 类型解析、编码往返（RPC 字节/proto）、哈希缓存、fee 表、
 * 支付扩展分类器、payload 上限 fail-closed（v1.9.4 安全修复回归）。
 */
class TransactionModelTest {

    private static Transaction sampleTx(int type, byte[] payload) {
        Transaction tx = new Transaction();
        tx.version = 1;
        tx.type = type;
        tx.nonce = 7;
        tx.from = new byte[Transaction.PUBLIC_KEY_SIZE];
        tx.from[0] = 1;
        tx.gasPrice = 2;
        tx.amount = 100;
        tx.payload = payload;
        tx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        tx.signature = new byte[Transaction.SIGNATURE_SIZE];
        return tx;
    }

    // ===== getTypeFromInput（:48） =====

    @Test
    void getTypeFromInputParsesNamesNumbersAndFailsGracefully() {
        assertEquals(Type.TRANSFER.ordinal(), Transaction.getTypeFromInput("transfer"));
        assertEquals(Type.COINBASE.ordinal(), Transaction.getTypeFromInput("COINBASE"));
        assertEquals(3, Transaction.getTypeFromInput("3"));
        assertEquals(Type.EXIT_MORTGAGE.ordinal(), Transaction.getTypeFromInput("exit_mortgage"));
        // 越界数字/非数字/null/空串 → null（:50/61/66 三条失败路径）
        assertNull(Transaction.getTypeFromInput("28"));
        assertNull(Transaction.getTypeFromInput("-1"));
        assertNull(Transaction.getTypeFromInput("not-a-type"));
        assertNull(Transaction.getTypeFromInput(null));
        assertNull(Transaction.getTypeFromInput(""));
    }

    // ===== 构造与 copy（:102/114） =====

    @Test
    void copyProducesEqualButDistinctObject() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), new byte[]{9});
        Transaction c = tx.copy();
        assertNotSame(tx, c);
        assertEquals(tx.version, c.version);
        assertEquals(tx.type, c.type);
        assertEquals(tx.nonce, c.nonce);
        assertSame(tx.from, c.from, "copy 共享引用（浅拷贝语义）");
        assertArrayEquals(tx.getHash(), c.getHash());
    }

    @Test
    void createEmptySizesAllFixedFields() {
        Transaction t = Transaction.createEmpty();
        assertEquals(Transaction.DEFAULT_TRANSACTION_VERSION, t.version);
        assertEquals(Transaction.PUBLIC_KEY_SIZE, t.from.length);
        assertEquals(Transaction.PUBLIC_KEY_HASH_SIZE, t.to.length);
        assertEquals(Transaction.SIGNATURE_SIZE, t.signature.length);
    }

    // ===== 常量表一致性 =====

    @Test
    void gasTableCoversAllTypes() {
        // 27 个枚举 × 27 项表一一对应；TYPE_MAX=27 为含边界常量（27 本身越界——
        // @Max(TYPE_MAX) 校验注解比实际合法域宽 1，记录现状）
        assertEquals(Type.values().length, Transaction.GAS_TABLE.length);
        assertEquals(Type.values().length, Transaction.TYPES_TABLE.length);
        assertEquals(27, Transaction.TYPE_MAX);
    }

    // ===== getHash 缓存 + getRawForSign/Hash 差异（:224/280-314） =====

    @Test
    void hashIsCachedAcrossCalls() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), null);
        byte[] h1 = tx.getHash();
        byte[] h2 = tx.getHash();
        assertSame(h1, h2, "第二次调用应返回缓存实例");
        // setHashCache 覆写缓存
        byte[] fake = new byte[32];
        tx.setHashCache(fake);
        assertSame(fake, tx.getHash());
        assertSame(fake, tx.getHashCache());
    }

    @Test
    void rawForSignZeroesSignatureWhileRawForHashKeepsIt() {
        byte[] sig = new byte[Transaction.SIGNATURE_SIZE];
        sig[0] = 5;
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), new byte[]{1});
        tx.signature = sig;
        byte[] withSig = tx.getRawForHash();
        byte[] forSign = tx.getRawForSign();
        // 定长布局：sig 位于 1+1+8+32+8+8=58 偏移处
        assertEquals(0, forSign[58], "getRawForSign 的签名区必须是 64 零字节");
        assertEquals(5, withSig[58]);
        // 载荷尾部：payloadLength 头 + payload 本体
        assertEquals(1, withSig[withSig.length - 1]);
    }

    @Test
    void rawEncodingLayoutIsStable() {
        Transaction tx = sampleTx(Type.VOTE.ordinal(), new byte[]{1, 2});
        // 布局 1+1+8+32+8+8+64+20+4 = 146 头部 + 2 payload
        assertEquals(148, tx.getRawForHash().length);
        assertEquals(1, tx.getRawForHash()[0]);
        assertEquals(Type.VOTE.ordinal(), tx.getRawForHash()[1]);
    }

    @Test
    void nullPayloadEncodesLengthZero() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), null);
        byte[] raw = tx.getRawForHash();
        // 尾部 4 字节 payloadLength = 0
        assertArrayEquals(new byte[4], Arrays.copyOfRange(raw, raw.length - 4, raw.length));
    }

    // ===== size / hashHexString（:316/321） =====

    @Test
    void sizeIncludesRawAndHash() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), null);
        assertEquals(tx.getRawForHash().length + tx.getHash().length, tx.size());
    }

    @Test
    void hashHexStringMatchesHexOfHash() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), null);
        String hex = tx.getHashHexString();
        assertEquals(org.apache.commons.codec.binary.Hex.encodeHexString(tx.getHash()), hex);
        // 缓存：同一实例重复取值一致
        assertSame(hex, tx.getHashHexString());
    }

    // ===== getFee（:330）——gasPrice × GAS_TABLE[type] =====

    @Test
    void feeMultipliesGasPriceByTableEntry() {
        // GAS_TABLE[TRANSFER]=50000（:87——注释把 COINBASE/TRANSFER/VOTE 写在同排，
        // 实际下标 1 是 50000）
        Transaction t1 = sampleTx(Type.TRANSFER.ordinal(), null);
        t1.gasPrice = 3;
        assertEquals(3L * 50000L, t1.getFee());

        Transaction t0 = sampleTx(Type.COINBASE.ordinal(), null); // GAS_TABLE[0]=0
        t0.gasPrice = 100;
        assertEquals(0, t0.getFee());
    }

    // ===== getdays（:335）——INCUBATE 才解析，非 INCUBATE 直接 0 =====

    @Test
    void getdaysReturnsZeroForNonIncubateType() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), new byte[]{1});
        assertEquals(0, tx.getdays());
    }

    // ===== getInterest/getShare（:349/362）——仅 INCUBATE 计算 =====

    @Test
    void interestOnlyComputedForIncubateType() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), null);
        assertEquals(0, tx.getInterest(100, null, 30));
        assertEquals(0, tx.getShare(100, null, 30));
    }    @Test
    void interestFormulaAndShareRatio() {
        Transaction tx = sampleTx(Type.INCUBATE.ordinal(), null);
        tx.amount = 1000;
        org.nexus.core.incubator.RateTable table =
                new org.nexus.core.incubator.RateTable();
        // 覆写 selectrate 消除查表状态依赖（RateTable 为具体类，可匿名覆写）
        org.nexus.core.incubator.RateTable stub = new org.nexus.core.incubator.RateTable() {
            @Override
            public String selectrate(long height, int days) {
                return "0.01";
            }
        };
        // rate 表：0.01 → interest = days × amount × rate = 30×1000×0.01 = 300
        assertEquals(300, tx.getInterest(100, stub, 30));
        // share = interest × 0.1 = 30（:366 硬编码 0.1 分成）
        assertEquals(30, tx.getShare(100, stub, 30));
        // 未覆写的原表行为在此不测（依赖 era/ratemap 状态），仅证明桩可注入
        assertNotNull(table);
    }

    // ===== 支付扩展分类器（:377-422） =====

    @Test
    void paymentExtensionTypeBoundaries() {
        assertTrue(sampleTx(Type.CHANNEL_OPEN.ordinal(), null).isPaymentExtensionType());
        assertTrue(sampleTx(Type.SUBSCRIPTION_AUTH.ordinal(), null).isPaymentExtensionType());
        assertFalse(sampleTx(Type.EXIT_MORTGAGE.ordinal(), null).isPaymentExtensionType());
        // 恰好在边界外的旧类型
        assertFalse(sampleTx(Type.MORTGAGE.ordinal(), null).isPaymentExtensionType());
    }

    @Test
    void channelStablecoinBridgeClassifiers() {
        for (Type t : new Type[]{Type.CHANNEL_OPEN, Type.CHANNEL_UPDATE, Type.CHANNEL_CLOSE}) {
            assertTrue(sampleTx(t.ordinal(), null).isChannelTransaction(), t.name());
        }
        for (Type t : new Type[]{Type.MINT_STABLECOIN, Type.REDEEM_STABLECOIN}) {
            assertTrue(sampleTx(t.ordinal(), null).isStableCoinTransaction(), t.name());
        }
        for (Type t : new Type[]{Type.BRIDGE_LOCK, Type.BRIDGE_MINT, Type.BRIDGE_BURN}) {
            assertTrue(sampleTx(t.ordinal(), null).isBridgeTransaction(), t.name());
        }
        // 交叉互斥：CHANNEL_OPEN 不是稳定币交易
        assertFalse(sampleTx(Type.CHANNEL_OPEN.ordinal(), null).isStableCoinTransaction());
        assertFalse(sampleTx(Type.TRANSFER.ordinal(), null).isChannelTransaction());
        assertFalse(sampleTx(Type.TRANSFER.ordinal(), null).isStableCoinTransaction());
        assertFalse(sampleTx(Type.TRANSFER.ordinal(), null).isBridgeTransaction());
    }

    @Test
    void hasPayloadChecksNonEmpty() {
        assertFalse(sampleTx(Type.TRANSFER.ordinal(), null).hasPayload());
        assertFalse(sampleTx(Type.TRANSFER.ordinal(), new byte[]{}).hasPayload());
        assertTrue(sampleTx(Type.TRANSFER.ordinal(), new byte[]{1}).hasPayload());
    }

    @Test
    void getTypeNameHandlesKnownAndUnknown() {
        assertEquals("TRANSFER", sampleTx(Type.TRANSFER.ordinal(), null).getTypeName());
        assertEquals("BRIDGE_LOCK", sampleTx(Type.BRIDGE_LOCK.ordinal(), null).getTypeName());
        Transaction weird = sampleTx(0, null);
        weird.type = 99;
        assertEquals("UNKNOWN(99)", weird.getTypeName());
    }

    // ===== RPC 字节往返（:447/452） =====

    @Test
    void rpcBytesRoundTripPreservesFields() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), new byte[]{7, 8, 9});
        byte[] wire = tx.toRPCBytes();
        Transaction back = Transaction.fromRPCBytes(wire);
        assertEquals(tx.version, back.version);
        assertEquals(tx.type, back.type);
        assertEquals(tx.nonce, back.nonce);
        assertArrayEquals(tx.from, back.from);
        assertEquals(tx.gasPrice, back.gasPrice);
        assertEquals(tx.amount, back.amount);
        assertArrayEquals(tx.signature, back.signature);
        assertArrayEquals(tx.to, back.to);
        assertArrayEquals(tx.payload, back.payload);
        assertArrayEquals(tx.getHash(), back.getHash(), "哈希由字段决定，往返必须一致");
    }

    @Test
    void rpcBytesRoundTripWithEmptyPayload() {
        Transaction tx = sampleTx(Type.VOTE.ordinal(), null);
        Transaction back = Transaction.fromRPCBytes(tx.toRPCBytes());
        assertNull(back.payload, "payloadLength=0 提前返回（:476），payload 保持 null");
        assertArrayEquals(tx.getHash(), back.getHash());
    }

    // ===== v1.9.4 安全修复回归：payload 上限 fail-closed（:472/527/581） =====

    @Test
    void fromRPCBytesRejectsOversizedPayloadLength() {
        // 构造合法头部 + 声称 payloadLength = MAX+1 的攻击报文
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), null);
        byte[] wire = tx.toRPCBytes();
        // payloadLength 字段位于尾部 4 字节（此例 payload 为空）
        int lenPos = wire.length - 4;
        byte[] evil = Arrays.copyOf(wire, wire.length);
        // 写入 MAX_PAYLOAD_LENGTH+1 = 0x00A00001（大端）
        evil[lenPos] = 0x00;
        evil[lenPos + 1] = (byte) 0xA0;
        evil[lenPos + 2] = 0x00;
        evil[lenPos + 3] = 0x01;
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> Transaction.fromRPCBytes(evil));
        assertTrue(ex.getMessage().contains("payload length exceeds maximum"));
    }

    @Test
    void transformByteRejectsOversizedPayloadLength() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), null);
        byte[] wire = tx.toRPCBytes();
        int lenPos = wire.length - 4;
        byte[] evil = Arrays.copyOf(wire, wire.length);
        evil[lenPos + 1] = (byte) 0xA0;
        evil[lenPos + 3] = 0x01;
        assertThrows(
                IllegalArgumentException.class,
                () -> Transaction.transformByte(evil));
    }

    @Test
    void transformByteRoundTripLegacyPath() {
        // transformByte 与 fromRPCBytes 同布局（deprecated 但保留二进制兼容），
        // 正常路径也应正确往返
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), new byte[]{3});
        Transaction back = Transaction.transformByte(tx.toRPCBytes());
        assertEquals(tx.type, back.type);
        assertEquals(tx.nonce, back.nonce);
        assertArrayEquals(tx.from, back.from);
        assertArrayEquals(tx.to, back.to);
        assertArrayEquals(tx.payload, back.payload);
    }

    // ===== proto 编解码（:184/424） =====
    // 注意：ProtocolModel 的 Type 枚举只定义到旧类型（EXIT_PLEDGE=15）——支付扩展
    // 类型（16+）的 forNumber 返回 null，encode() 在 builder.setType(null) 处 NPE。
    // proto 往返契约现仅对 type≤15 成立；16+ 类型走 RPC 字节路径（上方已测）。

    @Test
    void protoRoundTripPreservesFields() {
        Transaction tx = sampleTx(Type.MORTGAGE.ordinal(), new byte[]{1, 2});
        org.nexus.protobuf.tcp.ProtocolModel.Transaction proto = tx.encode();
        Transaction back = Transaction.fromProto(proto);
        assertEquals(tx.version, back.version);
        assertEquals(tx.type, back.type);
        assertEquals(tx.nonce, back.nonce);
        assertArrayEquals(tx.from, back.from);
        assertEquals(tx.gasPrice, back.gasPrice);
        assertEquals(tx.amount, back.amount);
        assertArrayEquals(tx.payload, back.payload);
        assertArrayEquals(tx.to, back.to);
        assertArrayEquals(tx.signature, back.signature);
    }

    @Test
    void protoEncodeOfPaymentExtensionTypeCurrentlyFails() {
        // 现状钉死：proto Type 枚举缺 16-26 值 → forNumber(null) → NPE。
        // 这是已知限制（proto 生成代码待补枚举）；测试记录现状防静默漂移。
        Transaction tx = sampleTx(Type.BRIDGE_LOCK.ordinal(), null);
        assertThrows(NullPointerException.class, tx::encode);
    }

    @Test
    void protoEncodeCarriesHash() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), null);
        org.nexus.protobuf.tcp.ProtocolModel.Transaction proto = tx.encode();
        assertArrayEquals(tx.getHash(), proto.getHash().toByteArray());
    }

    // ===== changeProtobuf（:538） =====

    @Test
    void changeProtobufRejectsOversizedPayloadLength() {
        Transaction tx = sampleTx(Type.TRANSFER.ordinal(), null);
        byte[] wire = tx.toRPCBytes();
        int lenPos = wire.length - 4;
        byte[] evil = Arrays.copyOf(wire, wire.length);
        evil[lenPos + 1] = (byte) 0xA0;
        evil[lenPos + 3] = 0x01;
        assertThrows(
                IllegalArgumentException.class,
                () -> Transaction.changeProtobuf(evil));
    }

    @Test
    void changeProtobufParsesValidWire() {
        Transaction tx = sampleTx(Type.MORTGAGE.ordinal(), new byte[]{5});
        org.nexus.protobuf.tcp.ProtocolModel.Transaction proto =
                Transaction.changeProtobuf(tx.toRPCBytes());
        assertEquals(tx.version, proto.getVersion());
        assertEquals(tx.type, proto.getType().getNumber());
        assertEquals(tx.nonce, proto.getNonce());
        assertArrayEquals(tx.from, proto.getFrom().toByteArray());
        assertEquals(tx.gasPrice, proto.getGasPrice());
        assertEquals(tx.amount, proto.getAmount());
        assertArrayEquals(tx.payload, proto.getPayload().toByteArray());
        assertArrayEquals(tx.to, proto.getTo().toByteArray());
        assertArrayEquals(tx.signature, proto.getSignature().toByteArray());
        assertArrayEquals(tx.getHash(), proto.getHash().toByteArray());
    }

    // ===== setter/字段直通（:211-221/272） =====

    @Test
    void auxiliarySettersStoreValues() {
        Transaction tx = new Transaction();
        byte[] h = new byte[32];
        tx.setTransactionHash(h);
        tx.setFee(9);
        tx.setDays(30);
        // transactionHash/fee/days 为 Jackson 兼容私有字段——经反射读回验证
        try {
            java.lang.reflect.Field f = Transaction.class.getDeclaredField("transactionHash");
            f.setAccessible(true);
            assertSame(h, f.get(tx));
            java.lang.reflect.Field ff = Transaction.class.getDeclaredField("fee");
            ff.setAccessible(true);
            assertEquals(9, ff.getInt(tx));
            java.lang.reflect.Field fd = Transaction.class.getDeclaredField("days");
            fd.setAccessible(true);
            assertEquals(30, fd.getInt(tx));
        } catch (ReflectiveOperationException e) {
            fail("reflection readback failed: " + e.getMessage());
        }
        // 公开字段直通
        tx.height = 42;
        tx.blockHash = new byte[3];
        assertEquals(42, tx.height);
        assertEquals(3, tx.blockHash.length);
    }

}
