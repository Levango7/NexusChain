package org.nexus.oracle.governance.execution;

import org.junit.jupiter.api.Test;
import org.nexus.oracle.governance.execution.GovernanceAuditLog;
import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SoftwareUpgradeExecutor NACOS 模式测试（GOV-P2-03 实现）。
 */
class SoftwareUpgradeExecutorNacosTest {

    /** 通过反射构造执行器（NACOS 模式 + 指定 nacos server）。 */
    private SoftwareUpgradeExecutor buildExecutor(String nacosServer) throws Exception {
        SoftwareUpgradeExecutor executor = new SoftwareUpgradeExecutor(
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                new GovernanceAuditLog());
        setField(executor, "configUpdateModeConfig", "NACOS");
        setField(executor, "nacosServer", nacosServer);
        return executor;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Proposal upgradeProposal(String target, String version) {
        return Proposal.builder()
                .proposalId("PROP-NACOS-001")
                .title("Upgrade " + target + " to " + version)
                .type(Proposal.Type.SOFTWARE_UPGRADE)
                .state(ProposalState.PASSED)
                .proposer("devops-1")
                .parameters(Map.of("target", target, "version", version,
                        "config", Map.of("featureFlag", "enabled")))
                .build();
    }

    @Test
    void nacosUnreachable_failsSafe_noException() throws Exception {
        // Nacos 不可达（本机无 8848）→ 告警不抛异常，升级不中断
        SoftwareUpgradeExecutor executor = buildExecutor("http://127.0.0.1:59999");
        assertDoesNotThrow(() -> executor.execute(upgradeProposal("nexus-gateway", "2.2.0")),
                "Nacos 不可达必须 fail-safe（不中断升级主流程）");
    }

    @Test
    void nacosInvalidServer_failsSafe_noException() throws Exception {
        // 非法 server 地址 → fail-safe
        SoftwareUpgradeExecutor executor = buildExecutor("http://not-a-real-host:8848");
        assertDoesNotThrow(() -> executor.execute(upgradeProposal("nexus-core", "2.2.0")),
                "非法 Nacos 地址必须 fail-safe");
    }
}
