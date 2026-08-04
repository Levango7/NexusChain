package org.nexus.core.payment;

import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BridgeTransaction.State;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 跨链桥交易验证流测试。
 *
 * <p>验证 BridgeTransaction 的状态转换、签名阈值验证、
 * 时间锁检查和失败/过期处理。</p>
 *
 * <p>跨链流程：
 * <pre>
 *   lock() -> LOCKED -> mint() -> MINTED -> burn() -> BURNED -> unlock() -> UNLOCKED
 *   任意状态 -> fail()/expire() -> FAILED
 * </pre></p>
 *
 * <p>测试通过辅助方法封装验证逻辑，不依赖 Spring 容器，为纯单元测试。
 * 签名阈值与 BridgeRule 一致，默认最低验证人数为 3。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class BridgeTransactionTest {

    /** 最低验证人签名数。 */
    private static final int MIN_VALIDATORS = 3;
    /** 测试用源链标识。 */
    private static final String SOURCE_CHAIN = "nexus-mainnet";
    /** 测试用目标链标识。 */
    private static final String TARGET_CHAIN = "nexus-sidechain";
    /** 测试用收款人地址。 */
    private static final String RECIPIENT = "NEX_recipient_001";

    // ==================== 辅助方法 ====================

    /**
     * 创建一个测试用桥交易（未锁定状态）。
     *
     * @param amount        跨链金额
     * @param timelockExpiry 时间锁到期时间戳（秒）
     * @return 桥交易对象
     */
    private BridgeTransaction createBridgeTx(long amount, long timelockExpiry) {
        List<String> validators = Arrays.asList("val-1", "val-2", "val-3", "val-4");
        List<String> signatures = Arrays.asList("sig-1", "sig-2", "sig-3", "sig-4");
        return new BridgeTransaction(
                "bridge-tx-001", SOURCE_CHAIN, TARGET_CHAIN,
                amount, RECIPIENT, validators, signatures,
                null, System.currentTimeMillis() / 1000, timelockExpiry, MIN_VALIDATORS
        );
    }

    /**
     * 模拟锁定操作，将状态转换为 LOCKED。
     *
     * @param bridgeTx 桥交易对象
     * @throws IllegalStateException 如果参数校验失败
     */
    private void lock(BridgeTransaction bridgeTx) {
        if (bridgeTx.getAmount() <= 0) {
            throw new IllegalStateException("BRIDGE_LOCK: 锁定金额须大于 0");
        }
        if (bridgeTx.getSourceChain() == null || bridgeTx.getSourceChain().isEmpty()) {
            throw new IllegalStateException("BRIDGE_LOCK: 源链标识不能为空");
        }
        if (bridgeTx.getTargetChain() == null || bridgeTx.getTargetChain().isEmpty()) {
            throw new IllegalStateException("BRIDGE_LOCK: 目标链标识不能为空");
        }
        if (bridgeTx.getRecipient() == null || bridgeTx.getRecipient().isEmpty()) {
            throw new IllegalStateException("BRIDGE_LOCK: 收款人地址不能为空");
        }
        bridgeTx.setState(State.LOCKED);
    }

    /**
     * 检查签名数量是否达到阈值。
     *
     * @param bridgeTx 桥交易对象
     * @return 如果签名数 >= 最低验证人数返回 true
     */
    private boolean hasSufficientSignatures(BridgeTransaction bridgeTx) {
        if (bridgeTx.getSignatures() == null) {
            return false;
        }
        return bridgeTx.getSignatures().size() >= MIN_VALIDATORS;
    }

    /**
     * 检查时间锁是否已到期。
     *
     * @param bridgeTx    桥交易对象
     * @param currentTime 当前时间戳（秒）
     * @return 如果时间锁已到期返回 true
     */
    private boolean isTimelockExpired(BridgeTransaction bridgeTx, long currentTime) {
        return currentTime > bridgeTx.getTimelockExpiry();
    }

    /**
     * 模拟铸造操作，将状态从 LOCKED 转换为 MINTED。
     *
     * @param bridgeTx    桥交易对象
     * @param currentTime 当前时间戳（秒）
     * @throws IllegalStateException 如果条件不满足
     */
    private void mint(BridgeTransaction bridgeTx, long currentTime) {
        if (bridgeTx.getState() != State.LOCKED) {
            throw new IllegalStateException(
                    "BRIDGE_MINT: 须在 LOCKED 状态才能铸造，当前状态: " + bridgeTx.getState());
        }
        if (bridgeTx.getAmount() <= 0) {
            throw new IllegalStateException("BRIDGE_MINT: 铸造金额须大于 0");
        }
        if (!hasSufficientSignatures(bridgeTx)) {
            int count = bridgeTx.getSignatures() != null ? bridgeTx.getSignatures().size() : 0;
            throw new IllegalStateException(
                    "BRIDGE_MINT: 签名数 " + count + " 低于最低要求 " + MIN_VALIDATORS);
        }
        if (!isTimelockExpired(bridgeTx, currentTime)) {
            throw new IllegalStateException(
                    "BRIDGE_MINT: 时间锁未到期，剩余 "
                            + (bridgeTx.getTimelockExpiry() - currentTime) + " 秒");
        }
        bridgeTx.setState(State.MINTED);
    }

    /**
     * 模拟销毁操作，将状态从 MINTED 转换为 BURNED。
     *
     * @param bridgeTx    桥交易对象
     * @param currentTime 当前时间戳（秒）
     * @throws IllegalStateException 如果条件不满足
     */
    private void burn(BridgeTransaction bridgeTx, long currentTime) {
        if (bridgeTx.getState() != State.MINTED) {
            throw new IllegalStateException(
                    "BRIDGE_BURN: 须在 MINTED 状态才能销毁，当前状态: " + bridgeTx.getState());
        }
        if (bridgeTx.getAmount() <= 0) {
            throw new IllegalStateException("BRIDGE_BURN: 销毁金额须大于 0");
        }
        if (!hasSufficientSignatures(bridgeTx)) {
            int count = bridgeTx.getSignatures() != null ? bridgeTx.getSignatures().size() : 0;
            throw new IllegalStateException(
                    "BRIDGE_BURN: 签名数 " + count + " 低于最低要求 " + MIN_VALIDATORS);
        }
        if (!isTimelockExpired(bridgeTx, currentTime)) {
            throw new IllegalStateException(
                    "BRIDGE_BURN: 时间锁未到期，剩余 "
                            + (bridgeTx.getTimelockExpiry() - currentTime) + " 秒");
        }
        bridgeTx.setState(State.BURNED);
    }

    /**
     * 模拟解锁操作，将状态从 BURNED 转换为 UNLOCKED。
     *
     * @param bridgeTx    桥交易对象
     * @param currentTime 当前时间戳（秒）
     * @throws IllegalStateException 如果条件不满足
     */
    private void unlock(BridgeTransaction bridgeTx, long currentTime) {
        if (bridgeTx.getState() != State.BURNED) {
            throw new IllegalStateException(
                    "BRIDGE_UNLOCK: 须在 BURNED 状态才能解锁，当前状态: " + bridgeTx.getState());
        }
        if (!isTimelockExpired(bridgeTx, currentTime)) {
            throw new IllegalStateException(
                    "BRIDGE_UNLOCK: 时间锁未到期，剩余 "
                            + (bridgeTx.getTimelockExpiry() - currentTime) + " 秒");
        }
        bridgeTx.setState(State.UNLOCKED);
    }

    /**
     * 模拟失败操作。
     *
     * @param bridgeTx 桥交易对象
     */
    private void fail(BridgeTransaction bridgeTx) {
        if (bridgeTx.getState() == State.UNLOCKED) {
            throw new IllegalStateException(
                    "BRIDGE_FAIL: 已完成的交易不能标记为失败");
        }
        bridgeTx.setState(State.FAILED);
    }

    /**
     * 模拟过期操作。
     *
     * @param bridgeTx       桥交易对象
     * @param currentTime    当前时间戳（秒）
     * @param maxDuration    最大允许持续时间（秒）
     * @throws IllegalStateException 如果交易未过期
     */
    private void expire(BridgeTransaction bridgeTx, long currentTime, long maxDuration) {
        if (bridgeTx.getState() == State.UNLOCKED || bridgeTx.getState() == State.FAILED) {
            throw new IllegalStateException(
                    "BRIDGE_EXPIRE: 交易已结束，当前状态: " + bridgeTx.getState());
        }
        long elapsed = currentTime - bridgeTx.getTimestamp();
        if (elapsed <= maxDuration) {
            throw new IllegalStateException(
                    "BRIDGE_EXPIRE: 交易未过期，已持续 " + elapsed + " 秒，上限 " + maxDuration + " 秒");
        }
        bridgeTx.setState(State.FAILED);
    }

    // ==================== lock 状态转换测试 ====================

    /**
     * 测试 lock() 状态转换
     */
    @Test
    public void testLockTransition() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        assertNull(bridgeTx.getState());

        lock(bridgeTx);
        assertEquals(State.LOCKED, bridgeTx.getState());
    }

    /**
     * 测试 lock() 金额为零时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testLockZeroAmount() {
        BridgeTransaction bridgeTx = createBridgeTx(0L, 0L);
        lock(bridgeTx);
    }

    /**
     * 测试 lock() 金额为负时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testLockNegativeAmount() {
        BridgeTransaction bridgeTx = createBridgeTx(-1000L, 0L);
        lock(bridgeTx);
    }

    /**
     * 测试 lock() 源链为空时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testLockEmptySourceChain() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setSourceChain("");
        lock(bridgeTx);
    }

    /**
     * 测试 lock() 目标链为空时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testLockEmptyTargetChain() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setTargetChain(null);
        lock(bridgeTx);
    }

    // ==================== 签名阈值验证测试 ====================

    /**
     * 测试签名数量达到阈值时验证通过
     */
    @Test
    public void testSufficientSignatures() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        // createBridgeTx 设置了 4 个签名，>= 3
        assertTrue(hasSufficientSignatures(bridgeTx));
    }

    /**
     * 测试签名数量恰好等于阈值时验证通过
     */
    @Test
    public void testSignaturesAtMinimum() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setSignatures(Arrays.asList("sig-1", "sig-2", "sig-3"));
        // 恰好 3 个签名 = 最低要求
        assertTrue(hasSufficientSignatures(bridgeTx));
    }

    /**
     * 测试签名数量不足时验证失败
     */
    @Test
    public void testInsufficientSignatures() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setSignatures(Arrays.asList("sig-1", "sig-2"));
        // 只有 2 个签名 < 3
        assertFalse(hasSufficientSignatures(bridgeTx));
    }

    /**
     * 测试签名为 null 时验证失败
     */
    @Test
    public void testNullSignatures() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setSignatures(null);
        assertFalse(hasSufficientSignatures(bridgeTx));
    }

    /**
     * 测试签名为空数组时验证失败
     */
    @Test
    public void testEmptySignatures() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setSignatures(new ArrayList<>());
        assertFalse(hasSufficientSignatures(bridgeTx));
    }

    /**
     * 测试签名不足时 mint() 抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testMintWithInsufficientSignatures() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setSignatures(Arrays.asList("sig-1", "sig-2"));
        lock(bridgeTx);
        // 时间锁已过期（timelockExpiry = 0），但签名不足
        mint(bridgeTx, 1L);
    }

    // ==================== mint 条件检查测试 ====================

    /**
     * 测试 mint() 条件满足时状态转换为 MINTED
     */
    @Test
    public void testMintSuccess() {
        // 时间锁到期时间为 0，当前时间为 1，已过期
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        lock(bridgeTx);

        mint(bridgeTx, 1L);
        assertEquals(State.MINTED, bridgeTx.getState());
    }

    /**
     * 测试 mint() 在非 LOCKED 状态时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testMintFromWrongState() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        // 未先执行 lock，状态为 null
        mint(bridgeTx, 1L);
    }

    /**
     * 测试 mint() 时间锁未到期时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testMintTimelockNotExpired() {
        // 时间锁到期时间为未来 1000 秒
        long futureTimelock = System.currentTimeMillis() / 1000 + 1000;
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, futureTimelock);
        lock(bridgeTx);

        long currentTime = System.currentTimeMillis() / 1000;
        mint(bridgeTx, currentTime);
    }

    /**
     * 测试 mint() 金额为零时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testMintZeroAmount() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setAmount(0L);
        lock(bridgeTx);
        mint(bridgeTx, 1L);
    }

    // ==================== unlock 时间锁测试 ====================

    /**
     * 测试完整跨链流程：lock -> mint -> burn -> unlock
     */
    @Test
    public void testFullBridgeFlow() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        long currentTime = 1L;

        // lock
        lock(bridgeTx);
        assertEquals(State.LOCKED, bridgeTx.getState());

        // mint（时间锁已过期，签名充足）
        mint(bridgeTx, currentTime);
        assertEquals(State.MINTED, bridgeTx.getState());

        // burn
        burn(bridgeTx, currentTime);
        assertEquals(State.BURNED, bridgeTx.getState());

        // unlock
        unlock(bridgeTx, currentTime);
        assertEquals(State.UNLOCKED, bridgeTx.getState());
    }

    /**
     * 测试 unlock() 时间锁未到期时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testUnlockTimelockNotExpired() {
        // 时间锁到期时间为未来
        long futureTimelock = System.currentTimeMillis() / 1000 + 2000;
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, futureTimelock);

        lock(bridgeTx);
        long currentTime = System.currentTimeMillis() / 1000;

        // mint 和 burn 需要时间锁过期，这里跳过直接设置状态
        bridgeTx.setState(State.MINTED);
        bridgeTx.setState(State.BURNED);

        // unlock 时时间锁未到期
        unlock(bridgeTx, currentTime);
    }

    /**
     * 测试 unlock() 在非 BURNED 状态时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testUnlockFromWrongState() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        lock(bridgeTx);
        // 从 LOCKED 状态直接 unlock，未经 BURNED
        unlock(bridgeTx, 1L);
    }

    /**
     * 测试 unlock() 时间锁恰好到期时允许（当前时间须严格大于到期时间）
     */
    @Test(expected = IllegalStateException.class)
    public void testUnlockTimelockExactlyAtExpiry() {
        long timelockExpiry = 5000L;
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, timelockExpiry);
        lock(bridgeTx);
        bridgeTx.setState(State.MINTED);
        bridgeTx.setState(State.BURNED);
        // 当前时间等于到期时间，须严格大于
        unlock(bridgeTx, timelockExpiry);
    }

    // ==================== fail 和 expire 测试 ====================

    /**
     * 测试 fail() 将交易标记为 FAILED
     */
    @Test
    public void testFail() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        lock(bridgeTx);
        assertEquals(State.LOCKED, bridgeTx.getState());

        fail(bridgeTx);
        assertEquals(State.FAILED, bridgeTx.getState());
    }

    /**
     * 测试 fail() 从 MINTED 状态
     */
    @Test
    public void testFailFromMinted() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        lock(bridgeTx);
        mint(bridgeTx, 1L);
        assertEquals(State.MINTED, bridgeTx.getState());

        fail(bridgeTx);
        assertEquals(State.FAILED, bridgeTx.getState());
    }

    /**
     * 测试 fail() 对已完成的交易抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testFailOnCompletedTx() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        lock(bridgeTx);
        mint(bridgeTx, 1L);
        burn(bridgeTx, 1L);
        unlock(bridgeTx, 1L);
        assertEquals(State.UNLOCKED, bridgeTx.getState());

        // 已完成的交易不能标记为失败
        fail(bridgeTx);
    }

    /**
     * 测试 expire() 在交易超时后标记为 FAILED
     */
    @Test
    public void testExpireAfterTimeout() {
        long timestamp = 1000L;
        long maxDuration = 3600L; // 最大允许 1 小时
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setTimestamp(timestamp);
        lock(bridgeTx);

        // 当前时间已超过最大持续时间
        long currentTime = timestamp + maxDuration + 1;
        expire(bridgeTx, currentTime, maxDuration);
        assertEquals(State.FAILED, bridgeTx.getState());
    }

    /**
     * 测试 expire() 在交易未超时时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testExpireBeforeTimeout() {
        long timestamp = 1000L;
        long maxDuration = 3600L;
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setTimestamp(timestamp);
        lock(bridgeTx);

        // 当前时间未超过最大持续时间
        long currentTime = timestamp + 1000;
        expire(bridgeTx, currentTime, maxDuration);
    }

    /**
     * 测试 expire() 对已完成的交易抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testExpireOnCompletedTx() {
        long timestamp = 1000L;
        long maxDuration = 3600L;
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setTimestamp(timestamp);
        lock(bridgeTx);
        mint(bridgeTx, timestamp + 1);
        burn(bridgeTx, timestamp + 2);
        unlock(bridgeTx, timestamp + 3);
        assertEquals(State.UNLOCKED, bridgeTx.getState());

        expire(bridgeTx, timestamp + maxDuration + 1, maxDuration);
    }

    /**
     * 测试 expire() 对已失败的交易抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testExpireOnFailedTx() {
        long timestamp = 1000L;
        long maxDuration = 3600L;
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 0L);
        bridgeTx.setTimestamp(timestamp);
        lock(bridgeTx);
        fail(bridgeTx);
        assertEquals(State.FAILED, bridgeTx.getState());

        expire(bridgeTx, timestamp + maxDuration + 1, maxDuration);
    }

    // ==================== 全参数构造器和 setter 测试 ====================

    /**
     * 测试全参数构造器正确设置所有字段
     */
    @Test
    public void testFullConstructor() {
        List<String> validators = Arrays.asList("val-1", "val-2", "val-3");
        List<String> signatures = Arrays.asList("sig-1", "sig-2", "sig-3");
        BridgeTransaction bridgeTx = new BridgeTransaction(
                "bridge-tx-002", SOURCE_CHAIN, TARGET_CHAIN,
                500000L, RECIPIENT, validators, signatures,
                State.LOCKED, 1000L, 5000L, MIN_VALIDATORS
        );

        assertEquals("bridge-tx-002", bridgeTx.getBridgeTxId());
        assertEquals(SOURCE_CHAIN, bridgeTx.getSourceChain());
        assertEquals(TARGET_CHAIN, bridgeTx.getTargetChain());
        assertEquals(500000L, bridgeTx.getAmount());
        assertEquals(RECIPIENT, bridgeTx.getRecipient());
        assertEquals(3, bridgeTx.getValidators().size());
        assertEquals(3, bridgeTx.getSignatures().size());
        assertEquals(State.LOCKED, bridgeTx.getState());
        assertEquals(1000L, bridgeTx.getTimestamp());
        assertEquals(5000L, bridgeTx.getTimelockExpiry());
    }

    /**
     * 测试默认构造器和 setter
     */
    @Test
    public void testDefaultConstructorAndSetters() {
        BridgeTransaction bridgeTx = new BridgeTransaction();

        bridgeTx.setBridgeTxId("bridge-tx-003");
        bridgeTx.setSourceChain("chain-A");
        bridgeTx.setTargetChain("chain-B");
        bridgeTx.setAmount(999999L);
        bridgeTx.setRecipient("NEX_recipient_002");
        bridgeTx.setValidators(Arrays.asList("v1", "v2"));
        bridgeTx.setSignatures(Arrays.asList("s1", "s2"));
        bridgeTx.setState(State.MINTED);
        bridgeTx.setTimestamp(2000L);
        bridgeTx.setTimelockExpiry(8000L);

        assertEquals("bridge-tx-003", bridgeTx.getBridgeTxId());
        assertEquals("chain-A", bridgeTx.getSourceChain());
        assertEquals("chain-B", bridgeTx.getTargetChain());
        assertEquals(999999L, bridgeTx.getAmount());
        assertEquals("NEX_recipient_002", bridgeTx.getRecipient());
        assertEquals(2, bridgeTx.getValidators().size());
        assertEquals(2, bridgeTx.getSignatures().size());
        assertEquals(State.MINTED, bridgeTx.getState());
        assertEquals(2000L, bridgeTx.getTimestamp());
        assertEquals(8000L, bridgeTx.getTimelockExpiry());
    }

    // ==================== State 枚举测试 ====================

    /**
     * 测试 State 枚举包含所有 8 种状态
     */
    @Test
    public void testStateEnumValues() {
        State[] states = State.values();
        assertEquals(8, states.length);
        assertNotNull(State.valueOf("PENDING"));
        assertNotNull(State.valueOf("LOCKED"));
        assertNotNull(State.valueOf("VALIDATING"));
        assertNotNull(State.valueOf("MINTED"));
        assertNotNull(State.valueOf("BURNED"));
        assertNotNull(State.valueOf("UNLOCKED"));
        assertNotNull(State.valueOf("FAILED"));
        assertNotNull(State.valueOf("EXPIRED"));
    }

    // ==================== Transaction 类型关联测试 ====================

    /**
     * 测试 BRIDGE_LOCK, BRIDGE_MINT, BRIDGE_BURN 交易类型
     */
    @Test
    public void testBridgeTransactionTypes() {
        Transaction tx = Transaction.createEmpty();

        tx.type = Transaction.Type.BRIDGE_LOCK.ordinal();
        assertEquals(22, tx.type);
        assertEquals("BRIDGE_LOCK", tx.getTypeName());
        assertTrue(tx.isBridgeTransaction());
        assertTrue(tx.isPaymentExtensionType());

        tx.type = Transaction.Type.BRIDGE_MINT.ordinal();
        assertEquals(23, tx.type);
        assertEquals("BRIDGE_MINT", tx.getTypeName());
        assertTrue(tx.isBridgeTransaction());

        tx.type = Transaction.Type.BRIDGE_BURN.ordinal();
        assertEquals(24, tx.type);
        assertEquals("BRIDGE_BURN", tx.getTypeName());
        assertTrue(tx.isBridgeTransaction());

        // 非桥类型
        tx.type = Transaction.Type.TRANSFER.ordinal();
        assertFalse(tx.isBridgeTransaction());
    }

    // ==================== 时间锁检查测试 ====================

    /**
     * 测试时间锁检查：已过期返回 true
     */
    @Test
    public void testTimelockExpired() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 1000L);
        // 当前时间 > 到期时间
        assertTrue(isTimelockExpired(bridgeTx, 1001L));
    }

    /**
     * 测试时间锁检查：未过期返回 false
     */
    @Test
    public void testTimelockNotExpired() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 5000L);
        // 当前时间 < 到期时间
        assertFalse(isTimelockExpired(bridgeTx, 4999L));
    }

    /**
     * 测试时间锁检查：恰好等于到期时间返回 false（须严格大于）
     */
    @Test
    public void testTimelockAtExpiry() {
        BridgeTransaction bridgeTx = createBridgeTx(1000000L, 5000L);
        // 当前时间等于到期时间，不算过期
        assertFalse(isTimelockExpired(bridgeTx, 5000L));
    }
}
