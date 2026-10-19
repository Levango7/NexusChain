package org.nexus.command;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.account.Account;
import org.nexus.core.account.Transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 批次⑥ 加固回归：账户 nonce 上界（{@link TransactionCheck#MAX_TX_NONCE}）。
 *
 * <p>覆盖：上界内通过 / 上界+1 拒绝 / {@code Long.MAX_VALUE} 拒绝 / 既有
 * "Nonce is too small" 语义保持。直接构造 {@link TransactionCheck}——nonce 分支
 * 不触达 @Autowired 字段，测试仅经历校验前置路径（转账类型 + 充足余额）。</p>
 */
public class TransactionNonceBoundTest {

    private static Transaction transferTx(long nonce) {
        Transaction tx = new Transaction();
        tx.type = Transaction.Type.TRANSFER.ordinal();
        tx.nonce = nonce;
        tx.from = new byte[Transaction.PUBLIC_KEY_SIZE];
        tx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        tx.gasPrice = 0;
        tx.amount = 1L;
        tx.payload = null;
        return tx;
    }

    private static Account account(long nonce) {
        return new Account(0, new byte[Transaction.PUBLIC_KEY_HASH_SIZE], nonce, 1_000_000L, 0, 0, 0);
    }

    @Test
    public void acceptsNonceAtUpperBound() {
        APIResult r = new TransactionCheck().TransactionVerify(
                transferTx(TransactionCheck.MAX_TX_NONCE), account(0), null);
        assertEquals(2000, r.getCode(), r.getMessage());
    }

    @Test
    public void rejectsNonceAboveUpperBound() {
        APIResult r = new TransactionCheck().TransactionVerify(
                transferTx(TransactionCheck.MAX_TX_NONCE + 1), account(0), null);
        assertEquals(5000, r.getCode());
        assertEquals("Nonce out of range", r.getMessage());
    }

    @Test
    public void rejectsLongMaxNonce() {
        APIResult r = new TransactionCheck().TransactionVerify(
                transferTx(Long.MAX_VALUE), account(0), null);
        assertEquals(5000, r.getCode());
        assertEquals("Nonce out of range", r.getMessage());
    }

    @Test
    public void keepsTooSmallSemantics() {
        APIResult r = new TransactionCheck().TransactionVerify(
                transferTx(5), account(5), null);
        assertEquals(5000, r.getCode());
        assertEquals("Nonce is too small", r.getMessage());
    }

    @Test
    public void acceptsNormalNextNonce() {
        APIResult r = new TransactionCheck().TransactionVerify(
                transferTx(6), account(5), null);
        assertEquals(2000, r.getCode(), r.getMessage());
    }
}
