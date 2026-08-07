package org.nexus.l2.zk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * ZK 证明生成器（骨架实现）。
 *
 * <p>实现 {@link ZkProofSystem} 接口，提供 prove 阶段的骨架实现。
 * 当前不接入真实 ZK 库，prove 返回占位证明字节（含电路 ID、setup 版本与时间戳的摘要），
 * 便于上层 {@link org.nexus.l2.ZkRollup} 走通 submitBatch → prove → verify 流程。</p>
 *
 * <h3>接入真实 ZK 库</h3>
 * <p>替换 {@link #prove} 内部为对应库的 prove 调用即可，例如：</p>
 * <pre>
 * // halo2 示例
 * Proof proof = halo2.create_proof(circuit, witness, publicInput, provingKey);
 * return new ZkProof(proof.to_bytes(), circuit.getCircuitId(), setupVersion, System.currentTimeMillis());
 * </pre>
 *
 * @since 1.5
 */
@Component
public class ZkProver implements ZkProofSystem {

    private static final Logger logger = LoggerFactory.getLogger(ZkProver.class);

    @Autowired
    private TrustedSetup trustedSetup;

    @Autowired
    private ZkVerifier zkVerifier;

    @Override
    public int setup(ZkCircuit circuit) {
        if (circuit == null) {
            throw new IllegalArgumentException("circuit cannot be null");
        }
        int constraints = circuit.defineCircuit();
        int version = trustedSetup.registerVersion(circuit.getCircuitId(),
                "skeleton-setup-" + Instant.now().toEpochMilli(), 1);
        logger.info("ZkProver setup: circuit={} constraints={} setupVersion={}",
                circuit.getCircuitId(), constraints, version);
        return version;
    }

    @Override
    public ZkProof prove(ZkCircuit circuit, byte[] witness, ZkPublicInput publicInput) {
        if (circuit == null) {
            throw new IllegalArgumentException("circuit cannot be null");
        }
        // 骨架实现：综合电路（占位），生成占位证明字节
        byte[] assignment = circuit.synthesize(witness);
        int setupVersion = trustedSetup.getActiveVersion();
        if (setupVersion < 1) setupVersion = 1;
        // 占位证明：电路 ID + setup 版本 + 公共输入摘要 + assignment 长度
        String placeholder = "PROOF|" + circuit.getCircuitId() + "|v" + setupVersion
                + "|" + (publicInput == null ? "null" : publicInput.toString())
                + "|assignLen=" + (assignment == null ? 0 : assignment.length);
        byte[] proofData = placeholder.getBytes(StandardCharsets.UTF_8);
        ZkProof proof = new ZkProof(proofData, circuit.getCircuitId(), setupVersion, System.currentTimeMillis());
        logger.info("ZkProver prove (skeleton): circuit={} setupVersion={} proofSize={}",
                circuit.getCircuitId(), setupVersion, proof.size());
        return proof;
    }

    @Override
    public boolean verify(ZkProof proof, ZkPublicInput publicInput) {
        // 验证逻辑由 ZkVerifier 承担，此处委托
        return zkVerifier != null ? zkVerifier.verify(proof, publicInput) : false;
    }

    @Override
    public String getName() {
        return "skeleton-prover";
    }
}