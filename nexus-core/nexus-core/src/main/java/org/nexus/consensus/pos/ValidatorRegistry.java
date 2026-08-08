package org.nexus.consensus.pos;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.nexus.core.persist.StateSnapshotPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 验证人注册中心。
 *
 * <p>维护验证人集合，提供注册 / 注销、质押门槛校验与
 * 活跃验证人查询能力，是 PoS 共识的参与方管理基础组件。</p>
 *
 * <h3>持久化</h3>
 * <ul>
 *   <li>{@code @PostConstruct}：从 {@code validator-registry-snapshot.json} 加载验证人列表。</li>
 *   <li>{@code @PreDestroy}：保存全部验证人到同文件。</li>
 *   <li>加载 / 保存失败均不阻塞启动 / 关闭（告警 + 继续空内存）。</li>
 * </ul>
 *
 * @since 1.2
 */
@Component
public class ValidatorRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ValidatorRegistry.class);

    private static final String SNAPSHOT_FILE = "validator-registry-snapshot.json";

    /** 默认最低质押门槛 */
    private static final BigDecimal DEFAULT_MIN_STAKE = new BigDecimal("1000");

    /** 默认最大验证人数量 */
    private static final int DEFAULT_MAX_VALIDATORS = 100;

    private final BigDecimal minStakeAmount;
    private final int maxValidators;
    private final Map<String, Validator> validators = new ConcurrentHashMap<>();

    @Autowired
    private StateSnapshotPersister persister;

    public ValidatorRegistry() {
        this(DEFAULT_MIN_STAKE, DEFAULT_MAX_VALIDATORS);
    }

    public ValidatorRegistry(BigDecimal minStakeAmount, int maxValidators) {
        this.minStakeAmount = minStakeAmount;
        this.maxValidators = maxValidators;
    }

    /**
     * 启动时从快照恢复验证人集合。
     *
     * <p>快照格式：{@code List<Validator>}。文件不存在或解析失败时保持空内存。</p>
     */
    @PostConstruct
    void loadSnapshot() {
        if (persister == null) {
            return;
        }
        List<Validator> snapshot = persister.load(
                SNAPSHOT_FILE,
                new com.fasterxml.jackson.core.type.TypeReference<List<Validator>>() {
                });
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        int loaded = 0;
        for (Validator v : snapshot) {
            if (v == null || v.getAddress() == null) {
                continue;
            }
            validators.put(v.getAddress(), v);
            loaded++;
        }
        logger.info("Validator registry snapshot loaded: {} validators", loaded);
    }

    /**
     * 关闭时保存验证人集合到快照。
     *
     * <p>保存失败仅告警，不阻塞关闭。</p>
     */
    @PreDestroy
    void saveSnapshot() {
        if (persister == null || !persister.isEnabled() || validators.isEmpty()) {
            return;
        }
        persister.save(SNAPSHOT_FILE, new ArrayList<>(validators.values()));
    }

    /**
     * 注册新验证人。
     *
     * @param address        验证人地址（hex）
     * @param publicKey      验证人公钥（hex）
     * @param stakeAmount    初始质押金额
     * @param commissionRate 佣金率（0~1）
     * @return 注册成功返回 true；已存在、集合已满或质押不足返回 false
     */
    public boolean register(String address, String publicKey, BigDecimal stakeAmount, double commissionRate) {
        if (address == null || address.isEmpty()) {
            logger.warn("Register rejected: empty address");
            return false;
        }
        if (validators.containsKey(address)) {
            logger.warn("Register rejected: validator already exists {}", address);
            return false;
        }
        if (validators.size() >= maxValidators) {
            logger.warn("Register rejected: validator set full (max={})", maxValidators);
            return false;
        }
        if (stakeAmount == null || stakeAmount.compareTo(minStakeAmount) < 0) {
            logger.warn("Register rejected: stake {} below minimum {}", stakeAmount, minStakeAmount);
            return false;
        }
        Validator validator = new Validator(address, publicKey, stakeAmount, commissionRate, ValidatorStatus.ACTIVE);
        validators.put(address, validator);
        logger.info("Validator registered: {} stake={} commission={}", address, stakeAmount, commissionRate);
        return true;
    }

    /**
     * 注销验证人，将其状态置为 INACTIVE。
     *
     * @param address 验证人地址
     * @return 注销成功返回 true；不存在返回 false
     */
    public boolean unregister(String address) {
        Validator validator = validators.get(address);
        if (validator == null) {
            logger.warn("Unregister rejected: validator not found {}", address);
            return false;
        }
        validator.setStatus(ValidatorStatus.INACTIVE);
        logger.info("Validator unregistered: {}", address);
        return true;
    }

    /**
     * 查询指定验证人。
     *
     * @param address 验证人地址
     * @return 验证人实体，不存在返回 null
     */
    public Validator getValidator(String address) {
        return validators.get(address);
    }

    /**
     * 获取所有状态为 ACTIVE 的验证人列表。
     *
     * @return 活跃验证人列表
     */
    public List<Validator> getActiveValidators() {
        List<Validator> active = new ArrayList<>();
        for (Validator validator : validators.values()) {
            if (validator.getStatus() == ValidatorStatus.ACTIVE) {
                active.add(validator);
            }
        }
        return active;
    }

    /**
     * 获取全部已注册验证人（含非活跃）。
     *
     * @return 全部验证人列表
     */
    public List<Validator> getAllValidators() {
        return new ArrayList<>(validators.values());
    }

    public BigDecimal getMinStakeAmount() {
        return minStakeAmount;
    }

    public int getMaxValidators() {
        return maxValidators;
    }

    public int getValidatorCount() {
        return validators.size();
    }
}
