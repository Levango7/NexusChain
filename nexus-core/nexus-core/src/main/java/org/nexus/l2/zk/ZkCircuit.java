package org.nexus.l2.zk;

import java.util.List;

/**
 * ZK 电路定义抽象接口。
 *
 * <p>一个 ZK 电路定义了一组约束（constraints），证明方需提供满足约束的 witness，
 * 验证方仅需检查约束成立而不知 witness 内容。本接口抽象电路定义与综合两个阶段，
 * 便于未来接入真实电路编译器（如 halo2 的 Circuit trait、Plonk 的 constraint system）。</p>
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li>{@link #defineCircuit}：声明电路的约束（门、连线、范围检查等）</li>
 *   <li>{@link #synthesize}：将具体 witness 代入电路，生成满足所有约束的赋值</li>
 *   <li>赋值交给 {@link ZkProver} 生成 {@link ZkProof}</li>
 * </ol>
 *
 * <h3>骨架说明</h3>
 * <p>当前为骨架接口，{@code defineCircuit} 与 {@code synthesize} 返回占位结果。
 * 真实实现需绑定具体 ZK 库（halo2、Plonk 等）的电路表达。</p>
 *
 * @since 1.5
 */
public interface ZkCircuit {

    /**
     * 返回电路唯一标识。
     *
     * @return 电路 ID
     */
    String getCircuitId();

    /**
     * 定义电路约束。
     *
     * <p>声明电路中所有约束（门、连线、范围检查等），返回约束数量。
     * 真实实现中此步骤会构建 constraint system 并绑定到可信设置。</p>
     *
     * @return 约束数量
     */
    int defineCircuit();

    /**
     * 综合电路：将 witness 代入电路生成赋值。
     *
     * <p>真实实现中此步骤会执行 witness assignment，验证所有约束成立，
     * 输出可被 prover 使用的 assignment 数据。骨架实现返回占位字节数组。</p>
     *
     * @param witness 私密见证（字节编码）
     * @return 电路赋值（字节编码）
     */
    byte[] synthesize(byte[] witness);

    /**
     * 返回电路的公共输入描述（字段名列表）。
     *
     * <p>用于指导 {@link ZkPublicInput} 的构造，确保 prover/verifier 双方
     * 对公共输入字段达成一致。</p>
     *
     * @return 公共输入字段名列表
     */
    List<String> getPublicInputSchema();
}