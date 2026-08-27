package org.nexus.l2.zk;

import org.nexus.l2.zk.groth16.Groth16Proof;
import org.nexus.l2.zk.groth16.Groth16ProofSystem;
import org.nexus.l2.zk.groth16.Groth16Setup;
import org.nexus.l2.zk.r1cs.R1csConstraintSystem;
import org.nexus.l2.zk.r1cs.R1csToJsonBridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

/**
 * 默认 ZK 证明系统实现（根据配置选择后端）。
 *
 * <p>实现 {@link ZkProofSystem} 接口，根据 {@link ZkProverProperties} 配置选择证明后端：</p>
 * <ul>
 *   <li><b>groth16</b>（默认）：使用 {@link Groth16ProofSystem}（BouncyCastle 椭圆曲线）</li>
 *   <li><b>plonk</b>：FROZEN per ADR-001，当前降级为 groth16</li>
 *   <li><b>halo2</b>：FROZEN per ADR-001，当前降级为 groth16</li>
 *   <li><b>mock</b>：骨架占位实现（prove 返回占位证明，verify 校验非空）</li>
 *   <li>{@code zk.prover.enabled=false}：禁用 ZK，prove 返回占位证明</li>
 * </ul>
 *
 * <h3>R1CS 集成</h3>
 * <p>当电路提供 R1CS 约束（{@link ZkCircuit#hasR1cs()} 返回 true）且后端为 groth16 时，
 * 走真实 Groth16 流程：setup → prove → verify。否则降级为骨架模式。</p>
 *
 * <h3>witness 编码</h3>
 * <p>witness byte[] 编码格式（供 RollupStateTransitionCircuit 使用）：</p>
 * <pre>
 * [magic(4)="ZWIT"] [numEffects(4)] [effect_0(8)] ... [effect_{n-1}(8)]
 * </pre>
 * <p>若 witness 不符合此格式，降级为占位证明。</p>
 *
 * <h3>mock 证明安全控制（ZK-P2-04，2.1.0）</h3>
 * <p>mock 后端生成的证明包含明确 {@code "MOCK|"} 前缀标记。verify 行为：</p>
 * <ul>
 *   <li>{@code zk.prover.mock.allow-verify=false}（默认）：verify 拒绝 mock 证明，
 *       返回 false 并记录 WARN 日志。生产环境必须保持此默认值。</li>
 *   <li>{@code zk.prover.mock.allow-verify=true}：verify 接受 mock 证明，
 *       仅用于测试环境，会记录 WARN 日志提示。</li>
 *   <li>旧格式 mock 证明（{@code "PROOF|"} 前缀，2.1.0 之前）同样受 allow-verify 控制，
 *       避免旧格式绕过安全检查。</li>
 * </ul>
 * <p><b>原问题</b>：2.1.0 之前 mock 证明 verify 总是返回 true，可被任意伪造，
 * 生产环境误用 mock 后端将导致证明无意义。</p>
 *
 * @since 1.5
 */
@Component
@Primary
public class DefaultZkProofSystem implements ZkProofSystem {

    private static final Logger logger = LoggerFactory.getLogger(DefaultZkProofSystem.class);

    /** witness 编码 magic */
    private static final byte[] WITNESS_MAGIC = "ZWIT".getBytes(StandardCharsets.US_ASCII);

    /** 证明编码 magic */
    private static final byte[] PROOF_MAGIC = "G16P".getBytes(StandardCharsets.US_ASCII);

    /** 远程证明内嵌 fingerprint 定长（字节，A1-R6） */
    private static final int REMOTE_FP_LEN = 32;

    /**
     * mock 证明前缀（ZK-P2-04 修复）。
     *
     * <p>所有 mock 证明以 {@code "MOCK|"} 开头，verify 据此识别 mock 证明并按
     * {@code zk.prover.mock.allow-verify} 配置决定是否通过。前缀不可伪造为
     * 真实 Groth16 证明（{@link #PROOF_MAGIC}）。</p>
     */
    private static final byte[] MOCK_PROOF_PREFIX = "MOCK|".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private TrustedSetup trustedSetup;

    @Autowired
    private ZkProverProperties properties;

    /**
     * ZK 方案 C 集成：真实 Groth16 远程验证服务地址（zk-groth16-service）。
     * 非空时 verify 优先走真实 BN254 配对（Rust arkworks），替代本地 Schnorr 降级；
     * 服务不可用则 fail-closed（返回 false，不降级到 Schnorr/mock）。
     */
    @org.springframework.beans.factory.annotation.Value("${zk.prover.remote-verify-url:}")
    private String remoteVerifyUrl;

    /**
     * ZK 方案 C 集成：真实 Groth16 远程 prove 服务地址（zk-groth16-service）。
     * 非空时 prove 优先走远程真实证明生成，替代本地 Schnorr 降级；
     * 服务不可用则 fail-closed（抛出异常，不降级到 mock）。
     */
    @org.springframework.beans.factory.annotation.Value("${zk.prover.remote-prove-url:}")
    private String remoteProveUrl;

    /** Groth16 证明系统（懒初始化） */
    private volatile Groth16ProofSystem groth16;

    /** 当前激活的 setup 版本号 */
    private volatile int currentSetupVersion = 0;

    @Override
    public int setup(ZkCircuit circuit) {
        if (circuit == null) {
            throw new IllegalArgumentException("circuit cannot be null");
        }
        int constraints = circuit.defineCircuit();
        int version = trustedSetup.registerVersion(circuit.getCircuitId(),
                "groth16-setup-" + Instant.now().toEpochMilli(), 1);
        this.currentSetupVersion = version;

        ZkProverProperties.BackendType backend = properties.resolveBackend();
        if (properties.isEnabled() && backend == ZkProverProperties.BackendType.GROTH16
                && circuit.hasR1cs()) {
            // 真实 Groth16 setup
            ensureGroth16();
            R1csConstraintSystem r1cs = circuit.buildR1cs();
            groth16.setup(circuit.getCircuitId(), r1cs);
            logger.info("DefaultZkProofSystem setup (groth16): circuit={} constraints={} version={}",
                    circuit.getCircuitId(), constraints, version);
        } else {
            logger.info("DefaultZkProofSystem setup ({}): circuit={} constraints={} version={}",
                    backend, circuit.getCircuitId(), constraints, version);
        }
        return version;
    }

    @Override
    public ZkProof prove(ZkCircuit circuit, byte[] witness, ZkPublicInput publicInput) {
        if (circuit == null) {
            throw new IllegalArgumentException("circuit cannot be null");
        }
        int setupVersion = currentSetupVersion > 0 ? currentSetupVersion : trustedSetup.getActiveVersion();
        if (setupVersion < 1) {
            setupVersion = 1;
        }

        ZkProverProperties.BackendType backend = properties.resolveBackend();

        // ZK 方案 C：配置了远程 prove 服务时优先走真实 BN254 配对（fail-closed）
        if (remoteProveUrl != null && !remoteProveUrl.isBlank()) {
            try {
                String circuitJson = R1csToJsonBridge.toJson(circuit.buildR1cs(),
                        buildWitnessFromInputs(circuit, witness, publicInput, circuit.buildR1cs()));
                String[] result = Groth16ProofSystem.proveRemote(remoteProveUrl, circuitJson);
                byte[] proofData = encodeRemoteGroth16Proof(result[0], result[1]);
                ZkProof proof = new ZkProof(proofData, circuit.getCircuitId(),
                        setupVersion, System.currentTimeMillis());
                logger.info("DefaultZkProofSystem prove (REMOTE REAL): circuit={} fingerprint={} proofSize={}",
                        circuit.getCircuitId(), result[0], proof.size());
                return proof;
            } catch (RuntimeException e) {
                logger.error("DefaultZkProofSystem prove REMOTE FAILED (fail-closed): {}", e.getMessage());
                throw new IllegalStateException("ZK remote prove failed: " + e.getMessage(), e);
            }
        }

        if (properties.isEnabled() && backend == ZkProverProperties.BackendType.GROTH16
                && circuit.hasR1cs() && groth16 != null) {
            // 真实 Groth16 prove（本地 Schnorr 降级）
            try {
                Groth16Proof g16Proof = proveGroth16(circuit, witness, publicInput);
                byte[] proofData = encodeGroth16Proof(g16Proof);
                ZkProof proof = new ZkProof(proofData, circuit.getCircuitId(),
                        setupVersion, System.currentTimeMillis());
                logger.info("DefaultZkProofSystem prove (groth16): circuit={} proofSize={}",
                        circuit.getCircuitId(), proof.size());
                return proof;
            } catch (RuntimeException e) {
                logger.warn("DefaultZkProofSystem groth16 prove failed, fallback to mock: {}",
                        e.getMessage());
                // 降级为占位证明
            }
        }

        // 占位证明（mock 模式或降级）
        byte[] proofData = encodeMockProof(circuit, witness, publicInput, setupVersion);
        ZkProof proof = new ZkProof(proofData, circuit.getCircuitId(),
                setupVersion, System.currentTimeMillis());
        logger.info("DefaultZkProofSystem prove (mock): circuit={} proofSize={}",
                circuit.getCircuitId(), proof.size());
        return proof;
    }

    @Override
    public boolean verify(ZkProof proof, ZkPublicInput publicInput) {
        if (proof == null || proof.size() == 0) {
            logger.warn("DefaultZkProofSystem verify: proof null or empty");
            return false;
        }
        if (publicInput == null
                || publicInput.getPreStateRoot() == null
                || publicInput.getPostStateRoot() == null) {
            logger.warn("DefaultZkProofSystem verify: publicInput invalid");
            return false;
        }

        byte[] data = proof.getProofData();
        // 检查是否为 Groth16 证明
        if (isGroth16Proof(data) && groth16 != null) {
            try {
                BigInteger[] publicInputs = extractPublicInputs(publicInput);
                // A1-R6: 远程真实证明（G16P + fingerprint + proofHex）走分离验证 /v1/verify-sep
                if (isRemoteGroth16Proof(data)) {
                    String[] remote = decodeRemoteGroth16Proof(data);
                    if (remoteVerifyUrl == null || remoteVerifyUrl.isBlank()) {
                        logger.warn("DefaultZkProofSystem verify REJECTED: remote proof (fp={}) "
                                + "without remote-verify-url configured (fail-closed)",
                                remote[0]);
                        return false;
                    }
                    boolean remoteValid = groth16.verifyRemoteSep(
                            remoteVerifyUrl, remote[0], remote[1], publicInputs);
                    logger.info("DefaultZkProofSystem verify (groth16 REMOTE SEP): circuit={} fp={} -> {}",
                            proof.getCircuitId(), remote[0], remoteValid);
                    return remoteValid;
                }
                // 本地 Groth16 证明（Schnorr 降级路径）
                Groth16Proof g16Proof = decodeGroth16Proof(data);
                // ZK 方案 C：配置了真实远程验证服务时优先走 BN254 配对（fail-closed）
                if (remoteVerifyUrl != null && !remoteVerifyUrl.isBlank()) {
                    boolean remoteValid = groth16.verifyRemote(remoteVerifyUrl, publicInputs);
                    logger.info("DefaultZkProofSystem verify (groth16 REMOTE REAL): circuit={} -> {}",
                            proof.getCircuitId(), remoteValid);
                    return remoteValid;
                }
                boolean valid = groth16.verify(g16Proof.getCircuitId(), g16Proof, publicInputs);
                logger.info("DefaultZkProofSystem verify (groth16): circuit={} -> {}",
                        proof.getCircuitId(), valid);
                return valid;
            } catch (RuntimeException e) {
                logger.warn("DefaultZkProofSystem groth16 verify failed: {}", e.getMessage());
                return false;
            }
        }

        // ZK-P2-04 修复：mock 证明验证
        // 1. 检查是否为 mock 证明（以 "MOCK|" 开头）
        if (isMockProof(data)) {
            boolean allowVerify = properties.getMock().isAllowVerify();
            if (!allowVerify) {
                // 默认拒绝 mock 证明，防止生产环境误用
                logger.warn("DefaultZkProofSystem verify REJECTED: circuit={} mock proof "
                        + "rejected (zk.prover.mock.allow-verify=false)", proof.getCircuitId());
                return false;
            }
            // 仅测试环境（allow-verify=true）接受 mock 证明
            logger.warn("DefaultZkProofSystem verify (mock ACCEPTED): circuit={} "
                    + "-> VALID (zk.prover.mock.allow-verify=true, test only)",
                    proof.getCircuitId());
            return true;
        }

        // 兼容旧格式 mock 证明（"PROOF|" 前缀，2.1.0 之前）
        // 同样受 allow-verify 控制，避免旧格式绕过安全检查
        if (data.length >= 5 && data[0] == 'P' && data[1] == 'R' && data[2] == 'O'
                && data[3] == 'O' && data[4] == 'F') {
            boolean allowVerify = properties.getMock().isAllowVerify();
            if (!allowVerify) {
                logger.warn("DefaultZkProofSystem verify REJECTED: circuit={} legacy mock proof "
                        + "rejected (zk.prover.mock.allow-verify=false)", proof.getCircuitId());
                return false;
            }
            logger.warn("DefaultZkProofSystem verify (legacy mock ACCEPTED): circuit={} "
                    + "-> VALID (zk.prover.mock.allow-verify=true, test only)",
                    proof.getCircuitId());
            return true;
        }

        logger.warn("DefaultZkProofSystem verify: unknown proof format");
        return false;
    }

    @Override
    public String getName() {
        ZkProverProperties.BackendType backend = properties.resolveBackend();
        if (!properties.isEnabled()) {
            return "disabled";
        }
        switch (backend) {
            case GROTH16: return "groth16-bc";
            case PLONK: return "plonk(frozen->groth16)";
            case HALO2: return "halo2(frozen->groth16)";
            case MOCK: return "mock";
            default: return "groth16-bc";
        }
    }

    // ==================== Groth16 集成 ====================

    private void ensureGroth16() {
        if (groth16 == null) {
            synchronized (this) {
                if (groth16 == null) {
                    groth16 = new Groth16ProofSystem();
                }
            }
        }
    }

    private Groth16Proof proveGroth16(ZkCircuit circuit, byte[] witness, ZkPublicInput publicInput) {
        R1csConstraintSystem r1cs = circuit.buildR1cs();
        BigInteger[] fullWitness = buildWitnessFromInputs(circuit, witness, publicInput, r1cs);
        return groth16.prove(circuit.getCircuitId(), r1cs, fullWitness);
    }

    /**
     * 从 witness byte[] 和公共输入构造完整 R1CS witness 向量（A1-R3：BigInteger 域）。
     *
     * <p>针对 RollupStateTransitionCircuit 优化：解析 witness 中的 txEffects，
     * 结合公共输入（preStateRoot, postStateRoot, batchDataHash）构造完整 witness。
     * 状态根经 {@link RollupStateTransitionCircuit#hashToBigInteger} 映射为 256 位
     * BigInteger，无 long 截断（ZK-P2-01 关闭）。</p>
     */
    private BigInteger[] buildWitnessFromInputs(ZkCircuit circuit, byte[] witness,
                                                ZkPublicInput publicInput,
                                                R1csConstraintSystem r1cs) {
        // 提取公共输入（256 位 BigInteger）
        BigInteger preStateRoot = RollupStateTransitionCircuit.hashToBigInteger(publicInput.getPreStateRoot());
        BigInteger postStateRoot = RollupStateTransitionCircuit.hashToBigInteger(publicInput.getPostStateRoot());
        BigInteger batchDataHash = RollupStateTransitionCircuit.hashToBigInteger(publicInput.getBatchDataHash());

        // 解析 witness 中的 txEffects
        long[] txEffects = decodeWitnessEffects(witness);

        // 若电路是 RollupStateTransitionCircuit，使用其 BigInteger witness 构造方法
        if (circuit instanceof RollupStateTransitionCircuit) {
            RollupStateTransitionCircuit rollupCircuit = (RollupStateTransitionCircuit) circuit;
            // 调整 txEffects 长度以匹配 maxBatchSize（缺失补 0，由构造方法处理）
            int maxBatch = rollupCircuit.getMaxBatchSize();
            long[] adjusted = new long[Math.min(txEffects.length, maxBatch)];
            System.arraycopy(txEffects, 0, adjusted, 0, adjusted.length);
            return rollupCircuit.buildWitnessBigIntegerArray(preStateRoot, postStateRoot, 0L, adjusted);
        }

        // 通用回退：直接拼装（BigInteger）
        BigInteger[] publicInputs = {preStateRoot, postStateRoot, batchDataHash};
        int numPrivate = r1cs.getNumPrivate();
        BigInteger[] privateWitness = new BigInteger[numPrivate];
        int copyLen = Math.min(txEffects.length, numPrivate);
        for (int i = 0; i < copyLen; i++) {
            privateWitness[i] = BigInteger.valueOf(txEffects[i]);
        }
        for (int i = copyLen; i < numPrivate; i++) {
            privateWitness[i] = BigInteger.ZERO;
        }
        return r1cs.buildWitness(publicInputs, privateWitness);
    }

    /**
     * 解码 witness byte[] 为 txEffects long 数组。
     *
     * <p>格式：[magic(4)="ZWIT"] [numEffects(4)] [effect_0(8)] ... [effect_{n-1}(8)]</p>
     */
    private long[] decodeWitnessEffects(byte[] witness) {
        if (witness == null || witness.length < 8) {
            return new long[0];
        }
        // 检查 magic
        if (witness.length >= 8
                && witness[0] == WITNESS_MAGIC[0] && witness[1] == WITNESS_MAGIC[1]
                && witness[2] == WITNESS_MAGIC[2] && witness[3] == WITNESS_MAGIC[3]) {
            int numEffects = readIntLE(witness, 4);
            if (numEffects <= 0 || 8 + numEffects * 8L > witness.length) {
                return new long[0];
            }
            long[] effects = new long[numEffects];
            for (int i = 0; i < numEffects; i++) {
                effects[i] = readLongLE(witness, 8 + i * 8);
            }
            return effects;
        }
        // 回退：将 witness 字节直接转为 long 数组（每 8 字节一个）
        int n = witness.length / 8;
        long[] effects = new long[n];
        for (int i = 0; i < n; i++) {
            effects[i] = readLongLE(witness, i * 8);
        }
        return effects;
    }

    private BigInteger[] extractPublicInputs(ZkPublicInput publicInput) {
        BigInteger pre = RollupStateTransitionCircuit.hashToBigInteger(publicInput.getPreStateRoot());
        BigInteger post = RollupStateTransitionCircuit.hashToBigInteger(publicInput.getPostStateRoot());
        BigInteger hash = RollupStateTransitionCircuit.hashToBigInteger(publicInput.getBatchDataHash());
        return new BigInteger[]{pre, post, hash};
    }

    // ==================== 证明编码/解码 ====================

    private byte[] encodeGroth16Proof(Groth16Proof proof) {
        byte[] g16Bytes = proof.encode();
        byte[] result = new byte[PROOF_MAGIC.length + g16Bytes.length];
        System.arraycopy(PROOF_MAGIC, 0, result, 0, PROOF_MAGIC.length);
        System.arraycopy(g16Bytes, 0, result, PROOF_MAGIC.length, g16Bytes.length);
        return result;
    }

    /**
     * 编码远程真实 Groth16 证明（ZK 方案 C：Rust 服务产出）。
     *
     * <p>格式："G16P" + fingerprint(32字节, ASCII 左对齐补零) + proof_hex 字节</p>
     */
    private byte[] encodeRemoteGroth16Proof(String fingerprint, String proofHex) {
        byte[] proofBytes = hexStringToByteArray(proofHex);
        byte[] fpBytes = new byte[REMOTE_FP_LEN];
        if (fingerprint != null) {
            byte[] fpRaw = fingerprint.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(fpRaw, 0, fpBytes, 0, Math.min(fpRaw.length, REMOTE_FP_LEN));
        }
        byte[] result = new byte[PROOF_MAGIC.length + fpBytes.length + proofBytes.length];
        System.arraycopy(PROOF_MAGIC, 0, result, 0, PROOF_MAGIC.length);
        System.arraycopy(fpBytes, 0, result, PROOF_MAGIC.length, fpBytes.length);
        System.arraycopy(proofBytes, 0, result, PROOF_MAGIC.length + fpBytes.length, proofBytes.length);
        return result;
    }

    /**
     * 判断是否为远程真实 Groth16 证明（"G16P" + fingerprint(32) + proofHex 结构）。
     *
     * <p>与本地证明（"G16P" + Groth16Proof 编码，magic "G16"）区分：
     * 远程证明 fingerprint 区为 hex 字符（0-9a-f），首字节不可能是 'G'（0x47）；
     * 本地证明首字节恒为 'G'。</p>
     */
    private boolean isRemoteGroth16Proof(byte[] data) {
        if (!isGroth16Proof(data) || data.length < PROOF_MAGIC.length + REMOTE_FP_LEN) {
            return false;
        }
        return data[PROOF_MAGIC.length] != 'G';
    }

    /**
     * 解码远程真实 Groth16 证明：返回 {fingerprint, proofHex}。
     */
    private String[] decodeRemoteGroth16Proof(byte[] data) {
        int fpStart = PROOF_MAGIC.length;
        int proofStart = fpStart + REMOTE_FP_LEN;
        byte[] fpBytes = new byte[REMOTE_FP_LEN];
        System.arraycopy(data, fpStart, fpBytes, 0, REMOTE_FP_LEN);
        // 截断尾部空白/零填充
        int fpLen = 0;
        while (fpLen < REMOTE_FP_LEN && fpBytes[fpLen] != 0 && fpBytes[fpLen] != ' ') {
            fpLen++;
        }
        String fingerprint = new String(fpBytes, 0, fpLen, StandardCharsets.US_ASCII);
        byte[] proofBytes = new byte[data.length - proofStart];
        System.arraycopy(data, proofStart, proofBytes, 0, proofBytes.length);
        return new String[]{fingerprint, byteArrayToHex(proofBytes)};
    }

    /**
     * 字节数组转 Hex 字符串（小写，无 0x 前缀）。
     */
    private static String byteArrayToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Hex 字符串转字节数组（替代 web3j Numeric.hexStringToByteArray）。
     */
    private static byte[] hexStringToByteArray(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        // 移除 0x 前缀
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private boolean isGroth16Proof(byte[] data) {
        if (data == null || data.length < PROOF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PROOF_MAGIC.length; i++) {
            if (data[i] != PROOF_MAGIC[i]) return false;
        }
        return true;
    }

    private Groth16Proof decodeGroth16Proof(byte[] data) {
        byte[] g16Bytes = new byte[data.length - PROOF_MAGIC.length];
        System.arraycopy(data, PROOF_MAGIC.length, g16Bytes, 0, g16Bytes.length);
        return decodeGroth16ProofBytes(g16Bytes);
    }

    private Groth16Proof decodeGroth16ProofBytes(byte[] bytes) {
        // 使用 Groth16Proof.decode 支持 v1/v2 格式
        // v1: [magic(3)="G16"] [version(1)=0x01] [circuitIdLen(2)] [circuitId] [A] [B] [C]
        // v2: [magic(3)="G16"] [version(1)=0x02] [circuitIdLen(2)] [circuitId] [A] [B] [C] [r1csProof]
        return Groth16Proof.decode(bytes);
    }

    /**
     * 编码 mock 证明（ZK-P2-04 修复）。
     *
     * <p>mock 证明以 {@code "MOCK|"} 前缀开头，明确标记为非真实证明。
     * verify 据此识别并按 {@code zk.prover.mock.allow-verify} 配置决定是否通过。</p>
     *
     * <p>格式：{@code "MOCK|" + circuitId + "|v" + setupVersion + "|" + publicInput + "|witnessLen=" + n}</p>
     */
    private byte[] encodeMockProof(ZkCircuit circuit, byte[] witness,
                                   ZkPublicInput publicInput, int setupVersion) {
        String placeholder = "MOCK|" + circuit.getCircuitId() + "|v" + setupVersion
                + "|" + (publicInput == null ? "null" : publicInput.toString())
                + "|witnessLen=" + (witness == null ? 0 : witness.length);
        return placeholder.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 检查证明数据是否为 mock 证明（以 {@link #MOCK_PROOF_PREFIX} 开头）。
     *
     * @param data 证明数据
     * @return 是 mock 证明返回 true
     */
    private boolean isMockProof(byte[] data) {
        if (data == null || data.length < MOCK_PROOF_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < MOCK_PROOF_PREFIX.length; i++) {
            if (data[i] != MOCK_PROOF_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    // ==================== 字节工具 ====================

    private static int readIntLE(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long readLongLE(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static int readUShortLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    /**
     * 将 txEffects long 数组编码为 witness byte[]（供上层调用）。
     *
     * @param txEffects 交易效果数组
     * @return 编码的 witness 字节
     */
    public static byte[] encodeWitness(long[] txEffects) {
        int numEffects = txEffects == null ? 0 : txEffects.length;
        ByteBuffer buf = ByteBuffer.allocate(8 + numEffects * 8)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.put(WITNESS_MAGIC);
        buf.putInt(numEffects);
        if (txEffects != null) {
            for (long e : txEffects) {
                buf.putLong(e);
            }
        }
        return buf.array();
    }
}