package org.nexus.core.validate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.nexus.core.account.Transaction;

import java.util.Arrays;

/**
 * 稳定币交易验证规则。
 *
 * <p>验证以下交易类型的合法性：
 * <ul>
 *   <li>{@code MINT_STABLECOIN} - 铸造稳定币：抵押率须不低于配置的最低抵押率阈值，
 *       payload 须包含抵押物数量等参数</li>
 *   <li>{@code REDEEM_STABLECOIN} - 赎回稳定币：赎回金额须不超过已铸造金额，
 *       payload 须包含仓位标识</li>
 * </ul></p>
 *
 * <p>通过 {@code @Value} 注入以下配置参数：
 * <ul>
 *   <li>{@code nexus.stablecoin.collateral-ratio} - 最低抵押率（如 1.5 表示 150%）</li>
 *   <li>{@code nexus.stablecoin.liquidation-ratio} - 清算抵押率阈值</li>
 *   <li>{@code nexus.stablecoin.price-source} - 价格来源标识</li>
 * </ul></p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class StableCoinRule implements TransactionRule {

    /** 最低抵押率（如 1.5 表示 150%），通过配置注入。 */
    @Value("${nexus.stablecoin.collateral-ratio:1.5}")
    private double collateralRatio;

    /** 清算抵押率阈值（如 1.1 表示 110%），通过配置注入。 */
    @Value("${nexus.stablecoin.liquidation-ratio:1.1}")
    private double liquidationRatio;

    /** 稳定币价格来源标识，通过配置注入。 */
    @Value("${nexus.stablecoin.price-source:oracle}")
    private String priceSource;

    /**
     * 获取最低抵押率。
     * @return 最低抵押率
     */
    public double getCollateralRatio() {
        return collateralRatio;
    }

    /**
     * 获取清算抵押率阈值。
     * @return 清算抵押率阈值
     */
    public double getLiquidationRatio() {
        return liquidationRatio;
    }

    /**
     * 验证稳定币相关交易。
     *
     * @param transaction 待验证的交易
     * @return 验证结果，成功返回 {@link Result#SUCCESS}，失败返回包含错误信息的 Result
     */
    @Override
    public Result validateTransaction(Transaction transaction) {
        if (transaction.type == Transaction.Type.MINT_STABLECOIN.ordinal()) {
            return validateMint(transaction);
        }
        if (transaction.type == Transaction.Type.REDEEM_STABLECOIN.ordinal()) {
            return validateRedeem(transaction);
        }
        return Result.SUCCESS;
    }

    /**
     * 验证 MINT_STABLECOIN 交易。
     * <p>校验规则：
     * <ol>
     *   <li>铸造金额须大于 0</li>
     *   <li>发起方地址非空</li>
     *   <li>payload 非空，须包含抵押物数量等信息</li>
     *   <li>抵押率须不低于配置的最低抵押率（抵押物价值 / 铸造金额 >= collateralRatio）</li>
     * </ol></p>
     *
     * <p>payload 格式约定：前 8 字节为抵押物数量（big-endian long），
     * 后续字节为价格预言机数据。实际抵押率 = 抵押物数量 / 铸造金额。</p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateMint(Transaction tx) {
        if (tx.amount <= 0) {
            return Result.Error("MINT_STABLECOIN: mint amount must be greater than 0");
        }
        if (!isNonEmpty(tx.from)) {
            return Result.Error("MINT_STABLECOIN: from (public key) must not be empty");
        }
        if (tx.payload == null || tx.payload.length < 8) {
            return Result.Error("MINT_STABLECOIN: payload must contain collateral amount (at least 8 bytes)");
        }

        // 从 payload 前 8 字节解析抵押物数量
        long collateralAmount = 0;
        for (int i = 0; i < 8; i++) {
            collateralAmount = (collateralAmount << 8) | (tx.payload[i] & 0xFF);
        }

        // 计算抵押率 = 抵押物数量 / 铸造金额
        // 审计修复：改用 BigDecimal 交叉乘法比较。原 double 除法在
        // collateralAmount/mintAmount 超过 2^53 时丢失精度，可误放行不达标的
        // 铸造请求（consensus 校验路径，属资金安全参数）。
        java.math.BigDecimal collateral = java.math.BigDecimal.valueOf(collateralAmount);
        java.math.BigDecimal required = java.math.BigDecimal.valueOf(collateralRatio)
                .multiply(java.math.BigDecimal.valueOf(tx.amount));
        if (collateral.compareTo(required) < 0) {
            java.math.BigDecimal ratio = collateral.divide(
                    java.math.BigDecimal.valueOf(tx.amount), 6, java.math.RoundingMode.HALF_UP);
            return Result.Error("MINT_STABLECOIN: collateral ratio " + ratio
                    + " is below minimum " + collateralRatio);
        }

        return Result.SUCCESS;
    }

    /**
     * 验证 REDEEM_STABLECOIN 交易。
     * <p>校验规则：
     * <ol>
     *   <li>赎回金额须大于 0</li>
     *   <li>发起方地址非空</li>
     *   <li>payload 非空，须包含仓位标识和已铸造金额</li>
     *   <li>赎回金额须不超过已铸造金额（从 payload 解析）</li>
     * </ol></p>
     *
     * <p>payload 格式约定：前 8 字节为已铸造金额（big-endian long），
     * 后续字节为仓位标识。</p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateRedeem(Transaction tx) {
        if (tx.amount <= 0) {
            return Result.Error("REDEEM_STABLECOIN: redeem amount must be greater than 0");
        }
        if (!isNonEmpty(tx.from)) {
            return Result.Error("REDEEM_STABLECOIN: from (public key) must not be empty");
        }
        if (tx.payload == null || tx.payload.length < 8) {
            return Result.Error("REDEEM_STABLECOIN: payload must contain minted amount (at least 8 bytes)");
        }

        // 从 payload 前 8 字节解析已铸造金额
        long mintedAmount = 0;
        for (int i = 0; i < 8; i++) {
            mintedAmount = (mintedAmount << 8) | (tx.payload[i] & 0xFF);
        }

        // 验证赎回金额不超过已铸造金额
        if (tx.amount > mintedAmount) {
            return Result.Error("REDEEM_STABLECOIN: redeem amount " + tx.amount
                    + " exceeds minted amount " + mintedAmount);
        }

        return Result.SUCCESS;
    }

    /**
     * 检查字节数组是否非空（非 null 且不全为零）。
     *
     * @param bytes 待检查的字节数组
     * @return 如果数组非 null 且至少有一个非零字节则返回 true
     */
    private boolean isNonEmpty(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        byte[] zeros = new byte[bytes.length];
        return !Arrays.equals(bytes, zeros);
    }
}
