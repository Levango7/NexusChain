package org.nexus.consensus.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * PoS 惩罚服务。
 *
 * <p>对验证人的作恶行为（双签 / 离线 / 恶意行为）执行罚没质押。
 * 不同违规类型对应不同罚没比例，严重违规会将验证人状态置为 SLASHED。</p>
 *
 * @since 1.2
 */
@Component
public class SlashingService {

    private static final Logger logger = LoggerFactory.getLogger(SlashingService.class);

    /** 违规类型 */
    public enum Offense {
        /** 双签（同一高度签署两个不同区块），最严重 */
        DOUBLE_SIGN,
        /** 长时间离线 */
        OFFLINE,
        /** 其他恶意行为 */
        MALICIOUS
    }

    @Autowired
    private ValidatorRegistry validatorRegistry;

    @Autowired
    private StakingService stakingService;

    private final Map<Offense, BigDecimal> slashRates = new EnumMap<>(Offense.class);

    public SlashingService() {
        // 双签没收全部；恶意行为没收 50%；离线没收 1%
        slashRates.put(Offense.DOUBLE_SIGN, new BigDecimal("1.0"));
        slashRates.put(Offense.MALICIOUS, new BigDecimal("0.5"));
        slashRates.put(Offense.OFFLINE, new BigDecimal("0.01"));
    }

    /**
     * 对验证人执行罚没。
     *
     * @param validatorAddress 验证人地址
     * @param offense          违规类型
     * @return 实际罚没金额；验证人不存在或无质押返回 0
     */
    public BigDecimal slash(String validatorAddress, Offense offense) {
        if (validatorAddress == null || offense == null) {
            return BigDecimal.ZERO;
        }
        Validator validator = validatorRegistry.getValidator(validatorAddress);
        if (validator == null) {
            logger.warn("Cannot slash unknown validator: {}", validatorAddress);
            return BigDecimal.ZERO;
        }
        BigDecimal rate = slashRates.getOrDefault(offense, BigDecimal.ZERO);
        BigDecimal stake = stakingService.getStake(validatorAddress);
        BigDecimal slashAmount = stake.multiply(rate);
        if (slashAmount.signum() > 0) {
            try {
                stakingService.unstake(validatorAddress, slashAmount);
            } catch (IllegalArgumentException e) {
                logger.warn("Slash unstake failed for {}: {}", validatorAddress, e.getMessage());
                return BigDecimal.ZERO;
            }
        }
        // 严重违规直接置为 SLASHED，永久禁止参与共识
        if (offense == Offense.DOUBLE_SIGN || offense == Offense.MALICIOUS) {
            validator.setStatus(ValidatorStatus.SLASHED);
        }
        logger.info("Slashed {} from {} for offense {} (rate={})", slashAmount, validatorAddress, offense, rate);
        return slashAmount;
    }

    /**
     * 对验证人执行指定金额的罚没（用于 L2 欺诈证明等外部场景）。
     *
     * <p>按精确金额 unstake，并将验证人置为 SLASHED。
     * 不会破坏 {@link #slash(String, Offense)} 的现有语义。</p>
     *
     * @param validatorAddress 验证人地址
     * @param slashAmount      罚没金额
     * @param reason           罚没原因（如 "FRAUD_PROVEN"）
     * @return 实际罚没金额；参数非法或余额不足返回 0
     */
    public BigDecimal slash(String validatorAddress, BigDecimal slashAmount, String reason) {
        if (validatorAddress == null || slashAmount == null || slashAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        Validator validator = validatorRegistry.getValidator(validatorAddress);
        if (validator == null) {
            logger.warn("Cannot slash unknown validator: {} (reason={})", validatorAddress, reason);
            return BigDecimal.ZERO;
        }
        BigDecimal stake = stakingService.getStake(validatorAddress);
        BigDecimal actual = slashAmount.min(stake);
        if (actual.signum() <= 0) {
            logger.warn("Slash skipped for {}: no stake (reason={})", validatorAddress, reason);
            return BigDecimal.ZERO;
        }
        try {
            stakingService.unstake(validatorAddress, actual);
        } catch (IllegalArgumentException e) {
            logger.warn("Slash unstake failed for {}: {} (reason={})", validatorAddress, e.getMessage(), reason);
            return BigDecimal.ZERO;
        }
        validator.setStatus(ValidatorStatus.SLASHED);
        logger.info("Slashed {} from {} reason={} (actual={})", slashAmount, validatorAddress, reason, actual);
        return actual;
    }

    /**
     * 设置违规类型的罚没比例。
     *
     * @param offense 违规类型
     * @param rate    罚没比例（0~1）
     */
    public void setSlashRate(Offense offense, BigDecimal rate) {
        slashRates.put(offense, rate);
    }

    public BigDecimal getSlashRate(Offense offense) {
        return slashRates.getOrDefault(offense, BigDecimal.ZERO);
    }
}