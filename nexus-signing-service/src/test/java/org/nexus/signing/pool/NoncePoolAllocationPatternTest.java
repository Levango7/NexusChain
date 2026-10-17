package org.nexus.signing.pool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.signing.storage.Leveldb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * 把旧分配方式的缺陷钉成断言（#33 的红测面）。
 *
 * <p>{@link NoncePool#getMaxNonce} 是<b>读</b>：返回 {@code TreeMap.lastKey()}，既不摘除也不占位。
 * 而生产调用点（{@code TxController:384}、{@code SigningTccActionImpl:95}）的分配是个跨两次调用的
 * 复合动作——{@code getMaxNonce} → 签名 → {@code nownonce++} → {@code add}——调用点没有任何同步。
 * 于是"在同一次 {@code add} 之前的两次读取"必然拿到同一个值，即<b>两笔交易签同一个 nonce</b>：
 * 上链后必有一笔被替换/丢弃，而两个调用方都已收到"广播成功"。
 *
 * <p>本用例故意<b>不需要多线程</b>就能证明这件事——两次顺序读之间没有 {@code add}，正是并发签名的
 * 交错形状。修复见 {@link NonceAllocator}（认领 = 一个事务内的条件递增 + 读回）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NoncePool 旧分配是读而非认领（#33 缺陷证据）")
class NoncePoolAllocationPatternTest {

    @Mock
    private Leveldb leveldb;

    private NoncePool pool() throws Exception {
        when(leveldb.readFromSnapshot()).thenReturn("");
        doNothing().when(leveldb).addPoolDb(org.mockito.ArgumentMatchers.anyString());
        return new NoncePool(leveldb);
    }

    @Test
    @DisplayName("1.同一地址在两次认领之间没有写回时，读到的 nonce 相同 ⇒ 两笔同号")
    void getMaxNonce_isAReadNotAClaim() throws Exception {
        NoncePool p = pool();
        String address = "0xPLATFORM_ADDRESS";
        // 模拟上一笔转账后池里的"下一个可用值" = 5
        p.add(address, new NonceState("hash-prev", 5L, System.currentTimeMillis()));

        long firstReader = p.getMaxNonce(address);
        long secondReader = p.getMaxNonce(address);

        assertEquals(5L, firstReader);
        assertEquals(firstReader, secondReader,
                "getMaxNonce 不摘除、不占位：第二个调用方在同一次 add 之前读到同一个值——"
                        + "这就是 prod 上两笔交易共用一个 nonce 的成因（多副本只是把它放大）");
    }

    @Test
    @DisplayName("2.写回之后才会前进——证明「前进」依赖调用方自觉，而不是分配动作本身")
    void counterAdvancesOnlyAfterCallerAdds() throws Exception {
        NoncePool p = pool();
        String address = "0xPLATFORM_ADDRESS";
        p.add(address, new NonceState("hash-prev", 7L, System.currentTimeMillis()));

        long before = p.getMaxNonce(address);
        p.add(address, new NonceState("hash-new", before + 1, System.currentTimeMillis()));
        long after = p.getMaxNonce(address);

        assertNotEquals(before, after, "只有 add 写回后水位才前进（分配与写回之间有窗口）");
        assertEquals(8L, after);
    }
}
