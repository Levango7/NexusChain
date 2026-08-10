package org.nexus.bridge.liquidity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流动性管理默认实现。
 *
 * <p>进程内内存实现，维护每条链的流动性池（储备量 / 利用率 / 滑点参数）：</p>
 * <ul>
 *   <li>{@link #getLiquidity}：查询指定链的池，不存在时惰性创建空池</li>
 *   <li>{@link #addLiquidity}：注入储备并刷新利用率</li>
 *   <li>{@link #removeLiquidity}：校验储备充足后抽取并刷新利用率</li>
 *   <li>{@link #rebalance}：跨链再平衡——从利用率低的池向利用率高的池调拨，
 *       使各池利用率趋近目标值</li>
 * </ul>
 *
 * <p>利用率模型：以初始储备为基准，当前储备占基准的比例视为利用率补集
 * （储备越少表示被跨链占用越多，利用率越高）。</p>
 *
 * <p>生产环境需替换为链上流动性合约查询；当前实现保留接口契约。</p>
 *
 * @since 1.2
 */
@Service
public class DefaultLiquidityManager implements LiquidityManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultLiquidityManager.class);

    /** 默认资产标识 */
    private static final String DEFAULT_ASSET = "NEX";

    /** 目标利用率（再平衡趋近值） */
    private static final double TARGET_UTILIZATION = 0.5d;

    /** 单次再平衡调拨比例上限（防止一次性抽干） */
    private static final double MAX_REBALANCE_RATIO = 0.25d;

    /** 池表：chainId → LiquidityPool */
    private final Map<String, LiquidityPool> pools = new ConcurrentHashMap<>();

    /** 基准储备表：chainId → 初始注入总额（利用率计算基准） */
    private final Map<String, BigDecimal> baselines = new ConcurrentHashMap<>();

    @Override
    public LiquidityPool getLiquidity(String chainId) {
        if (chainId == null || chainId.isEmpty()) {
            return null;
        }
        return pools.computeIfAbsent(chainId, id ->
                new LiquidityPool(id, DEFAULT_ASSET, BigDecimal.ZERO, 0.0, 0.001d));
    }

    @Override
    public void addLiquidity(String chainId, BigDecimal amount) {
        if (chainId == null || chainId.isEmpty()) {
            throw new IllegalArgumentException("chainId is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        LiquidityPool pool = getLiquidity(chainId);
        synchronized (pool) {
            BigDecimal current = pool.getReserve() == null ? BigDecimal.ZERO : pool.getReserve();
            pool.setReserve(current.add(amount));
            baselines.merge(chainId, amount, BigDecimal::add);
            refreshUtilization(chainId, pool);
        }
        logger.info("Liquidity added: chain={}, amount={}, reserve={}",
                chainId, amount, pool.getReserve());
    }

    @Override
    public void removeLiquidity(String chainId, BigDecimal amount) {
        if (chainId == null || chainId.isEmpty()) {
            throw new IllegalArgumentException("chainId is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        LiquidityPool pool = getLiquidity(chainId);
        synchronized (pool) {
            BigDecimal current = pool.getReserve() == null ? BigDecimal.ZERO : pool.getReserve();
            if (current.compareTo(amount) < 0) {
                throw new IllegalStateException(
                        "insufficient reserve: chain=" + chainId
                                + ", reserve=" + current + ", requested=" + amount);
            }
            pool.setReserve(current.subtract(amount));
            refreshUtilization(chainId, pool);
        }
        logger.info("Liquidity removed: chain={}, amount={}, reserve={}",
                chainId, amount, pool.getReserve());
    }

    @Override
    public void rebalance() {
        List<LiquidityPool> snapshot = new ArrayList<>(pools.values());
        if (snapshot.size() < 2) {
            logger.debug("Rebalance skipped: fewer than 2 pools");
            return;
        }

        // Find the most over-utilized (donor) and most under-utilized (receiver) pool
        LiquidityPool donor = null;
        LiquidityPool receiver = null;
        for (LiquidityPool pool : snapshot) {
            if (donor == null || pool.getUtilization() > donor.getUtilization()) {
                donor = pool;
            }
            if (receiver == null || pool.getUtilization() < receiver.getUtilization()) {
                receiver = pool;
            }
        }
        if (donor == null || receiver == null || donor.getChainId().equals(receiver.getChainId())) {
            return;
        }

        // Only rebalance if utilization gap is significant (> 10%)
        double gap = donor.getUtilization() - receiver.getUtilization();
        if (gap <= 0.10d) {
            logger.debug("Rebalance skipped: utilization gap too small: {}", gap);
            return;
        }

        // Transfer up to MAX_REBALANCE_RATIO of donor reserve toward target utilization
        BigDecimal donorReserve = donor.getReserve() == null ? BigDecimal.ZERO : donor.getReserve();
        BigDecimal transfer = donorReserve.multiply(BigDecimal.valueOf(MAX_REBALANCE_RATIO))
                .setScale(0, RoundingMode.DOWN);
        if (transfer.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // Donor gives up liquidity (reserve decreases → utilization rises toward target on receiver side)
        removeLiquidity(donor.getChainId(), transfer);
        addLiquidity(receiver.getChainId(), transfer);
        logger.info("Rebalance executed: {} -> {}, amount={}, gap={}",
                donor.getChainId(), receiver.getChainId(), transfer, gap);
    }

    /**
     * 刷新池利用率：储备占基准的比例越低，利用率越高（被跨链占用越多）。
     */
    private void refreshUtilization(String chainId, LiquidityPool pool) {
        BigDecimal baseline = baselines.getOrDefault(chainId, BigDecimal.ZERO);
        if (baseline.compareTo(BigDecimal.ZERO) <= 0) {
            pool.setUtilization(0.0d);
            return;
        }
        BigDecimal reserve = pool.getReserve() == null ? BigDecimal.ZERO : pool.getReserve();
        // utilization = 1 - reserve/baseline, clamped to [0,1]
        double ratio = reserve.divide(baseline, 6, RoundingMode.HALF_UP).doubleValue();
        double utilization = Math.max(0.0d, Math.min(1.0d, 1.0d - ratio));
        pool.setUtilization(utilization);
    }
}
