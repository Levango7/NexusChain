package org.nexus.l2.zk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ZK 证明系统配置。
 *
 * <p>通过 Spring 配置文件（application.properties/yml）或环境变量配置 ZK 证明系统行为：</p>
 * <pre>
 * zk.prover.enabled=true                  # 是否启用 ZK 证明（false 时 prove 返回占位证明）
 * zk.prover.backend=groth16               # 后端：groth16|plonk|halo2|mock
 * zk.prover.circuit.max-batch-size=1000   # 电路最大批次大小
 * zk.prover.groth16.curve=secp256k1       # Groth16 椭圆曲线
 * </pre>
 *
 * <h3>后端说明</h3>
 * <table>
 *   <caption>表：ZK 证明后端对照表</caption>
 *   <tr><th>后端</th><th>实现</th><th>状态</th></tr>
 *   <tr><td>groth16</td><td>BouncyCastle 椭圆曲线 Groth16 简化版</td><td>可用</td></tr>
 *   <tr><td>plonk</td><td>Plonk 证明系统</td><td>FROZEN per ADR-001（降级为 groth16）</td></tr>
 *   <tr><td>halo2</td><td>halo2-jvm 绑定</td><td>FROZEN per ADR-001（降级为 groth16）</td></tr>
 *   <tr><td>mock</td><td>骨架占位实现</td><td>可用</td></tr>
 * </table>
 *
 * @since 1.5
 */
@Component
@ConfigurationProperties(prefix = "zk.prover")
public class ZkProverProperties {

    /** 是否启用 ZK 证明（false 时 prove 返回占位证明，verify 仅校验非空） */
    private boolean enabled = true;

    /** 证明后端：groth16|plonk|halo2|mock */
    private String backend = "groth16";

    /** 电路配置 */
    private Circuit circuit = new Circuit();

    /** Groth16 配置 */
    private Groth16 groth16 = new Groth16();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }

    public Circuit getCircuit() { return circuit; }
    public void setCircuit(Circuit circuit) { this.circuit = circuit; }

    public Groth16 getGroth16() { return groth16; }
    public void setGroth16(Groth16 groth16) { this.groth16 = groth16; }

    /**
     * 解析后端类型为枚举。
     *
     * @return 后端枚举
     */
    public BackendType resolveBackend() {
        if (backend == null) return BackendType.GROTH16;
        switch (backend.toLowerCase().trim()) {
            case "groth16": return BackendType.GROTH16;
            case "plonk": return BackendType.PLONK;
            case "halo2": return BackendType.HALO2;
            case "mock": return BackendType.MOCK;
            default: return BackendType.GROTH16;
        }
    }

    /** 电路配置 */
    public static class Circuit {
        private int maxBatchSize = 1000;

        public int getMaxBatchSize() { return maxBatchSize; }
        public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    }

    /** Groth16 配置 */
    public static class Groth16 {
        private String curve = "secp256k1";

        public String getCurve() { return curve; }
        public void setCurve(String curve) { this.curve = curve; }
    }

    /** 后端类型枚举 */
    public enum BackendType {
        /** BouncyCastle Groth16 简化版（可用） */
        GROTH16,
        /** Plonk 证明系统（FROZEN per ADR-001，降级为 groth16） */
        PLONK,
        /** halo2-jvm 绑定（FROZEN per ADR-001，降级为 groth16） */
        HALO2,
        /** 骨架占位实现 */
        MOCK
    }
}