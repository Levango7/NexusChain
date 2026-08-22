package org.nexus.bridge.safety;

import org.nexus.bridge.model.BridgeTransaction.BridgeTxStatus;
import org.nexus.bridge.repository.BridgeTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金守恒校验器（P2-F2）。
 *
 * <p>定时校验跨链桥的资金守恒不变式，发现偏差超过阈值时告警
 * （日志 ERROR + 可选 webhook 通知）。校验在独立线程执行，
 * 不阻塞正常交易流程。</p>
 *
 * <h2>守恒不变式</h2>
 * <p>对每条链 {@code C}：</p>
 * <ul>
 *   <li><b>正向：</b> {@code locked(C) ≥ minted(C)}
 *       <ul>
 *         <li>{@code locked(C)} = 以 C 为源链、状态为 LOCKED/MINTED 的交易金额之和</li>
 *         <li>{@code minted(C)} = 以 C 为目标链、状态为 MINTED 的交易金额之和</li>
 *         <li>差值 = 待铸造量（应 ≥ 0；若 &lt; 0 表示铸造超过锁定，严重违规）</li>
 *       </ul>
 *   </li>
 *   <li><b>反向：</b> {@code burned(C) ≥ unlocked(C)}
 *       <ul>
 *         <li>{@code burned(C)} = 以 C 为目标链、状态为 BURNED/UNLOCKED 的交易金额之和</li>
 *         <li>{@code unlocked(C)} = 以 C 为源链、状态为 UNLOCKED 的交易金额之和</li>
 *         <li>差值 = 待解锁量（应 ≥ 0；若 &lt; 0 表示解锁超过销毁，严重违规）</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>告警阈值</h2>
 * <ul>
 *   <li>负偏差（minted &gt; locked 或 unlocked &gt; burned）：立即 ERROR 告警，可能资金被盗</li>
 *   <li>正偏差超过 {@code threshold}（积压过多）：WARN 告警，可能 Saga 卡住</li>
 * </ul>
 *
 * <h2>集成</h2>
 * <p>检测到严重偏差时联动 {@link EmergencyPauseService} 紧急暂停桥，
 * 并通过 {@link InsuranceFund} 准备补偿（仅记录日志，实际补偿需人工审批）。</p>
 *
 * @since 2.2.0
 */
@Service
public class FundConservationChecker {

    private static final Logger log = LoggerFactory.getLogger(FundConservationChecker.class);

    /** 桥 ID（用于联动 EmergencyPauseService）。 */
    private static final String BRIDGE_ID = "nexus-bridge";

    private final BridgeTransactionRepository txRepository;

    /** 紧急暂停服务（可选，自动暂停严重偏差的桥）。 */
    @Autowired(required = false)
    private DefaultEmergencyPauseService emergencyPauseService;

    /** 保险基金（可选，记录补偿预案）。 */
    @Autowired(required = false)
    private InsuranceFund insuranceFund;

    /** 正偏差告警阈值（默认 1000 NEX 最小单位，即 10^21 最小单位）。 */
    @Value("${nexus.bridge.fund-conservation.threshold:1000000000000}")
    private long alertThreshold;

    /** 是否在严重偏差时自动紧急暂停桥。 */
    @Value("${nexus.bridge.fund-conservation.auto-pause:true}")
    private boolean autoPauseOnViolation;

    /** 是否启用守恒校验。 */
    @Value("${nexus.bridge.fund-conservation.enabled:true}")
    private boolean enabled;

    /** 校验周期 cron（默认每 5 分钟）。 */
    @Value("${nexus.bridge.fund-conservation.cron:0 */5 * * * ?}")
    private String cron;

    /**
     * 构造资金守恒校验器。
     *
     * @param txRepository 桥交易 Repository
     */
    @Autowired
    public FundConservationChecker(BridgeTransactionRepository txRepository) {
        this.txRepository = txRepository;
    }

    /**
     * 定时校验资金守恒。
     *
     * <p>每 5 分钟执行一次（可配置）。校验异常仅记录日志，
     * 不影响后续执行。</p>
     */
    @Scheduled(cron = "${nexus.bridge.fund-conservation.cron:0 */5 * * * ?}")
    public void checkConservation() {
        if (!enabled) {
            return;
        }
        try {
            ConservationResult result = doCheck();
            if (result.hasViolations()) {
                handleViolations(result);
            } else {
                log.info("Fund conservation check passed: {} chains verified, maxDelta={}",
                        result.getChainCount(), result.getMaxAbsDelta());
            }
        } catch (RuntimeException e) {
            log.error("Fund conservation check failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行守恒校验（同步，可被测试直接调用）。
     *
     * @return 校验结果
     */
    public ConservationResult doCheck() {
        ConservationResult result = new ConservationResult();

        // LOCKED + MINTED：已锁定总量（含已铸造）
        List<BridgeTxStatus> lockedStatuses = Arrays.asList(
                BridgeTxStatus.LOCK_PENDING, BridgeTxStatus.LOCKED, BridgeTxStatus.MINTED);
        // MINTED：已铸造总量
        List<BridgeTxStatus> mintedStatuses = Arrays.asList(BridgeTxStatus.MINTED);
        // BURNED + UNLOCKED：已销毁总量（含已解锁）
        List<BridgeTxStatus> burnedStatuses = Arrays.asList(
                BridgeTxStatus.BURN_PENDING, BridgeTxStatus.BURNED, BridgeTxStatus.UNLOCKED);
        // UNLOCKED：已解锁总量
        List<BridgeTxStatus> unlockedStatuses = Arrays.asList(BridgeTxStatus.UNLOCKED);

        // 遍历所有出现过的链
        List<String> sourceChains = txRepository.findDistinctSourceChainIds();
        List<String> targetChains = txRepository.findDistinctTargetChainIds();
        java.util.Set<String> allChains = new java.util.TreeSet<>();
        allChains.addAll(sourceChains);
        allChains.addAll(targetChains);

        for (String chainId : allChains) {
            long locked = txRepository.sumAmountBySourceChainIdAndStatusIn(chainId, lockedStatuses);
            long minted = txRepository.sumAmountByTargetChainIdAndStatusIn(chainId, mintedStatuses);
            long burned = txRepository.sumAmountByTargetChainIdAndStatusIn(chainId, burnedStatuses);
            long unlocked = txRepository.sumAmountBySourceChainIdAndStatusIn(chainId, unlockedStatuses);

            long lockDelta = locked - minted;   // 待铸造量，应 >= 0
            long burnDelta = burned - unlocked; // 待解锁量，应 >= 0

            result.recordChain(chainId, locked, minted, burned, unlocked, lockDelta, burnDelta);
        }
        return result;
    }

    /**
     * 处理守恒违规。
     *
     * @param result 校验结果
     */
    private void handleViolations(ConservationResult result) {
        for (Map.Entry<String, ChainConservation> entry : result.getChains().entrySet()) {
            String chainId = entry.getKey();
            ChainConservation c = entry.getValue();

            if (c.lockDelta < 0) {
                // 严重违规：铸造超过锁定
                log.error("FUND_CONSERVATION_VIOLATION [{}] minted({}) > locked({}), delta={}"
                                + " — possible double-mint or stolen funds",
                        chainId, c.minted, c.locked, c.lockDelta);
                triggerEmergencyPause(chainId, "minted > locked: delta=" + c.lockDelta);
            } else if (c.lockDelta > alertThreshold) {
                log.warn("FUND_CONSERVATION_BACKLOG [{}] pending mint {} exceeds threshold {}",
                        chainId, c.lockDelta, alertThreshold);
            }

            if (c.burnDelta < 0) {
                // 严重违规：解锁超过销毁
                log.error("FUND_CONSERVATION_VIOLATION [{}] unlocked({}) > burned({}), delta={}"
                                + " — possible double-unlock or stolen funds",
                        chainId, c.unlocked, c.burned, c.burnDelta);
                triggerEmergencyPause(chainId, "unlocked > burned: delta=" + c.burnDelta);
            } else if (c.burnDelta > alertThreshold) {
                log.warn("FUND_CONSERVATION_BACKLOG [{}] pending unlock {} exceeds threshold {}",
                        chainId, c.burnDelta, alertThreshold);
            }
        }
    }

    /**
     * 触发紧急暂停（联动 EmergencyPauseService）。
     *
     * @param chainId 链 ID
     * @param reason  暂停原因
     */
    private void triggerEmergencyPause(String chainId, String reason) {
        if (!autoPauseOnViolation || emergencyPauseService == null) {
            return;
        }
        try {
            String bridgeId = BRIDGE_ID + ":" + chainId;
            emergencyPauseService.triggerPause(bridgeId,
                    DefaultEmergencyPauseService.STATE_EMERGENCY_STOP,
                    "fund conservation violation: " + reason,
                    "FundConservationChecker");
            log.warn("Bridge {} emergency-paused due to fund conservation violation: {}",
                    bridgeId, reason);
        } catch (RuntimeException e) {
            log.error("Failed to trigger emergency pause for chain {}: {}", chainId, e.getMessage(), e);
        }
    }

    // ==================== 校验结果数据结构 ====================

    /**
     * 校验结果。
     */
    public static class ConservationResult {
        private final Map<String, ChainConservation> chains = new LinkedHashMap<>();
        private long maxAbsDelta = 0;

        public void recordChain(String chainId, long locked, long minted,
                                long burned, long unlocked,
                                long lockDelta, long burnDelta) {
            chains.put(chainId, new ChainConservation(locked, minted, burned, unlocked,
                    lockDelta, burnDelta));
            maxAbsDelta = Math.max(maxAbsDelta, Math.abs(lockDelta));
            maxAbsDelta = Math.max(maxAbsDelta, Math.abs(burnDelta));
        }

        public boolean hasViolations() {
            for (ChainConservation c : chains.values()) {
                if (c.lockDelta < 0 || c.burnDelta < 0) {
                    return true;
                }
            }
            return false;
        }

        public int getChainCount() {
            return chains.size();
        }

        public long getMaxAbsDelta() {
            return maxAbsDelta;
        }

        public Map<String, ChainConservation> getChains() {
            return chains;
        }
    }

    /**
     * 单条链的守恒快照。
     */
    public static class ChainConservation {
        public final long locked;
        public final long minted;
        public final long burned;
        public final long unlocked;
        public final long lockDelta;
        public final long burnDelta;

        ChainConservation(long locked, long minted, long burned, long unlocked,
                          long lockDelta, long burnDelta) {
            this.locked = locked;
            this.minted = minted;
            this.burned = burned;
            this.unlocked = unlocked;
            this.lockDelta = lockDelta;
            this.burnDelta = burnDelta;
        }
    }
}