package org.nexus.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可治理参数注册表。
 *
 * <p>集中登记链上所有可治理参数的元信息（类型、取值范围、默认值、生效策略、敏感度）
 * 与当前值，提供统一的校验、读取与写入入口。{@link GovernanceExecutor} 在执行提案变更时
 * 通过本注册表进行二次校验并落盘新值。</p>
 *
 * <p>当前实现为进程内内存态；后续可扩展为持久化仓储。
 * 元信息在 {@link #initDefaults()} 中一次性装入，运行期不可变；
 * 仅 {@link GovernableParameter#getCurrentValue()} 随治理执行更新。</p>
 *
 * <h3>参数清单（共 12 个）</h3>
 * <table>
 *   <caption>表：可治理参数清单</caption>
 *   <tr><th>name</th><th>type</th><th>min</th><th>max</th><th>default</th><th>policy</th><th>sensitivity</th></tr>
 *   <tr><td>pos.minStakeThreshold</td><td>DECIMAL</td><td>1</td><td>1e12</td><td>1000</td><td>NEXT_EPOCH</td><td>HIGH</td></tr>
 *   <tr><td>pos.rewardRate</td><td>DECIMAL</td><td>0</td><td>1</td><td>0.05</td><td>NEXT_EPOCH</td><td>HIGH</td></tr>
 *   <tr><td>pos.slashRate</td><td>DECIMAL</td><td>0</td><td>1</td><td>0.1</td><td>NEXT_EPOCH</td><td>HIGH</td></tr>
 *   <tr><td>gov.votingPeriod</td><td>DURATION</td><td>1h</td><td>30d</td><td>3d</td><td>NEXT_PROPOSAL</td><td>MEDIUM</td></tr>
 *   <tr><td>gov.quorum</td><td>DECIMAL</td><td>1</td><td>1e12</td><td>100</td><td>NEXT_PROPOSAL</td><td>MEDIUM</td></tr>
 *   <tr><td>gov.timelockDelay</td><td>DURATION</td><td>1h</td><td>30d</td><td>2d</td><td>NEXT_PROPOSAL</td><td>HIGH</td></tr>
 *   <tr><td>gov.proposalDeposit</td><td>DECIMAL</td><td>0</td><td>1e12</td><td>100</td><td>NEXT_PROPOSAL</td><td>MEDIUM</td></tr>
 *   <tr><td>l2.challengeWindow</td><td>DURATION</td><td>1h</td><td>7d</td><td>1d</td><td>NEXT_BATCH</td><td>MEDIUM</td></tr>
 *   <tr><td>l2.maxBatchSize</td><td>INT</td><td>1</td><td>100000</td><td>1000</td><td>NEXT_BATCH</td><td>LOW</td></tr>
 *   <tr><td>l2.sequencerStake</td><td>DECIMAL</td><td>1</td><td>1e12</td><td>10000</td><td>NEXT_EPOCH</td><td>HIGH</td></tr>
 *   <tr><td>tx.maxBlockGas</td><td>INT</td><td>1000000</td><td>1e10</td><td>30000000</td><td>NEXT_BLOCK</td><td>MEDIUM</td></tr>
 *   <tr><td>tx.baseFee</td><td>DECIMAL</td><td>0</td><td>1e9</td><td>1</td><td>NEXT_BLOCK</td><td>MEDIUM</td></tr>
 * </table>
 *
 * <p>DURATION 类型以毫秒数存储为 BigDecimal。</p>
 *
 * @since 1.3
 */
@Component
public class GovernableParameterRegistry {

    private static final Logger logger = LoggerFactory.getLogger(GovernableParameterRegistry.class);

    /** 一小时毫秒数 */
    private static final BigDecimal ONE_HOUR_MS = new BigDecimal("3600000");
    /** 一天毫秒数 */
    private static final BigDecimal ONE_DAY_MS = new BigDecimal("86400000");

    /** 参数名 -> 参数实体 */
    private final Map<String, GovernableParameter> parameters = new LinkedHashMap<>();

    /** 多版本快照历史（按版本号递增顺序） */
    private final ConcurrentLinkedDeque<ConfigSnapshot> snapshotHistory = new ConcurrentLinkedDeque<>();

    /** 下一个快照版本号 */
    private final AtomicInteger nextSnapshotVersion = new AtomicInteger(0);

    /**
     * 构造并装入默认参数清单。
     */
    public GovernableParameterRegistry() {
        initDefaults();
    }

    /**
     * 装入 12 个可治理参数的默认元信息。
     */
    private void initDefaults() {
        register("pos.minStakeThreshold", ParameterType.DECIMAL,
                BigDecimal.ONE, new BigDecimal("1000000000000"), new BigDecimal("1000"),
                EffectivePolicy.NEXT_EPOCH, ParameterSensitivity.HIGH);
        register("pos.rewardRate", ParameterType.DECIMAL,
                BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("0.05"),
                EffectivePolicy.NEXT_EPOCH, ParameterSensitivity.HIGH);
        register("pos.slashRate", ParameterType.DECIMAL,
                BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("0.1"),
                EffectivePolicy.NEXT_EPOCH, ParameterSensitivity.HIGH);
        register("gov.votingPeriod", ParameterType.DURATION,
                ONE_HOUR_MS, ONE_DAY_MS.multiply(new BigDecimal("30")), ONE_DAY_MS.multiply(new BigDecimal("3")),
                EffectivePolicy.NEXT_PROPOSAL, ParameterSensitivity.MEDIUM);
        register("gov.quorum", ParameterType.DECIMAL,
                BigDecimal.ONE, new BigDecimal("1000000000000"), new BigDecimal("100"),
                EffectivePolicy.NEXT_PROPOSAL, ParameterSensitivity.MEDIUM);
        register("gov.timelockDelay", ParameterType.DURATION,
                ONE_HOUR_MS, ONE_DAY_MS.multiply(new BigDecimal("30")), ONE_DAY_MS.multiply(new BigDecimal("2")),
                EffectivePolicy.NEXT_PROPOSAL, ParameterSensitivity.HIGH);
        register("gov.proposalDeposit", ParameterType.DECIMAL,
                BigDecimal.ZERO, new BigDecimal("1000000000000"), new BigDecimal("100"),
                EffectivePolicy.NEXT_PROPOSAL, ParameterSensitivity.MEDIUM);
        register("l2.challengeWindow", ParameterType.DURATION,
                ONE_HOUR_MS, ONE_DAY_MS.multiply(new BigDecimal("7")), ONE_DAY_MS,
                EffectivePolicy.NEXT_BATCH, ParameterSensitivity.MEDIUM);
        register("l2.maxBatchSize", ParameterType.INT,
                BigDecimal.ONE, new BigDecimal("100000"), new BigDecimal("1000"),
                EffectivePolicy.NEXT_BATCH, ParameterSensitivity.LOW);
        register("l2.sequencerStake", ParameterType.DECIMAL,
                BigDecimal.ONE, new BigDecimal("1000000000000"), new BigDecimal("10000"),
                EffectivePolicy.NEXT_EPOCH, ParameterSensitivity.HIGH);
        register("tx.maxBlockGas", ParameterType.INT,
                new BigDecimal("1000000"), new BigDecimal("10000000000"), new BigDecimal("30000000"),
                EffectivePolicy.NEXT_BLOCK, ParameterSensitivity.MEDIUM);
        register("tx.baseFee", ParameterType.DECIMAL,
                BigDecimal.ZERO, new BigDecimal("1000000000"), BigDecimal.ONE,
                EffectivePolicy.NEXT_BLOCK, ParameterSensitivity.MEDIUM);

        logger.info("GovernableParameterRegistry initialized with {} parameters", parameters.size());
    }

    private void register(String name, ParameterType type,
                          BigDecimal min, BigDecimal max, BigDecimal def,
                          EffectivePolicy policy, ParameterSensitivity sensitivity) {
        parameters.put(name, new GovernableParameter(name, type, min, max, def, policy, sensitivity));
    }

    /**
     * 校验给定新值对指定参数是否合法（类型校验 + 范围校验）。
     *
     * @param parameterName 参数名
     * @param newValue      新值字符串
     * @return 合法返回 true；参数不存在、类型不匹配或越界返回 false
     */
    public boolean validate(String parameterName, String newValue) {
        GovernableParameter param = parameters.get(parameterName);
        if (param == null) {
            logger.warn("Validate failed: unknown parameter {}", parameterName);
            return false;
        }
        if (newValue == null) {
            return false;
        }
        BigDecimal numeric;
        try {
            numeric = parseValue(param.getType(), newValue);
        } catch (IllegalArgumentException e) {
            logger.warn("Validate failed: parameter {} type {} parse error for '{}': {}",
                    parameterName, param.getType(), newValue, e.getMessage());
            return false;
        }
        if (numeric.compareTo(param.getMinValue()) < 0 || numeric.compareTo(param.getMaxValue()) > 0) {
            logger.warn("Validate failed: parameter {} value {} out of range [{}, {}]",
                    parameterName, numeric, param.getMinValue(), param.getMaxValue());
            return false;
        }
        return true;
    }

    /**
     * 根据参数类型解析字符串为数值。
     *
     * <p>BOOL 类型解析为 0/1（0=false, 1=true）以便统一范围比较；
     * 其余类型解析为 {@link BigDecimal}。DURATION 视为毫秒数。</p>
     *
     * @param type 参数类型
     * @param raw  原始字符串
     * @return 解析后的数值
     * @throws IllegalArgumentException 类型不匹配
     */
    private BigDecimal parseValue(ParameterType type, String raw) {
        switch (type) {
            case DECIMAL:
            case DURATION:
                return new BigDecimal(raw);
            case INT:
                BigDecimal v = new BigDecimal(raw);
                if (v.scale() > 0) {
                    throw new IllegalArgumentException("INT parameter must be integer, got " + raw);
                }
                return v;
            case BOOL:
                if ("true".equalsIgnoreCase(raw)) {
                    return BigDecimal.ONE;
                }
                if ("false".equalsIgnoreCase(raw)) {
                    return BigDecimal.ZERO;
                }
                throw new IllegalArgumentException("BOOL parameter must be 'true'/'false', got " + raw);
            default:
                throw new IllegalArgumentException("Unsupported parameter type: " + type);
        }
    }

    /**
     * 查询参数实体。
     *
     * @param name 参数名
     * @return 参数实体；不存在返回 null
     */
    public GovernableParameter getParameter(String name) {
        return parameters.get(name);
    }

    /**
     * 设置参数当前值（先校验后写入）。
     *
     * @param name     参数名
     * @param newValue 新值字符串
     * @return 设置成功返回 true；参数不存在或校验失败返回 false
     */
    public boolean setParameter(String name, String newValue) {
        if (!validate(name, newValue)) {
            return false;
        }
        GovernableParameter param = parameters.get(name);
        param.setCurrentValue(parseValue(param.getType(), newValue));
        logger.info("Parameter {} set to {}", name, param.getCurrentValue());
        return true;
    }

    /**
     * 返回所有参数名的不可变视图。
     *
     * @return 参数名集合
     */
    public Set<String> getParameterNames() {
        return Collections.unmodifiableSet(parameters.keySet());
    }

    /**
     * 返回所有参数实体的不可变视图。
     *
     * @return 参数实体集合
     */
    public java.util.Collection<GovernableParameter> getAllParameters() {
        return Collections.unmodifiableCollection(parameters.values());
    }

    /**
     * 捕获当前所有参数值的快照（用于配置事务回滚）。
     *
     * @return 参数名 -> 当前值 的不可变快照
     */
    public Map<String, BigDecimal> snapshot() {
        Map<String, BigDecimal> snap = new LinkedHashMap<>();
        for (GovernableParameter p : parameters.values()) {
            snap.put(p.getName(), p.getCurrentValue());
        }
        return Collections.unmodifiableMap(snap);
    }

    /**
     * 将指定参数恢复为快照中的值（回滚用）。
     *
     * @param snapshot 快照
     */
    public void restore(Map<String, BigDecimal> snapshot) {
        if (snapshot == null) {
            return;
        }
        for (Map.Entry<String, BigDecimal> e : snapshot.entrySet()) {
            GovernableParameter p = parameters.get(e.getKey());
            if (p != null) {
                p.setCurrentValue(e.getValue());
            }
        }
        logger.info("Parameter registry restored from snapshot ({} entries)", snapshot.size());
    }

    /**
     * 创建带版本号的配置快照并存入历史，供后续指定版本回滚。
     *
     * <p>每次治理提案执行前可调用以留存执行前配置，便于执行失败或后续回滚时恢复。</p>
     *
     * @param tag 快照语义标签（可为 null）
     * @return 快照版本号（从 1 递增）
     */
    public int createVersionedSnapshot(String tag) {
        int version = nextSnapshotVersion.incrementAndGet();
        Map<String, BigDecimal> snap = snapshot();
        ConfigSnapshot entry = new ConfigSnapshot(version, Instant.now(), tag, snap);
        snapshotHistory.addLast(entry);
        logger.info("Versioned snapshot created: version={} tag={} size={}", version, tag, snap.size());
        return version;
    }

    /**
     * 将参数注册表恢复到指定历史版本。
     *
     * <p>仅恢复参数当前值，不删除快照历史。版本不存在返回 false。</p>
     *
     * @param version 快照版本号
     * @return 恢复成功返回 true；版本不存在返回 false
     */
    public boolean restoreVersionedSnapshot(int version) {
        ConfigSnapshot entry = getSnapshot(version);
        if (entry == null) {
            logger.warn("Restore failed: snapshot version {} not found", version);
            return false;
        }
        restore(entry.getValues());
        logger.info("Parameter registry restored to snapshot version={} tag={}", version, entry.getTag());
        return true;
    }

    /**
     * 查询指定版本的快照。
     *
     * @param version 版本号
     * @return 快照实体；不存在返回 null
     */
    public ConfigSnapshot getSnapshot(int version) {
        for (ConfigSnapshot entry : snapshotHistory) {
            if (entry.getVersion() == version) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 返回最新（版本号最大）的快照。
     *
     * @return 最新快照；无快照返回 null
     */
    public ConfigSnapshot getLatestSnapshot() {
        return snapshotHistory.peekLast();
    }

    /**
     * 列出所有快照历史（按版本号升序）。
     *
     * @return 快照历史列表（只读副本）
     */
    public List<ConfigSnapshot> listSnapshotHistory() {
        return new ArrayList<>(snapshotHistory);
    }

    /**
     * 清空快照历史（不影响当前参数值）。
     */
    public void clearSnapshotHistory() {
        snapshotHistory.clear();
        nextSnapshotVersion.set(0);
        logger.info("Snapshot history cleared");
    }
}