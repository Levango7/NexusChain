package org.nexus.l2.zk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * ZK 证明验证器。
 *
 * <p>验证 {@link ZkProof} 在给定 {@link ZkPublicInput} 下的有效性。
 * 支持两种证明格式：</p>
 * <ul>
 *   <li><b>Groth16 证明</b>（前缀 "G16P"）：委托给 {@link ZkProofSystem#verify}</li>
 *   <li><b>骨架证明</b>（前缀 "PROOF|"）：校验非空与格式一致性</li>
 * </ul>
 *
 * <h3>接入真实 ZK 库</h3>
 * <p>Groth16 证明的验证逻辑由 {@link DefaultZkProofSystem} 实现，本类通过注入的
 * {@link ZkProofSystem} 委托验证。骨架证明保留原校验逻辑供向后兼容。</p>
 *
 * <h3>校验规则</h3>
 * <ul>
 *   <li>proof 非空且 proofData 长度 > 0</li>
 *   <li>proof.circuitId 非空</li>
 *   <li>proof.setupVersion ≥ 1</li>
 *   <li>publicInput 非空（preStateRoot/postStateRoot 非空）</li>
 *   <li>Groth16 证明：委托 ZkProofSystem.verify</li>
 *   <li>骨架证明：校验 "PROOF|" 前缀</li>
 * </ul>
 *
 * @since 1.5
 */
@Component
public class ZkVerifier {

    private static final Logger logger = LoggerFactory.getLogger(ZkVerifier.class);

    /** Groth16 证明前缀 */
    private static final byte[] G16_PREFIX = "G16P".getBytes(StandardCharsets.US_ASCII);

    /** ZkProofSystem 引用（用于委托验证 Groth16 证明） */
    @Autowired
    private ZkProofSystem zkProofSystem;

    /**
     * 验证 ZK 证明。
     *
     * @param proof       ZK 证明
     * @param publicInput 公共输入
     * @return 验证通过返回 true；证明为空、字段缺失或校验失败返回 false
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

        byte[] data = proof.getProofData();

        // Groth16 证明：委托 ZkProofSystem.verify
        if (isGroth16Proof(data)) {
            if (zkProofSystem == null) {
                logger.warn("ZkVerifier: zkProofSystem not available for Groth16 verification");
                return false;
            }
            boolean valid = zkProofSystem.verify(proof, publicInput);
            logger.info("ZkVerifier verify (groth16): circuit={} setupVersion={} -> {}",
                    proof.getCircuitId(), proof.getSetupVersion(), valid ? "VALID" : "INVALID");
            return valid;
        }

        // 骨架证明：校验 "PROOF|" 前缀
        String prefix = new String(data, 0, Math.min(6, data.length), StandardCharsets.UTF_8);
        if (!"PROOF|".equals(prefix)) {
            logger.warn("ZkVerifier: proof data prefix mismatch (expected 'PROOF|' or 'G16P', got '{}')", prefix);
            return false;
        }
        logger.info("ZkVerifier verify (skeleton): circuit={} setupVersion={} -> VALID",
                proof.getCircuitId(), proof.getSetupVersion());
        return true;
    }

    /**
     * 判断证明数据是否为 Groth16 格式。
     */
    private static boolean isGroth16Proof(byte[] data) {
        if (data == null || data.length < G16_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < G16_PREFIX.length; i++) {
            if (data[i] != G16_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }
}
