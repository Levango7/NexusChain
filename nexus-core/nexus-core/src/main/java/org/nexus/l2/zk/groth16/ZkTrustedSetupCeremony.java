package org.nexus.l2.zk.groth16;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ZK 多方设置仪式（Trusted Setup Ceremony）模拟。
 *
 * <p>模拟 Groth16 的 Powers of Tau 多方设置仪式：
 * <ul>
 *   <li>初始参数：powers of tau [1, τ, τ², τ³, ...]</li>
 *   <li>每个参与者依次贡献随机性 r：更新参数为 [1, τ·r, (τ·r)², ...]</li>
 *   <li>最终参数：所有参与者随机性的乘积</li>
 *   <li>只要有一个参与者诚实销毁其随机性（toxic waste），setup 就是安全的</li>
 * </ul>
 *
 * <p>纯 Java 模拟，用于验证多方设置仪式的逻辑正确性。
 * 生产环境应使用 snarkjs/arkworks 等专业库实现真实仪式。
 *
 * @since 2.12.0
 */
public class ZkTrustedSetupCeremony {

    private static final Logger log = LoggerFactory.getLogger(ZkTrustedSetupCeremony.class);

    /** 模拟的有限域素数（BN254 曲线阶的简化模拟） */
    private static final BigInteger P = new BigInteger(
            "21888242871839275222246405745257275088548364400416034343698204186575808495617");

    private final int circuitSize;
    private final List<Participant> participants = new CopyOnWriteArrayList<>();
    private volatile BigInteger[] powersOfTau;

    /** 参与者记录 */
    public static class Participant {
        final String id;
        final BigInteger contribution;
        boolean toxicWasteDestroyed;

        Participant(String id, BigInteger contribution) {
            this.id = id;
            this.contribution = contribution;
            this.toxicWasteDestroyed = false;
        }
    }

    /** 仪式结果 */
    public static class CeremonyResult {
        public final BigInteger[] provingKey;
        public final BigInteger[] verifyingKey;
        public final int participantCount;

        CeremonyResult(BigInteger[] provingKey, BigInteger[] verifyingKey, int participantCount) {
            this.provingKey = provingKey;
            this.verifyingKey = verifyingKey;
            this.participantCount = participantCount;
        }
    }

    public ZkTrustedSetupCeremony(int circuitSize) {
        this.circuitSize = circuitSize;
        // 初始参数：τ=1（空仪式）
        this.powersOfTau = new BigInteger[circuitSize];
        for (int i = 0; i < circuitSize; i++) {
            powersOfTau[i] = BigInteger.ONE;
        }
    }

    /** 参与者贡献随机性 */
    public void contribute(String participantId, BigInteger randomness) {
        BigInteger r = randomness.mod(P);
        if (r.equals(BigInteger.ZERO)) {
            throw new IllegalArgumentException("随机性不能为0");
        }
        participants.add(new Participant(participantId, r));

        // 更新 powers of tau: τ^i → (τ·r)^i
        BigInteger currentPower = BigInteger.ONE;
        for (int i = 0; i < circuitSize; i++) {
            powersOfTau[i] = powersOfTau[i].multiply(currentPower).mod(P);
            currentPower = currentPower.multiply(r).mod(P);
        }
        log.info("Participant {} contributed to ceremony (total: {})", participantId, participants.size());
    }

    /** 参与者销毁 toxic waste */
    public void destroyToxicWaste(String participantId) {
        for (Participant p : participants) {
            if (p.id.equals(participantId)) {
                p.toxicWasteDestroyed = true;
                log.info("Participant {} destroyed toxic waste", participantId);
                return;
            }
        }
    }

    /** 完成仪式，生成 proving key 和 verifying key */
    public CeremonyResult finalizeCeremony() {
        BigInteger[] pk = new BigInteger[circuitSize];
        BigInteger[] vk = new BigInteger[2]; // [α, β] 简化

        System.arraycopy(powersOfTau, 0, pk, 0, circuitSize);
        // verifying key: 第一个和最后一个 power
        vk[0] = powersOfTau[0];
        vk[1] = powersOfTau[circuitSize - 1];

        log.info("Ceremony finalized: {} participants, circuit size {}", participants.size(), circuitSize);
        return new CeremonyResult(pk, vk, participants.size());
    }

    /** 检查仪式安全性（至少一个参与者销毁了 toxic waste） */
    public boolean isSecure() {
        return participants.stream().anyMatch(p -> p.toxicWasteDestroyed);
    }

    /** 获取参与者列表 */
    public List<Participant> getParticipants() {
        return new ArrayList<>(participants);
    }

    /** 获取当前 powers of tau */
    public BigInteger[] getPowersOfTau() {
        return powersOfTau.clone();
    }
}