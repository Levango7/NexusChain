package org.nexus.consensus.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * PoS 出块提议者选择器。
 *
 * <p>提供两种提案者选取策略：
 * <ul>
 *   <li>按质押比例加权随机选取（{@link #selectProposer(long)}）</li>
 *   <li>按高度确定性轮询选取（{@link #selectRoundRobinProposer(long)}）</li>
 * </ul>
 *
 * @since 1.2
 */
@Component
public class PosProposer {

    private static final Logger logger = LoggerFactory.getLogger(PosProposer.class);

    @Autowired
    private ValidatorRegistry validatorRegistry;

    private final SecureRandom random = new SecureRandom();

    /**
     * 按质押比例加权随机选择提案者。
     *
     * <p>质押越多被选中概率越高，结合 SecureRandom 引入随机性，
     * 避免确定性预测。</p>
     *
     * @param height 区块高度
     * @return 被选中的验证人；无活跃验证人返回 null
     */
    public Validator selectProposer(long height) {
        List<Validator> active = validatorRegistry.getActiveValidators();
        if (active.isEmpty()) {
            logger.warn("No active validators to propose at height {}", height);
            return null;
        }
        BigDecimal totalStake = BigDecimal.ZERO;
        for (Validator v : active) {
            totalStake = totalStake.add(v.getStakeAmount());
        }
        if (totalStake.signum() == 0) {
            logger.warn("Total stake is zero at height {}", height);
            return null;
        }
        BigDecimal threshold = BigDecimal.valueOf(random.nextDouble()).multiply(totalStake);
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Validator v : active) {
            cumulative = cumulative.add(v.getStakeAmount());
            if (cumulative.compareTo(threshold) >= 0) {
                logger.info("Selected proposer {} at height {} (weighted random)", v.getAddress(), height);
                return v;
            }
        }
        return active.get(active.size() - 1);
    }

    /**
     * 按高度确定性轮询选择提案者。
     *
     * <p>基于 {@code height % size} 在活跃验证人列表中选取，
     * 保证可验证性与公平性。</p>
     *
     * @param height 区块高度
     * @return 被选中的验证人；无活跃验证人返回 null
     */
    public Validator selectRoundRobinProposer(long height) {
        List<Validator> active = validatorRegistry.getActiveValidators();
        if (active.isEmpty()) {
            logger.warn("No active validators to propose at height {}", height);
            return null;
        }
        // PLAN-007 单 proposer 协调：按地址排序保证全网确定性顺序。
        // getActiveValidators() 遍历 HashMap（validators.values()）顺序不保证，
        // 两节点注册顺序不同 → 列表顺序不同 → 同一高度选出不同 proposer → 竞争出块。
        List<Validator> ordered = new ArrayList<>(active);
        ordered.sort(java.util.Comparator.comparing(Validator::getAddress));
        int index = (int) (Math.abs(height) % ordered.size());
        Validator selected = ordered.get(index);
        logger.info("Selected proposer {} at height {} (round robin index={}, ordered set size={})",
                selected.getAddress(), height, index, ordered.size());
        return selected;
    }
}