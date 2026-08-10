package org.nexus.l2.zk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可信设置（Trusted Setup）管理。
 *
 * <p>ZK 证明系统（如 Groth16、Plonk）依赖可信设置产物（proving key、verifying key），
 * 这些产物由 setup ceremony 生成。一旦 ceremony 完成，产物固定，所有 prover/verifier
 * 共享同一 setup 版本。</p>
 *
 * <h3>多版本管理</h3>
 * <p>支持多版本 setup 共存：升级电路或更换 ceremony 时创建新版本，
 * 旧证明仍可用旧版本验证，新证明用新版本生成，便于平滑升级。</p>
 *
 * <h3>安全注意</h3>
 * <ul>
 *   <li>真实场景中 setup ceremony 需多方参与（MPC ceremony），防单点作恶</li>
 *   <li>setup 产物（特别是 toxic waste）必须安全销毁</li>
 *   <li>本骨架实现仅记录元信息，不持有真实 pk/vk 字节</li>
 * </ul>
 *
 * <p>当前为骨架实现，真实可信设置需接入 ceremony 工具链（如 halo2 的 setup、
 * Plonk 的 powers of tau）。</p>
 *
 * @since 1.5
 */
@Component
public class TrustedSetup {

    private static final Logger logger = LoggerFactory.getLogger(TrustedSetup.class);

    /** setup 历史版本（按版本号递增顺序） */
    private final ConcurrentLinkedDeque<SetupVersion> versions = new ConcurrentLinkedDeque<>();

    /** 下一个版本号 */
    private final AtomicInteger nextVersion = new AtomicInteger(0);

    /** 当前激活版本号 */
    private volatile int activeVersion = 0;

    /**
     * 注册一个新的可信设置版本。
     *
     * @param circuitId    关联电路 ID
     * @param ceremonyTag  ceremony 标签（如 "powers-of-tau-2026Q3"）
     * @param participantCount 参与 MPC ceremony 的参与方数量
     * @return 新版本号
     */
    public int registerVersion(String circuitId, String ceremonyTag, int participantCount) {
        int version = nextVersion.incrementAndGet();
        SetupVersion sv = new SetupVersion(version, circuitId, ceremonyTag, participantCount, Instant.now());
        versions.addLast(sv);
        if (activeVersion == 0) {
            activeVersion = version;
        }
        logger.info("Trusted setup version registered: version={} circuit={} tag={} participants={}",
                version, circuitId, ceremonyTag, participantCount);
        return version;
    }

    /**
     * 设置当前激活版本。
     *
     * @param version 版本号
     * @return 设置成功返回 true；版本不存在返回 false
     */
    public boolean setActiveVersion(int version) {
        if (getVersion(version) == null) {
            logger.warn("Set active version rejected: version {} not found", version);
            return false;
        }
        this.activeVersion = version;
        logger.info("Trusted setup active version set to {}", version);
        return true;
    }

    public int getActiveVersion() {
        return activeVersion;
    }

    /**
     * 查询指定版本信息。
     *
     * @param version 版本号
     * @return 版本信息；不存在返回 null
     */
    public SetupVersion getVersion(int version) {
        for (SetupVersion sv : versions) {
            if (sv.version == version) {
                return sv;
            }
        }
        return null;
    }

    /**
     * 列出所有 setup 版本（按版本号升序）。
     *
     * @return 版本列表
     */
    public List<SetupVersion> listVersions() {
        return new ArrayList<>(versions);
    }

    /**
     * 返回当前激活版本信息。
     *
     * @return 激活版本；未注册任何版本返回 null
     */
    public SetupVersion getActiveVersionInfo() {
        return getVersion(activeVersion);
    }

    /**
     * 可信设置版本实体。
     */
    public static final class SetupVersion {
        /** 版本号 */
        private final int version;
        /** 关联电路 ID */
        private final String circuitId;
        /** ceremony 标签 */
        private final String ceremonyTag;
        /** MPC 参与方数量 */
        private final int participantCount;
        /** 注册时间 */
        private final Instant registeredAt;

        SetupVersion(int version, String circuitId, String ceremonyTag,
                     int participantCount, Instant registeredAt) {
            this.version = version;
            this.circuitId = circuitId;
            this.ceremonyTag = ceremonyTag;
            this.participantCount = participantCount;
            this.registeredAt = registeredAt;
        }

        public int getVersion() {
            return version;
        }

        public String getCircuitId() {
            return circuitId;
        }

        public String getCeremonyTag() {
            return ceremonyTag;
        }

        public int getParticipantCount() {
            return participantCount;
        }

        public Instant getRegisteredAt() {
            return registeredAt;
        }

        @Override
        public String toString() {
            return "SetupVersion{version=" + version
                    + ", circuitId='" + circuitId + '\''
                    + ", tag='" + ceremonyTag + '\''
                    + ", participants=" + participantCount + '}';
        }
    }
}