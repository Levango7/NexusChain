package org.nexus.l2.zk;

/**
 * ZK 证明系统抽象接口。
 *
 * <p>统一抽象 ZK 证明系统的三个核心阶段：</p>
 * <ol>
 *   <li>{@link #setup}：可信设置（绑定电路生成 proving/verifying key）</li>
 *   <li>{@link #prove}：证明生成（结合 witness、public inputs、circuit 生成证明）</li>
 *   <li>{@link #verify}：证明验证（结合 proof、public inputs、setup 验证有效性）</li>
 * </ol>
 *
 * <h3>未来接入</h3>
 * <p>本接口设计为可接入真实 ZK 库：</p>
 * <ul>
 *   <li><b>halo2</b>：实现 {@code Halo2ProofSystem}，setup 调用 halo2 的 keygen，
 *       prove 调用 create_proof，verify 调用 verify_proof</li>
 *   <li><b>Plonk</b>：实现 {@code PlonkProofSystem}，setup 调用 plonk 的 setup，
 *       prove/verify 对应 plonk 的 prove/verify</li>
 *   <li><b>Groth16</b>：实现 {@code Groth16ProofSystem}，需每个电路独立 setup</li>
 * </ul>
 *
 * <h3>骨架说明</h3>
 * <p>当前 {@link org.nexus.l2.zk.ZkProver} 与 {@link org.nexus.l2.zk.ZkVerifier}
 * 是骨架实现，prove 返回占位证明，verify 始终返回 true（仅校验非空）。
 * 真实接入时替换为对应 ZK 库绑定即可，无需改动上层 {@link org.nexus.l2.ZkRollup}。</p>
 *
 * @since 1.5
 */
public interface ZkProofSystem {

    /**
     * 可信设置：为指定电路生成 proving/verifying key 并注册到 {@link TrustedSetup}。
     *
     * @param circuit 目标电路
     * @return 注册的 setup 版本号
     */
    int setup(ZkCircuit circuit);

    /**
     * 生成证明。
     *
     * @param circuit     目标电路
     * @param witness     私密见证
     * @param publicInput 公共输入
     * @return ZK 证明
     */
    ZkProof prove(ZkCircuit circuit, byte[] witness, ZkPublicInput publicInput);

    /**
     * 验证证明。
     *
     * @param proof       ZK 证明
     * @param publicInput 公共输入
     * @return 验证通过返回 true
     */
    boolean verify(ZkProof proof, ZkPublicInput publicInput);

    /**
     * 返回证明系统名称（如 "halo2"、"plonk"、"groth16"、"skeleton"）。
     *
     * @return 证明系统名称
     */
    String getName();
}