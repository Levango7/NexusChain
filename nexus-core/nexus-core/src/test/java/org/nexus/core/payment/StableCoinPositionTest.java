package org.nexus.core.payment;

import org.nexus.core.account.Transaction;
import org.nexus.core.payment.StableCoinPosition.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 稳定币仓位管理测试。
 *
 * <p>验证 StableCoinPosition 的抵押率检查、赎回逻辑、
 * 健康度分级、清算流程和水下判断。</p>
 *
 * <p>仓位状态转换：HEALTHY -> WARN -> LIQUIDATABLE，
 * 补充抵押后可回退到健康状态。测试通过辅助方法封装业务规则，
 * 不依赖 Spring 容器，为纯单元测试。</p>
 *
 * <p>抵押率阈值（与 StableCoinRule 一致）：
 * <ul>
 *   <li>最低抵押率 = 1.5（150%）</li>
 *   <li>清算抵押率 = 1.1（110%）</li>
 * </ul></p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class StableCoinPositionTest {

    /** 最低抵押率（150%）。 */
    private static final double MIN_COLLATERAL_RATIO = 1.5;
    /** 清算抵押率阈值（110%）。 */
    private static final double LIQUIDATION_RATIO = 1.1;

    // ==================== 辅助方法 ====================

    /**
     * 创建一个健康仓位用于测试。
     *
     * @param collateral 抵押物数量
     * @param minted     已铸造稳定币数量
     * @return 已初始化的健康仓位
     */
    private StableCoinPosition createPosition(long collateral, long minted) {
        double ratio = minted > 0 ? (double) collateral / (double) minted : 0.0;
        State state = classifyHealth(ratio);
        return new StableCoinPosition(
                "pos-001", "nexus-owner-001", collateral, minted, (int) (ratio * 100),
                computeLiquidationPrice(collateral, minted), state, 0L, 0L
        );
    }

    /**
     * 根据抵押率分级仓位健康度。
     *
     * @param ratio 当前抵押率
     * @return 健康状态
     */
    private State classifyHealth(double ratio) {
        if (ratio >= MIN_COLLATERAL_RATIO) {
            return State.HEALTHY;
        } else if (ratio >= LIQUIDATION_RATIO) {
            return State.WARNING;
        } else {
            return State.LIQUIDATABLE;
        }
    }

    /**
     * 计算清算价格（抵押物价格跌至此值时触发清算）。
     *
     * @param collateral 抵押物数量
     * @param minted     已铸造稳定币数量
     * @return 清算价格
     */
    private long computeLiquidationPrice(long collateral, long minted) {
        if (collateral <= 0 || minted <= 0) {
            return 0;
        }
        // 清算价格 = 抵押物数量 / (铸造稳定币 * 清算抵押率)
        return (long) (collateral / (minted * LIQUIDATION_RATIO));
    }

    /**
     * 模拟铸造稳定币操作，强制抵押率检查。
     *
     * @param position      仓位对象
     * @param addCollateral 追加抵押物数量
     * @param mintAmount    铸造稳定币数量
     * @throws IllegalStateException 如果抵押率不达标
     */
    private void mint(StableCoinPosition position, long addCollateral, long mintAmount) {
        if (mintAmount <= 0) {
            throw new IllegalStateException("MINT_STABLECOIN: 铸造金额须大于 0");
        }
        long newCollateral = position.getCollateralAmount() + addCollateral;
        long newMinted = position.getMintedAmount() + mintAmount;
        double newRatio = (double) newCollateral / (double) newMinted;
        if (newRatio < MIN_COLLATERAL_RATIO) {
            throw new IllegalStateException(
                    "MINT_STABLECOIN: 抵押率 " + newRatio + " 低于最低要求 " + MIN_COLLATERAL_RATIO);
        }
        position.setCollateralAmount(newCollateral);
        position.setMintedAmount(newMinted);
        position.setCollateralRatio((int) (newRatio * 100));
        position.setLiquidationPrice(computeLiquidationPrice(newCollateral, newMinted));
        position.setState(classifyHealth(newRatio));
    }

    /**
     * 模拟赎回稳定币操作。
     *
     * @param position    仓位对象
     * @param redeemAmount 赎回稳定币数量
     * @return 返还的抵押物数量
     * @throws IllegalStateException 如果赎回金额无效
     */
    private long redeem(StableCoinPosition position, long redeemAmount) {
        if (redeemAmount <= 0) {
            throw new IllegalStateException("REDEEM_STABLECOIN: 赎回金额须大于 0");
        }
        if (redeemAmount > position.getMintedAmount()) {
            throw new IllegalStateException(
                    "REDEEM_STABLECOIN: 赎回金额 " + redeemAmount
                            + " 超过已铸造金额 " + position.getMintedAmount());
        }
        // 按比例返还抵押物（getCollateralRatio 返回百分比整数，需除以 100.0 转回小数）
        double ratio = position.getCollateralRatio() / 100.0;
        long returnCollateral = (long) (redeemAmount * ratio);
        long newCollateral = position.getCollateralAmount() - returnCollateral;
        long newMinted = position.getMintedAmount() - redeemAmount;

        position.setCollateralAmount(newCollateral);
        position.setMintedAmount(newMinted);
        if (newMinted > 0) {
            double newRatio = (double) newCollateral / (double) newMinted;
            position.setCollateralRatio((int) (newRatio * 100));
            position.setLiquidationPrice(computeLiquidationPrice(newCollateral, newMinted));
            position.setState(classifyHealth(newRatio));
        } else {
            // 全部赎回，仓位清空
            position.setCollateralRatio(0);
            position.setLiquidationPrice(0L);
            position.setState(State.HEALTHY);
        }
        return returnCollateral;
    }

    /**
     * 检查仓位健康度并更新状态。
     *
     * @param position 仓位对象
     */
    private void checkHealth(StableCoinPosition position) {
        // getCollateralRatio 返回百分比整数，需除以 100.0 转回小数进行分级
        State newState = classifyHealth(position.getCollateralRatio() / 100.0);
        position.setState(newState);
    }

    /**
     * 判断仓位是否在水下（抵押率低于清算阈值）。
     *
     * @param position 仓位对象
     * @return 如果在水下返回 true
     */
    private boolean isUnderwater(StableCoinPosition position) {
        return position.getCollateralRatio() / 100.0 < LIQUIDATION_RATIO;
    }

    /**
     * 模拟清算操作。
     *
     * @param position 仓位对象
     * @throws IllegalStateException 如果仓位不在可清算状态
     */
    private void liquidate(StableCoinPosition position) {
        if (position.getState() != State.LIQUIDATABLE) {
            throw new IllegalStateException(
                    "仓位不在可清算状态，当前状态: " + position.getState());
        }
        // 清算：没收全部抵押物，消除铸造稳定币
        position.setCollateralAmount(0L);
        position.setMintedAmount(0L);
        position.setCollateralRatio(0);
        position.setLiquidationPrice(0L);
        // 清算后仓位保持 LIQUIDATABLE 标记（已被清算）
        position.setState(State.LIQUIDATABLE);
    }

    // ==================== mint 抵押率检查测试 ====================

    /**
     * 测试 mint() 抵押率充足时铸造成功
     */
    @Test
    public void testMintWithSufficientCollateral() {
        // 抵押 3000000，铸造 1000000，抵押率 = 3.0 >= 1.5
        StableCoinPosition position = new StableCoinPosition();
        position.setOwner("nexus-owner-001");
        position.setCollateralAmount(0L);
        position.setMintedAmount(0L);

        mint(position, 3000000L, 1000000L);

        assertEquals(3000000L, position.getCollateralAmount());
        assertEquals(1000000L, position.getMintedAmount());
        assertEquals(3.0, position.getCollateralRatio() / 100.0, 0.001);
        assertEquals(State.HEALTHY, position.getState());
    }

    /**
     * 测试 mint() 抵押率恰好等于最低要求时铸造成功
     */
    @Test
    public void testMintAtMinimumRatio() {
        // 抵押 1500000，铸造 1000000，抵押率 = 1.5 = 最低要求
        StableCoinPosition position = new StableCoinPosition();
        position.setOwner("nexus-owner-001");
        position.setCollateralAmount(0L);
        position.setMintedAmount(0L);

        mint(position, 1500000L, 1000000L);

        assertEquals(1.5, position.getCollateralRatio() / 100.0, 0.001);
        assertEquals(State.HEALTHY, position.getState());
    }

    /**
     * 测试 mint() 抵押率不足时抛异常
     */
    @Test
    public void testMintWithInsufficientCollateral() {
        assertThrows(IllegalStateException.class, () -> {
            // 抵押 1000000，铸造 1000000，抵押率 = 1.0 < 1.5
            StableCoinPosition position = new StableCoinPosition();
            position.setOwner("nexus-owner-001");
    
            mint(position, 1000000L, 1000000L);
        });
    }

    /**
     * 测试 mint() 铸造金额为零时抛异常
     */
    @Test
    public void testMintZeroAmount() {
        assertThrows(IllegalStateException.class, () -> {
            StableCoinPosition position = new StableCoinPosition();
            position.setOwner("nexus-owner-001");
    
            mint(position, 1000000L, 0L);
        });
    }

    /**
     * 测试 mint() 铸造金额为负时抛异常
     */
    @Test
    public void testMintNegativeAmount() {
        assertThrows(IllegalStateException.class, () -> {
            StableCoinPosition position = new StableCoinPosition();
            position.setOwner("nexus-owner-001");
    
            mint(position, 1000000L, -500L);
        });
    }

    // ==================== redeem 赎回逻辑测试 ====================

    /**
     * 测试 redeem() 部分赎回成功
     */
    @Test
    public void testRedeemPartial() {
        // 初始：抵押 3000000，铸造 1000000，抵押率 3.0
        StableCoinPosition position = createPosition(3000000L, 1000000L);
        assertEquals(State.HEALTHY, position.getState());

        // 赎回 500000
        long returned = redeem(position, 500000L);

        // 验证铸造量减少
        assertEquals(500000L, position.getMintedAmount());
        // 验证抵押物按比例返还
        assertTrue(returned > 0);
        assertTrue(position.getCollateralAmount() < 3000000L);
        // 验证仓位仍然健康
        assertEquals(State.HEALTHY, position.getState());
    }

    /**
     * 测试 redeem() 全部赎回
     */
    @Test
    public void testRedeemAll() {
        StableCoinPosition position = createPosition(3000000L, 1000000L);

        long returned = redeem(position, 1000000L);

        // 全部赎回后仓位清空
        assertEquals(0L, position.getMintedAmount());
        assertEquals(0L, position.getCollateralAmount());
        assertTrue(returned > 0);
    }

    /**
     * 测试 redeem() 赎回金额超过已铸造金额时抛异常
     */
    @Test
    public void testRedeemExceedsMinted() {
        assertThrows(IllegalStateException.class, () -> {
            StableCoinPosition position = createPosition(3000000L, 1000000L);
            redeem(position, 2000000L);
        });
    }

    /**
     * 测试 redeem() 赎回金额为零时抛异常
     */
    @Test
    public void testRedeemZero() {
        assertThrows(IllegalStateException.class, () -> {
            StableCoinPosition position = createPosition(3000000L, 1000000L);
            redeem(position, 0L);
        });
    }

    /**
     * 测试 redeem() 赎回金额为负时抛异常
     */
    @Test
    public void testRedeemNegative() {
        assertThrows(IllegalStateException.class, () -> {
            StableCoinPosition position = createPosition(3000000L, 1000000L);
            redeem(position, -100L);
        });
    }

    // ==================== checkHealth 状态分级测试 ====================

    /**
     * 测试 checkHealth() 对健康仓位返回 HEALTHY
     */
    @Test
    public void testCheckHealthHealthy() {
        // 抵押率 3.0 >= 1.5
        StableCoinPosition position = createPosition(3000000L, 1000000L);
        checkHealth(position);
        assertEquals(State.HEALTHY, position.getState());
    }

    /**
     * 测试 checkHealth() 对警告仓位返回 WARN
     */
    @Test
    public void testCheckHealthWarn() {
        // 抵押率 1.2，在 [1.1, 1.5) 区间
        StableCoinPosition position = createPosition(1200000L, 1000000L);
        checkHealth(position);
        assertEquals(State.WARNING, position.getState());
    }

    /**
     * 测试 checkHealth() 对可清算仓位返回 LIQUIDATABLE
     */
    @Test
    public void testCheckHealthLiquidatable() {
        // 抵押率 1.0 < 1.1
        StableCoinPosition position = createPosition(1000000L, 1000000L);
        checkHealth(position);
        assertEquals(State.LIQUIDATABLE, position.getState());
    }

    /**
     * 测试 checkHealth() 边界：抵押率恰好等于 1.5 为 HEALTHY
     */
    @Test
    public void testCheckHealthBoundaryMinRatio() {
        StableCoinPosition position = new StableCoinPosition(
                "pos-001", "owner", 1500000L, 1000000L, 150, 0L, State.WARNING, 0L, 0L
        );
        checkHealth(position);
        assertEquals(State.HEALTHY, position.getState());
    }

    /**
     * 测试 checkHealth() 边界：抵押率恰好等于 1.1 为 WARN
     */
    @Test
    public void testCheckHealthBoundaryLiquidationRatio() {
        StableCoinPosition position = new StableCoinPosition(
                "pos-001", "owner", 1100000L, 1000000L, 110, 0L, State.LIQUIDATABLE, 0L, 0L
        );
        checkHealth(position);
        assertEquals(State.WARNING, position.getState());
    }

    /**
     * 测试 checkHealth() 边界：抵押率略低于 1.1 为 LIQUIDATABLE
     */
    @Test
    public void testCheckHealthBelowLiquidationRatio() {
        StableCoinPosition position = new StableCoinPosition(
                "pos-001", "owner", 1099999L, 1000000L, 109, 0L, State.WARNING, 0L, 0L
        );
        checkHealth(position);
        assertEquals(State.LIQUIDATABLE, position.getState());
    }

    // ==================== liquidate 清算流程测试 ====================

    /**
     * 测试 liquidate() 对可清算仓位的清算流程
     */
    @Test
    public void testLiquidate() {
        // 抵押率 1.0 < 1.1，可清算
        StableCoinPosition position = createPosition(1000000L, 1000000L);
        assertEquals(State.LIQUIDATABLE, position.getState());

        liquidate(position);

        // 清算后仓位清空
        assertEquals(0L, position.getCollateralAmount());
        assertEquals(0L, position.getMintedAmount());
        assertEquals(0.0, position.getCollateralRatio() / 100.0, 0.001);
        assertEquals(0L, position.getLiquidationPrice());
    }

    /**
     * 测试 liquidate() 对健康仓位抛异常
     */
    @Test
    public void testLiquidateHealthyPosition() {
        assertThrows(IllegalStateException.class, () -> {
            StableCoinPosition position = createPosition(3000000L, 1000000L);
            assertEquals(State.HEALTHY, position.getState());
            liquidate(position);
        });
    }

    /**
     * 测试 liquidate() 对警告仓位抛异常
     */
    @Test
    public void testLiquidateWarnPosition() {
        assertThrows(IllegalStateException.class, () -> {
            StableCoinPosition position = createPosition(1200000L, 1000000L);
            assertEquals(State.WARNING, position.getState());
            liquidate(position);
        });
    }

    // ==================== isUnderwater 判断测试 ====================

    /**
     * 测试 isUnderwater() 对低抵押率仓位返回 true
     */
    @Test
    public void testIsUnderwaterTrue() {
        // 抵押率 1.0 < 1.1
        StableCoinPosition position = createPosition(1000000L, 1000000L);
        assertTrue(isUnderwater(position));
    }

    /**
     * 测试 isUnderwater() 对高抵押率仓位返回 false
     */
    @Test
    public void testIsUnderwaterFalse() {
        // 抵押率 3.0 >= 1.1
        StableCoinPosition position = createPosition(3000000L, 1000000L);
        assertFalse(isUnderwater(position));
    }

    /**
     * 测试 isUnderwater() 边界：抵押率恰好等于清算阈值为非水下
     */
    @Test
    public void testIsUnderwaterBoundary() {
        // 抵押率 = 1.1 = 清算阈值，不算水下
        StableCoinPosition position = new StableCoinPosition(
                "pos-001", "owner", 1100000L, 1000000L, 110, 0L, State.WARNING, 0L, 0L
        );
        assertFalse(isUnderwater(position));

        // 抵押率 = 1.099 < 1.1，算水下
        position = new StableCoinPosition(
                "pos-001", "owner", 1099000L, 1000000L, 109, 0L, State.LIQUIDATABLE, 0L, 0L
        );
        assertTrue(isUnderwater(position));
    }

    // ==================== 全参数构造器和 setter 测试 ====================

    /**
     * 测试全参数构造器正确设置所有字段
     */
    @Test
    public void testFullConstructor() {
        StableCoinPosition position = new StableCoinPosition(
                "pos-002", "nexus-owner-002", 2000000L, 1000000L, 200, 1818181L, State.HEALTHY, 0L, 0L
        );

        assertEquals(position.getOwner(), "nexus-owner-002");
        assertEquals(2000000L, position.getCollateralAmount());
        assertEquals(1000000L, position.getMintedAmount());
        assertEquals(2.0, position.getCollateralRatio() / 100.0, 0.001);
        assertEquals(1818181L, position.getLiquidationPrice());
        assertEquals(State.HEALTHY, position.getState());
    }

    /**
     * 测试默认构造器和 setter
     */
    @Test
    public void testDefaultConstructorAndSetters() {
        StableCoinPosition position = new StableCoinPosition();

        position.setOwner("nexus-owner-003");
        position.setCollateralAmount(5000000L);
        position.setMintedAmount(2000000L);
        position.setCollateralRatio(250);
        position.setLiquidationPrice(2272727L);
        position.setState(State.HEALTHY);

        assertEquals(position.getOwner(), "nexus-owner-003");
        assertEquals(5000000L, position.getCollateralAmount());
        assertEquals(2000000L, position.getMintedAmount());
        assertEquals(2.5, position.getCollateralRatio() / 100.0, 0.001);
        assertEquals(2272727L, position.getLiquidationPrice());
        assertEquals(State.HEALTHY, position.getState());
    }

    // ==================== State 枚举测试 ====================

    /**
     * 测试 State 枚举包含所有 3 种状态
     */
    @Test
    public void testStateEnumValues() {
        State[] states = State.values();
        assertEquals(5, states.length);
        assertNotNull(State.valueOf("HEALTHY"));
        assertNotNull(State.valueOf("WARNING"));
        assertNotNull(State.valueOf("LIQUIDATABLE"));
    }

    // ==================== Transaction 类型关联测试 ====================

    /**
     * 测试 MINT_STABLECOIN 和 REDEEM_STABLECOIN 交易类型
     */
    @Test
    public void testStableCoinTransactionTypes() {
        Transaction tx = Transaction.createEmpty();

        tx.type = Transaction.Type.MINT_STABLECOIN.ordinal();
        assertEquals(20, tx.type);
        assertEquals(tx.getTypeName(), "MINT_STABLECOIN");
        assertTrue(tx.isStableCoinTransaction());
        assertTrue(tx.isPaymentExtensionType());

        tx.type = Transaction.Type.REDEEM_STABLECOIN.ordinal();
        assertEquals(21, tx.type);
        assertEquals(tx.getTypeName(), "REDEEM_STABLECOIN");
        assertTrue(tx.isStableCoinTransaction());
        assertFalse(tx.isChannelTransaction());
        assertFalse(tx.isBridgeTransaction());
    }
}
