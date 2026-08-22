package org.nexus.gateway.orchestration.connectors;

import org.nexus.oracle.price.PriceEntry;
import org.nexus.oracle.price.PriceOracle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 链上币价格适配器：桥接 nexus-oracle 的 {@link PriceOracle}。
 *
 * <p>职责：将法币金额按预言机报价换算为链上币金额，供 {@link ChainConnector}
 * 在创建支付时把商户法币计价转换为链上币计价。
 *
 * <p>换算公式：{@code chainAmount = fiatAmount / oraclePrice}
 * （oraclePrice 为 1 单位链上币的法币计价）。
 *
 * <p>{@link PriceOracle} 为可选依赖：当 nexus-oracle 模块未装配或无可用 PriceFeed 时，
 * 本 Bean 仍可创建但 {@link #convertToChainAmount} 返回 {@code null}，调用方应回退到
 * 原始金额逻辑（不破坏现有链路）。
 */
@Component
public class OraclePriceAdapter {

    private static final Logger log = LoggerFactory.getLogger(OraclePriceAdapter.class);

    /** 链上币金额精度（小数位） */
    private static final int CHAIN_AMOUNT_SCALE = 8;

    private final PriceOracle priceOracle;

    /**
     * 可选构造注入：当容器中存在 {@link PriceOracle} Bean 时注入；
     * 否则注入 {@code null}，本适配器退化为 no-op。
     *
     * @param priceOracle 聚合价格预言机（可选）
     */
    @Autowired(required = false)
    public OraclePriceAdapter(PriceOracle priceOracle) {
        this.priceOracle = priceOracle;
    }

    /**
     * 将法币金额换算为链上币金额。
     *
     * @param fiatAmount 法币金额（与 oracle 计价货币一致，通常为 USD）
     * @param chainAsset 链上币符号（如 "NEX" / "ETH"）
     * @return 链上币金额；当 oracle 不可用、价格无效或入参非法时返回 {@code null}
     */
    public BigDecimal convertToChainAmount(BigDecimal fiatAmount, String chainAsset) {
        if (priceOracle == null) {
            log.debug("PriceOracle not available, skip conversion: asset={}", chainAsset);
            return null;
        }
        if (fiatAmount == null || fiatAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (chainAsset == null || chainAsset.isBlank()) {
            return null;
        }
        try {
            PriceEntry entry = priceOracle.getPrice(chainAsset);
            if (entry == null || entry.getPrice() == null
                    || entry.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                log.debug("No valid oracle price for asset={}, skip conversion", chainAsset);
                return null;
            }
            BigDecimal chainAmount = fiatAmount.divide(entry.getPrice(), CHAIN_AMOUNT_SCALE, RoundingMode.HALF_UP);
            log.debug("Fiat->chain conversion: fiat={}, asset={}, price={}, chainAmount={}",
                    fiatAmount, chainAsset, entry.getPrice(), chainAmount);
            return chainAmount;
        } catch (RuntimeException e) {
            log.warn("Oracle price conversion failed: asset={}, error={}", chainAsset, e.getMessage());
            return null;
        }
    }

    /**
     * 预言机是否就绪可换算。
     *
     * @return true 表示 PriceOracle 已注入
     */
    public boolean isAvailable() {
        return priceOracle != null;
    }
}