package org.nexus.l2.zk.groth16;

import org.junit.jupiter.api.*;
import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZK 多方设置仪式测试。
 *
 * <p>验证 Groth16 Powers of Tau 多方设置仪式的逻辑正确性：
 * 多方贡献随机性、参数更新、toxic waste 销毁、仪式安全性。
 *
 * @since 2.12.0
 */
@DisplayName("ZK多方设置仪式")
class ZkTrustedSetupCeremonyTest {

    private static final int CIRCUIT_SIZE = 8;
    private static final BigInteger P = new BigInteger(
            "21888242871839275222246405745257275088548364400416034343698204186575808495617");

    @Test
    @Order(1)
    @DisplayName("1. 多方设置仪式→3参与者贡献→生成非平凡参数")
    void multiPartyCeremony_generatesNonTrivialParameters() {
        ZkTrustedSetupCeremony ceremony = new ZkTrustedSetupCeremony(CIRCUIT_SIZE);

        // 3个参与者贡献随机性
        ceremony.contribute("alice", BigInteger.valueOf(42));
        ceremony.contribute("bob", BigInteger.valueOf(123));
        ceremony.contribute("carol", BigInteger.valueOf(777));

        var result = ceremony.finalizeCeremony();
        assertEquals(3, result.participantCount, "应有3个参与者");
        assertNotNull(result.provingKey);
        assertNotNull(result.verifyingKey);
        assertEquals(CIRCUIT_SIZE, result.provingKey.length);

        // 参数应非平凡（不是全1）
        boolean hasNonTrivial = false;
        for (BigInteger pk : result.provingKey) {
            if (!pk.equals(BigInteger.ONE)) {
                hasNonTrivial = true;
                break;
            }
        }
        assertTrue(hasNonTrivial, "多方贡献后参数应非平凡");
    }

    @Test
    @Order(2)
    @DisplayName("2. 单方设置vs多方设置→参数不同")
    void singleVsMultiParty_differentParameters() {
        // 单方
        ZkTrustedSetupCeremony single = new ZkTrustedSetupCeremony(CIRCUIT_SIZE);
        single.contribute("solo", BigInteger.valueOf(42));
        var singleResult = single.finalizeCeremony();

        // 多方
        ZkTrustedSetupCeremony multi = new ZkTrustedSetupCeremony(CIRCUIT_SIZE);
        multi.contribute("alice", BigInteger.valueOf(42));
        multi.contribute("bob", BigInteger.valueOf(123));
        var multiResult = multi.finalizeCeremony();

        assertFalse(Arrays.equals(singleResult.provingKey, multiResult.provingKey),
                "单方和多方设置应生成不同参数");
    }

    @Test
    @Order(3)
    @DisplayName("3. 参与者贡献随机性→参数更新")
    void contribution_updatesParameters() {
        ZkTrustedSetupCeremony ceremony = new ZkTrustedSetupCeremony(CIRCUIT_SIZE);

        // 初始参数全1
        BigInteger[] before = ceremony.getPowersOfTau();
        assertTrue(Arrays.stream(before).allMatch(b -> b.equals(BigInteger.ONE)),
                "初始参数应全1");

        // 贡献随机性
        ceremony.contribute("alice", BigInteger.valueOf(42));
        BigInteger[] after = ceremony.getPowersOfTau();

        // 参数应更新（至少有一个非1）
        assertTrue(Arrays.stream(after).anyMatch(b -> !b.equals(BigInteger.ONE)),
                "贡献后参数应更新");
    }

    @Test
    @Order(4)
    @DisplayName("4. 仪式安全性→至少一个销毁toxic waste→安全")
    void security_atLeastOneDestroyed() {
        ZkTrustedSetupCeremony ceremony = new ZkTrustedSetupCeremony(CIRCUIT_SIZE);
        ceremony.contribute("alice", BigInteger.valueOf(42));
        ceremony.contribute("bob", BigInteger.valueOf(123));
        ceremony.contribute("carol", BigInteger.valueOf(777));

        // 未销毁任何toxic waste → 不安全
        assertFalse(ceremony.isSecure(), "未销毁toxic waste应不安全");

        // alice销毁 → 安全
        ceremony.destroyToxicWaste("alice");
        assertTrue(ceremony.isSecure(), "至少一个销毁应安全");
    }

    @Test
    @Order(5)
    @DisplayName("5. 参与者故障→仪式可继续（跳过故障参与者）")
    void participantFailure_ceremonyContinues() {
        ZkTrustedSetupCeremony ceremony = new ZkTrustedSetupCeremony(CIRCUIT_SIZE);

        // alice 和 bob 贡献
        ceremony.contribute("alice", BigInteger.valueOf(42));
        ceremony.contribute("bob", BigInteger.valueOf(123));

        // carol 故障（不贡献），仪式继续
        var result = ceremony.finalizeCeremony();
        assertEquals(2, result.participantCount, "2个参与者贡献");
        assertNotNull(result.provingKey);

        // 仪式仍可完成（2个参与者足够）
        ceremony.destroyToxicWaste("alice");
        assertTrue(ceremony.isSecure(), "2个参与者中1个销毁仍安全");
    }

    @Test
    @Order(6)
    @DisplayName("6. 零随机性→拒绝")
    void zeroContribution_rejected() {
        ZkTrustedSetupCeremony ceremony = new ZkTrustedSetupCeremony(CIRCUIT_SIZE);
        assertThrows(IllegalArgumentException.class,
                () -> ceremony.contribute("evil", BigInteger.ZERO),
                "零随机性应被拒绝");
    }

    @Test
    @Order(7)
    @DisplayName("7. 仪式结果可验证→proving key和verifying key一致")
    void ceremonyResult_verifiable() {
        ZkTrustedSetupCeremony ceremony = new ZkTrustedSetupCeremony(CIRCUIT_SIZE);
        ceremony.contribute("alice", BigInteger.valueOf(42));
        ceremony.contribute("bob", BigInteger.valueOf(123));

        var result = ceremony.finalizeCeremony();

        // verifying key 的第一个元素应等于 proving key 的第一个元素
        assertEquals(result.provingKey[0], result.verifyingKey[0],
                "vk[0] 应等于 pk[0]");
        // verifying key 的第二个元素应等于 proving key 的最后一个元素
        assertEquals(result.provingKey[CIRCUIT_SIZE - 1], result.verifyingKey[1],
                "vk[1] 应等于 pk[circuitSize-1]");
    }
}