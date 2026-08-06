package org.nexus.l2.zk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * ZK 证明验证器（骨架实现）。
 *
 * <p>验证 {@link ZkProof} 在给定 {@link ZkPublicInput} 下的有效性。
 * 当前为骨架实现，仅校验证明非空、电路 ID 一致、setup 版本非负，
 * 不执行真实 ZK 验证算法。真实接入时替换 {@link #verify} 内部为
 * 对应 ZK 库的 verify 调用即可。</p>
 *
 * <h3>接入真实 ZK 库</h3>
 * <pre>
 * // halo2 示例
 * Proof proof = Proof.from_bytes(proof.getProofData());
 * return halo2.verify_proof(proof, publicInput, verifyingKey);
 * </pre>
 *
 * <h3>骨架校验规则</h3>
 * <ul>
 *   <li>proof 非空且 proofData 长度 > 0</li>
 *   <li>proof.circuitId 非空</li>
 *   <li>proof.setupVersion ≥ 1</li>
 *   <li>publicInput 非空（preStateRoot/postStateRoot 非空）</li>
 * </ul>
 *
 * @since 1.5
 */
@Component
public class ZkVerifier {

    private static final Logger logger = LoggerFactory.getLogger(ZkVerifier.class);

    /**
     * 验证 ZK 证明。
     *
     * @param proof       ZK 证明
     * @param publicInput 公共输入
     * @return 验证通过返回 true；证明为空、字段缺失或骨架校验失败返回 false
     */
    public boolean verify(ZkProof proof, ZkPublicInput publicInput) {
        if (proof == null) {
            logger.warn("ZkVerifier: proof is null");
            return false;
        }
        if (proof.size() == 0) {
            logger.warn("ZkVerifier: proof data empty");
            return false;
        }
        if (proof.getCircuitId() == null || proof.getCircuitId().isEmpty()) {
            logger.warn("ZkVerifier: circuitId empty");
            return false;
        }
        if (proof.getSetupVersion() < 1) {
            logger.warn("ZkVerifier: setupVersion invalid ({})", proof.getSetupVersion());
            return false;
        }
        if (publicInput == null) {
            logger.warn("ZkVerifier: publicInput is null");
            return false;
        }
        if (publicInput.getPreStateRoot() == null || publicInput.getPostStateRoot() == null) {
            logger.warn("ZkVerifier: stateRoots missing in publicInput");
            return false;
        }
        // 骨架实现：校验证明数据以 "PROOF|" 前缀开头（与 ZkProver 占位格式一致）
        byte[] data = proof.getProofData();
        String prefix = new String(data, 0, Math.min(6, data.length), StandardCharsets.UTF_8);
        if (!"PROOF|".equals(prefix)) {
            logger.warn("ZkVerifier: proof data prefix mismatch (expected 'PROOF|', got '{}')", prefix);
            return false;
        }
        logger.info("ZkVerifier verify (skeleton): circuit={} setupVersion={} -> VALID",
                proof.getCircuitId(), proof.getSetupVersion());
        return true;
    }
}