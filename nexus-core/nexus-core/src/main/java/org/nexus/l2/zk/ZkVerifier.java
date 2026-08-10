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
 *   <li><b>mock 证明</b>（前缀 "MOCK|" 或旧版 "PROOF|"）：委托给
 *       {@link ZkProofSystem#verify}，受 {@code zk.prover.mock.allow-verify} 控制
 *       （ZK-P2-04 修复）</li>
 * </ul>
 *
 * <h3>接入真实 ZK 库</h3>
 * <p>Groth16 证明的验证逻辑由 {@link DefaultZkProofSystem} 实现，本类通过注入的
 * {@link ZkProofSystem} 委托验证。mock 证明同样委托给 {@link ZkProofSystem}，
 * 确保 allow-verify 安全控制统一生效。</p>
 *
 * <h3>校验规则</h3>
 * <ul>
 *   <li>proof 非空且 proofData 长度 > 0</li>
 *   <li>proof.circuitId 非空</li>
 *   <li>proof.setupVersion ≥ 1</li>
 *   <li>publicInput 非空（preStateRoot/postStateRoot 非空）</li>
 *   <li>Groth16 证明：委托 ZkProofSystem.verify</li>
 *   <li>mock 证明（"MOCK|" 或 "PROOF|"）：委托 ZkProofSystem.verify（受 allow-verify 控制）</li>
 * </ul>
 *
 * <h3>mock 证明安全控制（ZK-P2-04，2.1.0）</h3>
 * <p>自 2.1.0 起，mock 证明（"MOCK|" 前缀）和旧格式骨架证明（"PROOF|" 前缀）
 * 均委托给 {@link ZkProofSystem#verify}，受 {@code zk.prover.mock.allow-verify}
 * 配置控制。默认 allow-verify=false，mock 证明验证被拒绝，防止生产环境误用。</p>
 *
 * @since 1.5
 */
@Component
public class ZkVerifier {

    private static final Logger logger = LoggerFactory.getLogger(ZkVerifier.class);

    /** Groth16 证明前缀 */
    private static final byte[] G16_PREFIX = "G16P".getBytes(StandardCharsets.US_ASCII);

    /** mock 证明前缀（ZK-P2-04） */
    private static final byte[] MOCK_PREFIX = "MOCK|".getBytes(StandardCharsets.US_ASCII);

    /** 旧格式骨架证明前缀（2.1.0 之前） */
    private static final byte[] LEGACY_MOCK_PREFIX = "PROOF|".getBytes(StandardCharsets.US_ASCII);

    /** ZkProofSystem 引用（用于委托验证 Groth16 证明与 mock 证明） */
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
            return delegateVerify(proof, publicInput, "groth16");
        }

        // ZK-P2-04 修复：mock 证明（"MOCK|" 前缀）委托 ZkProofSystem.verify
        if (isMockProof(data)) {
            return delegateVerify(proof, publicInput, "mock");
        }

        // 兼容旧格式骨架证明（"PROOF|" 前缀，2.1.0 之前）
        // 同样委托 ZkProofSystem.verify，应用 allow-verify 控制
        if (isLegacyMockProof(data)) {
            return delegateVerify(proof, publicInput, "legacy-mock");
        }

        logger.warn("ZkVerifier: unknown proof format (prefix mismatch)");
        return false;
    }

    /**
     * 委托给 {@link ZkProofSystem#verify} 进行验证。
     *
     * @param proof       ZK 证明
     * @param publicInput 公共输入
     * @param type        证明类型（用于日志）
     * @return 验证结果
     */
    private boolean delegateVerify(ZkProof proof, ZkPublicInput publicInput, String type) {
        if (zkProofSystem == null) {
            logger.warn("ZkVerifier: zkProofSystem not available for {} verification", type);
            return false;
        }
        boolean valid = zkProofSystem.verify(proof, publicInput);
        logger.info("ZkVerifier verify ({}): circuit={} setupVersion={} -> {}",
                type, proof.getCircuitId(), proof.getSetupVersion(), valid ? "VALID" : "INVALID");
        return valid;
    }

    /**
     * 判断证明数据是否为 Groth16 格式。
     */
    private static boolean isGroth16Proof(byte[] data) {
        return hasPrefix(data, G16_PREFIX);
    }

    /**
     * 判断证明数据是否为 mock 格式（"MOCK|" 前缀，ZK-P2-04）。
     */
    private static boolean isMockProof(byte[] data) {
        return hasPrefix(data, MOCK_PREFIX);
    }

    /**
     * 判断证明数据是否为旧格式骨架证明（"PROOF|" 前缀，2.1.0 之前）。
     */
    private static boolean isLegacyMockProof(byte[] data) {
        return hasPrefix(data, LEGACY_MOCK_PREFIX);
    }

    /**
     * 检查数据是否以指定前缀开头。
     */
    private static boolean hasPrefix(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
