package org.nexus.core;

import org.junit.jupiter.api.Test;
import org.nexus.core.account.Transaction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TransactionPool} 入池守卫测试。
 *
 * <p>守卫只做与链上校验等价的快速判定：拒绝全零 from、空签名、0x10–0x1a 支付扩展类型；
 * coinbase 白名单放行；原有合法交易入池行为不变。</p>
 */
public class TransactionPoolGuardTest {

    private static final byte[] FROM = new byte[Transaction.PUBLIC_KEY_SIZE];
    private static final byte[] TO = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
    private static final byte[] SIGNATURE = new byte[Transaction.SIGNATURE_SIZE];

    /** 0x10–0x1a 支付扩展类型。 */
    private static final Transaction.Type[] PAYMENT_EXTENSION_TYPES = {
            Transaction.Type.CHANNEL_OPEN,
            Transaction.Type.CHANNEL_UPDATE,
            Transaction.Type.CHANNEL_CLOSE,
            Transaction.Type.BATCH_TRANSFER,
            Transaction.Type.MINT_STABLECOIN,
            Transaction.Type.REDEEM_STABLECOIN,
            Transaction.Type.BRIDGE_LOCK,
            Transaction.Type.BRIDGE_MINT,
            Transaction.Type.BRIDGE_BURN,
            Transaction.Type.IDENTITY_REGISTER,
            Transaction.Type.SUBSCRIPTION_AUTH
    };

    static {
        FROM[0] = 1;
        SIGNATURE[0] = 1;
    }

    @Test
    public void rejectsAllZeroFrom() {
        TransactionPool pool = new TransactionPool();
        Transaction tx = new Transaction(1, Transaction.Type.TRANSFER.ordinal(), 1L,
                new byte[Transaction.PUBLIC_KEY_SIZE], 100000L, 100L, null, TO, SIGNATURE);

        assertFalse(pool.add(tx), "全零 from 应被拒绝");
        assertEquals(0, pool.size());
        assertFalse(pool.has(tx.getHashHexString()));
    }

    @Test
    public void rejectsEmptySignature() {
        TransactionPool pool = new TransactionPool();
        Transaction tx = new Transaction(1, Transaction.Type.TRANSFER.ordinal(), 1L,
                FROM, 100000L, 100L, null, TO, new byte[Transaction.SIGNATURE_SIZE]);

        assertFalse(pool.add(tx), "空签名应被拒绝");
        assertEquals(0, pool.size());
    }

    @Test
    public void rejectsPaymentExtensionTypes() {
        TransactionPool pool = new TransactionPool();
        // 0x10–0x1a 全部无法通过 TransactionCheck 的类型门禁
        for (Transaction.Type type : PAYMENT_EXTENSION_TYPES) {
            Transaction tx = new Transaction(1, type.ordinal(), 1L,
                    FROM, 100000L, 100L, null, TO, SIGNATURE);
            assertFalse(pool.add(tx), type + " 应被入池守卫拒绝");
        }
        assertEquals(0, pool.size(), "扩展类型交易不应进入交易池");
    }

    @Test
    public void acceptsCoinbaseWithoutFromOrSignature() {
        TransactionPool pool = new TransactionPool();
        Transaction coinbase = new Transaction(1, Transaction.Type.COINBASE.ordinal(), 0L,
                new byte[Transaction.PUBLIC_KEY_SIZE], 0L, 100L, null,
                new byte[Transaction.PUBLIC_KEY_HASH_SIZE], new byte[Transaction.SIGNATURE_SIZE]);

        assertTrue(pool.add(coinbase), "coinbase 应白名单放行");
        assertEquals(1, pool.size());
    }

    @Test
    public void acceptsValidTransfer() {
        TransactionPool pool = new TransactionPool();
        Transaction tx = new Transaction(1, Transaction.Type.TRANSFER.ordinal(), 1L,
                FROM, 100000L, 100L, null, TO, SIGNATURE);

        assertTrue(pool.add(tx), "原有合法交易路径不应受影响");
        assertEquals(1, pool.size());
        assertTrue(pool.has(tx.getHashHexString()));
    }

    @Test
    public void duplicateIsAcceptedOnce() {
        TransactionPool pool = new TransactionPool();
        Transaction tx = new Transaction(1, Transaction.Type.TRANSFER.ordinal(), 1L,
                FROM, 100000L, 100L, null, TO, SIGNATURE);

        assertTrue(pool.add(tx));
        assertTrue(pool.add(tx), "重复交易视为已接受");
        assertEquals(1, pool.size());
    }
}
