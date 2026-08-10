package org.nexus.bridge;

/**
 * 桥配置，定义跨链桥的运行参数。
 *
 * <p>桥配置由 nexus-consortium 治理层产生和更新，桥模块在启动时
 * 加载配置，并在运行时动态读取以执行安全检查。</p>
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>验证者列表 — 参与多签的验证者集合</li>
 *   <li>签名阈值 — 执行跨链操作所需的最低签名数</li>
 *   <li>时间锁周期 — 大额跨链的确认等待时间</li>
 *   <li>单笔上限 — 单次跨链允许的最大金额</li>
 *   <li>日限额 — 24 小时累计跨链流出上限</li>
 *   <li>大额阈值 — 超过此金额的跨链需经过时间锁确认</li>
 * </ul>
 *
 * @since 1.0.0
 */
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "nexus.bridge")
public class BridgeConfig {

    /** 桥验证者公钥地址列表，由 nexus-consortium 治理产生。 */
    private java.util.List<String> validatorPublicKeys;

    /** 执行跨链操作所需的最少验证者签名数（N-of-M 中的 N）。 */
    private int signatureThreshold;

    /** 时间锁确认周期（秒），大额跨链需在此周期后才能执行。 */
    private long timelockPeriodSeconds;

    /** 单笔跨链允许的最大金额（以 NEX 最小单位表示）。 */
    private long maxAmountPerTx;

    /** 24 小时累计跨链流出上限（以 NEX 最小单位表示）。 */
    private long dailyLimit;

    /** 大额阈值，超过此金额的跨链需经过时间锁确认（以 NEX 最小单位表示）。 */
    private long largeAmountThreshold;

    /** 源链 ID。 */
    private String sourceChainId;

    /** 目标链 ID。 */
    private String targetChainId;

    /**
     * 默认构造函数。
     */
    public BridgeConfig() {
    }

    /**
     * 获取验证者公钥地址列表。
     *
     * @return 验证者公钥列表
     */
    public java.util.List<String> getValidatorPublicKeys() {
        return validatorPublicKeys;
    }

    /**
     * 设置验证者公钥地址列表。
     *
     * @param validatorPublicKeys 验证者公钥列表
     */
    public void setValidatorPublicKeys(java.util.List<String> validatorPublicKeys) {
        this.validatorPublicKeys = validatorPublicKeys;
    }

    /**
     * 获取签名阈值。
     *
     * @return 最少签名数
     */
    public int getSignatureThreshold() {
        return signatureThreshold;
    }

    /**
     * 设置签名阈值。
     *
     * @param signatureThreshold 最少签名数
     */
    public void setSignatureThreshold(int signatureThreshold) {
        this.signatureThreshold = signatureThreshold;
    }

    /**
     * 获取时间锁确认周期。
     *
     * @return 时间锁周期（秒）
     */
    public long getTimelockPeriodSeconds() {
        return timelockPeriodSeconds;
    }

    /**
     * 设置时间锁确认周期。
     *
     * @param timelockPeriodSeconds 时间锁周期（秒）
     */
    public void setTimelockPeriodSeconds(long timelockPeriodSeconds) {
        this.timelockPeriodSeconds = timelockPeriodSeconds;
    }

    /**
     * 获取单笔跨链上限。
     *
     * @return 单笔最大金额
     */
    public long getMaxAmountPerTx() {
        return maxAmountPerTx;
    }

    /**
     * 设置单笔跨链上限。
     *
     * @param maxAmountPerTx 单笔最大金额
     */
    public void setMaxAmountPerTx(long maxAmountPerTx) {
        this.maxAmountPerTx = maxAmountPerTx;
    }

    /**
     * 获取 24 小时累计流出上限。
     *
     * @return 日限额
     */
    public long getDailyLimit() {
        return dailyLimit;
    }

    /**
     * 设置 24 小时累计流出上限。
     *
     * @param dailyLimit 日限额
     */
    public void setDailyLimit(long dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    /**
     * 获取大额阈值。
     *
     * @return 大额阈值
     */
    public long getLargeAmountThreshold() {
        return largeAmountThreshold;
    }

    /**
     * 设置大额阈值。
     *
     * @param largeAmountThreshold 大额阈值
     */
    public void setLargeAmountThreshold(long largeAmountThreshold) {
        this.largeAmountThreshold = largeAmountThreshold;
    }

    /**
     * 获取源链 ID。
     *
     * @return 源链 ID
     */
    public String getSourceChainId() {
        return sourceChainId;
    }

    /**
     * 设置源链 ID。
     *
     * @param sourceChainId 源链 ID
     */
    public void setSourceChainId(String sourceChainId) {
        this.sourceChainId = sourceChainId;
    }

    /**
     * 获取目标链 ID。
     *
     * @return 目标链 ID
     */
    public String getTargetChainId() {
        return targetChainId;
    }

    /**
     * 设置目标链 ID。
     *
     * @param targetChainId 目标链 ID
     */
    public void setTargetChainId(String targetChainId) {
        this.targetChainId = targetChainId;
    }

    /**
     * 判断给定金额是否属于大额跨链，需要时间锁确认。
     *
     * @param amount 跨链金额
     * @return 需要时间锁返回 {@code true}，否则返回 {@code false}
     */
    public boolean isLargeAmount(long amount) {
        return amount >= largeAmountThreshold;
    }

    /**
     * 判断给定金额是否超过单笔上限。
     *
     * @param amount 跨链金额
     * @return 超过上限返回 {@code true}，否则返回 {@code false}
     */
    public boolean exceedsMaxAmount(long amount) {
        return amount > maxAmountPerTx;
    }

    /**
     * 判断给定金额是否超过日限额。
     *
     * @param amount          本次跨链金额
     * @param dailyUsedToday  今日已使用的额度
     * @return 超过日限额返回 {@code true}，否则返回 {@code false}
     */
    public boolean exceedsDailyLimit(long amount, long dailyUsedToday) {
        return dailyUsedToday + amount > dailyLimit;
    }
}
