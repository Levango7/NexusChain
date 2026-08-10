package org.nexus.core.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 稳定币仓位模型类。
 *
 * <p>表示用户在 NEX 稳定币系统中的抵押仓位状态。用户通过抵押 NEX 代币
 * 铸造稳定币，仓位健康度由抵押率决定。当抵押率低于阈值时，仓位进入警告
 * 或可清算状态。</p>
 *
 * <p>仓位状态转换：
 * <ul>
 *   <li>{@link State#HEALTHY} - 抵押率充足，仓位健康</li>
 *   <li>{@link State#WARNING} - 抵押率接近阈值，需要关注</li>
 *   <li>{@link State#LIQUIDATABLE} - 抵押率低于清算阈值，可被清算</li>
 *   <li>{@link State#LIQUIDATED} - 仓位已被清算</li>
 *   <li>{@link State#CLOSED} - 仓位已关闭</li>
 * </ul>
 * 补充抵押后可从 WARNING 回退到 HEALTHY 状态。</p>
 *
 * <p>抵押率计算公式：{@code (collateralAmount * currentPrice * 100) / mintedAmount}，
 * 其中 currentPrice 为抵押物当前价格。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class StableCoinPosition {

    /**
     * 仓位健康状态枚举。
     */
    public enum State {
        /** 抵押率充足，仓位健康。 */
        HEALTHY,
        /** 抵押率接近阈值，需要关注。 */
        WARNING,
        /** 抵押率低于清算阈值，可被清算。 */
        LIQUIDATABLE,
        /** 仓位已被清算，抵押物已扣除惩罚。 */
        LIQUIDATED,
        /** 仓位已关闭，不再活跃。 */
        CLOSED
    }

    /** JSON 序列化/反序列化器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 仓位唯一标识符。 */
    private String positionId;

    /** 仓位所有者地址（公钥哈希十六进制字符串）。 */
    private String owner;

    /** 抵押的 NEX 代币数量（单位：最小单位）。 */
    private long collateralAmount;

    /** 已铸造的稳定币数量（单位：最小单位）。 */
    private long mintedAmount;

    /** 当前抵押率（百分比，如 150 表示 150%）。 */
    private int collateralRatio;

    /** 清算价格（抵押物价格跌至此值时触发清算）。 */
    private long liquidationPrice;

    /** 仓位当前状态。 */
    private State state;

    /** 仓位创建时的区块高度。 */
    private long createdAtBlock;

    /** 仓位最后更新时的区块高度。 */
    private long lastUpdateBlock;

    /**
     * 默认构造函数，供 JSON 反序列化使用。
     */
    public StableCoinPosition() {
    }

    /**
     * 创建新仓位构造函数。
     *
     * @param positionId     仓位唯一标识符
     * @param owner          仓位所有者地址
     * @param createdAtBlock 创建时区块高度
     */
    public StableCoinPosition(String positionId, String owner, long createdAtBlock) {
        this.positionId = positionId;
        this.owner = owner;
        this.createdAtBlock = createdAtBlock;
        this.lastUpdateBlock = createdAtBlock;
        this.state = State.HEALTHY;
    }

    /**
     * 全参数构造函数。
     *
     * @param positionId       仓位唯一标识符
     * @param owner            仓位所有者地址
     * @param collateralAmount 抵押代币数量
     * @param mintedAmount     已铸造稳定币数量
     * @param collateralRatio  当前抵押率
     * @param liquidationPrice 清算价格
     * @param state            仓位状态
     * @param createdAtBlock   创建时区块高度
     * @param lastUpdateBlock  最后更新时区块高度
     */
    public StableCoinPosition(String positionId, String owner, long collateralAmount,
                              long mintedAmount, int collateralRatio, long liquidationPrice,
                              State state, long createdAtBlock, long lastUpdateBlock) {
        this.positionId = positionId;
        this.owner = owner;
        this.collateralAmount = collateralAmount;
        this.mintedAmount = mintedAmount;
        this.collateralRatio = collateralRatio;
        this.liquidationPrice = liquidationPrice;
        this.state = state;
        this.createdAtBlock = createdAtBlock;
        this.lastUpdateBlock = lastUpdateBlock;
    }

    // ==================== 核心业务方法 ====================

    /**
     * 铸造稳定币。
     *
     * <p>向仓位追加抵押物并铸造对应数量的稳定币。铸造前验证抵押率是否达到
     * 最低要求（抵押率 = collateralAmount * 100 / mintedAmount，按价格 1 计算）。
     * 铸造成功后仓位状态为 {@link State#HEALTHY}，并计算清算价格。</p>
     *
     * <p>清算价格计算：当抵押率降至 minRatio 时的抵押物价格，
     * 即 {@code liquidationPrice = minRatio * mintedAmount / (collateralAmount * 100)}。</p>
     *
     * @param collateral 追加的抵押物数量（必须为正数）
     * @param mintAmount 铸造的稳定币数量（必须为正数）
     * @param minRatio   最低抵押率要求（百分比，如 150 表示 150%）
     * @throws IllegalStateException    如果仓位已关闭或已清算
     * @throws IllegalArgumentException 如果参数非法
     * @throws IllegalStateException    如果抵押率低于最低要求
     */
    public void mint(long collateral, long mintAmount, int minRatio) {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Cannot mint on a CLOSED position");
        }
        if (state == State.LIQUIDATED) {
            throw new IllegalStateException("Cannot mint on a LIQUIDATED position");
        }
        if (collateral <= 0) {
            throw new IllegalArgumentException("Collateral amount must be positive: " + collateral);
        }
        if (mintAmount <= 0) {
            throw new IllegalArgumentException("Mint amount must be positive: " + mintAmount);
        }
        if (minRatio <= 0) {
            throw new IllegalArgumentException("Minimum ratio must be positive: " + minRatio);
        }

        long newCollateral = collateralAmount + collateral;
        long newMinted = mintedAmount + mintAmount;

        // 按价格 1 计算抵押率
        int ratio = (int) (newCollateral * 100L / newMinted);
        if (ratio < minRatio) {
            throw new IllegalStateException(
                    "Collateral ratio " + ratio + "% is below minimum " + minRatio + "%");
        }

        this.collateralAmount = newCollateral;
        this.mintedAmount = newMinted;
        this.collateralRatio = ratio;

        // 清算价格 = minRatio * mintedAmount / (collateralAmount * 100)
        this.liquidationPrice = (long) ((double) minRatio * newMinted / ((double) newCollateral * 100.0));

        this.state = State.HEALTHY;
    }

    /**
     * 赎回稳定币。
     *
     * <p>销毁指定数量的稳定币并减少仓位债务。赎回后抵押率会提升
     * （因为 mintedAmount 减少而 collateralAmount 不变）。
     * 新抵押率 = 旧抵押率 * 旧 mintedAmount / 新 mintedAmount。</p>
     *
     * @param redeemAmount 赎回的稳定币数量（必须为正数，且不超过 mintedAmount）
     * @throws IllegalStateException    如果仓位已关闭或已清算
     * @throws IllegalArgumentException 如果赎回数量非法
     */
    public void redeem(long redeemAmount) {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Cannot redeem from a CLOSED position");
        }
        if (state == State.LIQUIDATED) {
            throw new IllegalStateException("Cannot redeem from a LIQUIDATED position");
        }
        if (redeemAmount <= 0) {
            throw new IllegalArgumentException("Redeem amount must be positive: " + redeemAmount);
        }
        if (redeemAmount > mintedAmount) {
            throw new IllegalArgumentException(
                    "Redeem amount " + redeemAmount + " exceeds minted amount " + mintedAmount);
        }

        long newMinted = mintedAmount - redeemAmount;
        if (newMinted > 0 && collateralRatio > 0) {
            // 新抵押率 = 旧抵押率 * 旧mintedAmount / 新mintedAmount
            collateralRatio = (int) ((long) collateralRatio * mintedAmount / newMinted);
        } else if (newMinted == 0) {
            // 债务全部清偿，抵押率无穷大
            collateralRatio = Integer.MAX_VALUE;
        }
        mintedAmount = newMinted;
    }

    /**
     * 追加抵押物。
     *
     * <p>增加仓位抵押物数量，抵押率会相应提升。
     * 新抵押率 = 旧抵押率 * 新 collateralAmount / 旧 collateralAmount。</p>
     *
     * @param amount 追加的抵押物数量（必须为正数）
     * @throws IllegalStateException    如果仓位已关闭或已清算
     * @throws IllegalArgumentException 如果抵押数量非法
     */
    public void addCollateral(long amount) {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Cannot add collateral to a CLOSED position");
        }
        if (state == State.LIQUIDATED) {
            throw new IllegalStateException("Cannot add collateral to a LIQUIDATED position");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Collateral amount must be positive: " + amount);
        }

        if (collateralAmount > 0 && collateralRatio > 0) {
            // 新抵押率 = 旧抵押率 * 新collateralAmount / 旧collateralAmount
            collateralRatio = (int) ((long) collateralRatio * (collateralAmount + amount) / collateralAmount);
        }
        collateralAmount += amount;
    }

    /**
     * 检查仓位健康度。
     *
     * <p>根据当前抵押物价格重新计算抵押率，并更新仓位状态：</p>
     * <ul>
     *   <li>抵押率 &gt;= minRatio &rarr; {@link State#HEALTHY}</li>
     *   <li>warningRatio &lt;= 抵押率 &lt; minRatio &rarr; {@link State#WARNING}</li>
     *   <li>抵押率 &lt; warningRatio &rarr; {@link State#LIQUIDATABLE}</li>
     * </ul>
     *
     * @param currentPrice  抵押物当前价格
     * @param minRatio      最低抵押率（百分比，低于此值为可清算）
     * @param warningRatio  警告抵押率（百分比，低于此值进入警告）
     */
    public void checkHealth(long currentPrice, int minRatio, int warningRatio) {
        if (state == State.CLOSED || state == State.LIQUIDATED) {
            return;
        }

        int ratio = calculateCollateralRatio(currentPrice);
        this.collateralRatio = ratio;

        if (ratio >= minRatio) {
            this.state = State.HEALTHY;
        } else if (ratio >= warningRatio) {
            this.state = State.WARNING;
        } else {
            this.state = State.LIQUIDATABLE;
        }
    }

    /**
     * 清算仓位。
     *
     * <p>对可清算的仓位执行清算，从抵押物中扣除指定百分比的惩罚。
     * 清算后仓位状态变为 {@link State#LIQUIDATED}。</p>
     *
     * @param penaltyPct 惩罚百分比（0-100，如 10 表示扣除 10% 抵押物）
     * @throws IllegalStateException    如果仓位不在 LIQUIDATABLE 状态
     * @throws IllegalArgumentException 如果惩罚百分比非法
     */
    public void liquidate(long penaltyPct) {
        if (state != State.LIQUIDATABLE) {
            throw new IllegalStateException(
                    "Can only liquidate from LIQUIDATABLE state, current: " + state);
        }
        if (penaltyPct < 0 || penaltyPct > 100) {
            throw new IllegalArgumentException(
                    "Penalty percentage must be between 0 and 100: " + penaltyPct);
        }

        // 从抵押物中扣除惩罚
        collateralAmount = collateralAmount * (100 - penaltyPct) / 100;
        state = State.LIQUIDATED;
    }

    /**
     * 关闭已清仓的仓位。
     *
     * <p>仓位在以下情况下可被关闭：</p>
     * <ul>
     *   <li>仓位已被清算（{@link State#LIQUIDATED}）</li>
     *   <li>仓位债务已全部偿还（mintedAmount == 0）</li>
     * </ul>
     *
     * @throws IllegalStateException 如果仓位不满足关闭条件
     */
    public void close() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Position is already CLOSED");
        }
        if (state != State.LIQUIDATED && mintedAmount > 0) {
            throw new IllegalStateException(
                    "Cannot close position: state=" + state + ", outstanding debt=" + mintedAmount);
        }
        state = State.CLOSED;
    }

    // ==================== 计算方法 ====================

    /**
     * 计算指定价格下的抵押率。
     *
     * <p>抵押率 = (collateralAmount * currentPrice * 100) / mintedAmount。
     * 如果 mintedAmount 为 0（无债务），返回 {@link Integer#MAX_VALUE}。</p>
     *
     * @param currentPrice 抵押物当前价格
     * @return 抵押率（百分比），无债务时返回 Integer.MAX_VALUE
     */
    public int calculateCollateralRatio(long currentPrice) {
        if (mintedAmount == 0) {
            return Integer.MAX_VALUE;
        }
        long ratio = collateralAmount * currentPrice * 100L / mintedAmount;
        if (ratio > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (ratio < 0) {
            return 0;
        }
        return (int) ratio;
    }

    /**
     * 获取指定价格下抵押物的价值。
     *
     * @param currentPrice 抵押物当前价格
     * @return 抵押物价值 = collateralAmount * currentPrice
     */
    public long getCollateralValue(long currentPrice) {
        return collateralAmount * currentPrice;
    }

    /**
     * 判断仓位是否资不抵债（抵押物价值 < 铸造金额）。
     *
     * @param currentPrice 抵押物当前价格
     * @return 如果抵押物价值小于铸造金额则返回 true
     */
    public boolean isUnderwater(long currentPrice) {
        return getCollateralValue(currentPrice) < mintedAmount;
    }

    // ==================== 序列化方法 ====================

    /**
     * 将仓位序列化为 JSON 字符串。
     *
     * <p>输出使用 UTF-8 编码的 JSON 格式，包含所有字段。</p>
     *
     * @return JSON 字符串
     * @throws UncheckedIOException 如果序列化失败
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("Failed to serialize StableCoinPosition", e));
        }
    }

    /**
     * 从 JSON 字符串反序列化仓位。
     *
     * <p>输入应为 UTF-8 编码的 JSON 字符串。</p>
     *
     * @param json JSON 字符串
     * @return 反序列化的仓位对象
     * @throws UncheckedIOException 如果反序列化失败
     */
    public static StableCoinPosition fromJson(String json) {
        try {
            return MAPPER.readValue(json.getBytes(StandardCharsets.UTF_8), StableCoinPosition.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize StableCoinPosition", e);
        }
    }

    // ==================== Getter / Setter ====================

    /**
     * 获取仓位唯一标识符。
     * @return 仓位 ID
     */
    public String getPositionId() {
        return positionId;
    }

    /**
     * 设置仓位唯一标识符。
     * @param positionId 仓位 ID
     */
    public void setPositionId(String positionId) {
        this.positionId = positionId;
    }

    /**
     * 获取仓位所有者地址。
     * @return 所有者地址
     */
    public String getOwner() {
        return owner;
    }

    /**
     * 设置仓位所有者地址。
     * @param owner 所有者地址
     */
    public void setOwner(String owner) {
        this.owner = owner;
    }

    /**
     * 获取抵押代币数量。
     * @return 抵押代币数量
     */
    public long getCollateralAmount() {
        return collateralAmount;
    }

    /**
     * 设置抵押代币数量。
     * @param collateralAmount 抵押代币数量
     */
    public void setCollateralAmount(long collateralAmount) {
        this.collateralAmount = collateralAmount;
    }

    /**
     * 获取已铸造稳定币数量。
     * @return 已铸造稳定币数量
     */
    public long getMintedAmount() {
        return mintedAmount;
    }

    /**
     * 设置已铸造稳定币数量。
     * @param mintedAmount 已铸造稳定币数量
     */
    public void setMintedAmount(long mintedAmount) {
        this.mintedAmount = mintedAmount;
    }

    /**
     * 获取当前抵押率。
     * @return 抵押率（百分比）
     */
    public int getCollateralRatio() {
        return collateralRatio;
    }

    /**
     * 设置当前抵押率。
     * @param collateralRatio 抵押率（百分比）
     */
    public void setCollateralRatio(int collateralRatio) {
        this.collateralRatio = collateralRatio;
    }

    /**
     * 获取清算价格。
     * @return 清算价格
     */
    public long getLiquidationPrice() {
        return liquidationPrice;
    }

    /**
     * 设置清算价格。
     * @param liquidationPrice 清算价格
     */
    public void setLiquidationPrice(long liquidationPrice) {
        this.liquidationPrice = liquidationPrice;
    }

    /**
     * 获取仓位当前状态。
     * @return 仓位状态
     */
    public State getState() {
        return state;
    }

    /**
     * 设置仓位当前状态。
     * @param state 仓位状态
     */
    public void setState(State state) {
        this.state = state;
    }

    /**
     * 获取仓位创建时的区块高度。
     * @return 创建时区块高度
     */
    public long getCreatedAtBlock() {
        return createdAtBlock;
    }

    /**
     * 设置仓位创建时的区块高度。
     * @param createdAtBlock 创建时区块高度
     */
    public void setCreatedAtBlock(long createdAtBlock) {
        this.createdAtBlock = createdAtBlock;
    }

    /**
     * 获取仓位最后更新时的区块高度。
     * @return 最后更新时区块高度
     */
    public long getLastUpdateBlock() {
        return lastUpdateBlock;
    }

    /**
     * 设置仓位最后更新时的区块高度。
     * @param lastUpdateBlock 最后更新时区块高度
     */
    public void setLastUpdateBlock(long lastUpdateBlock) {
        this.lastUpdateBlock = lastUpdateBlock;
    }

    @Override
    public String toString() {
        return "StableCoinPosition{" +
                "positionId='" + positionId + '\'' +
                ", owner='" + owner + '\'' +
                ", collateralAmount=" + collateralAmount +
                ", mintedAmount=" + mintedAmount +
                ", collateralRatio=" + collateralRatio +
                ", liquidationPrice=" + liquidationPrice +
                ", state=" + state +
                ", createdAtBlock=" + createdAtBlock +
                ", lastUpdateBlock=" + lastUpdateBlock +
                '}';
    }
}
