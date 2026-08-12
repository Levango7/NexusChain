package org.nexus.oracle.governance.execution;

import org.nexus.oracle.governance.Proposal;

/**
 * 验证者集变更端口（治理→验证者集连接轴，ADR-030 第三条轴）。
 *
 * <p>由 nexus-core 在启动时注入真实实现（桥接 {@code ValidatorRegistry}），
 * oracle 模块只依赖此端口，避免对共识层的反向依赖。</p>
 *
 * <p>参数约定（提案 parameters）：</p>
 * <ul>
 *   <li>{@code action}: "add" | "remove"</li>
 *   <li>{@code validatorAddress}: 验证者地址</li>
 *   <li>{@code publicKey}: （add 时必填）验证者公钥 hex</li>
 *   <li>{@code stakeAmount}: （add 时必填）初始质押金额</li>
 * </ul>
 */
public interface ValidatorSetPort {

    /**
     * 执行验证者集变更。
     *
     * @param proposal VALIDATOR_SET_CHANGE 类型提案
     * @return 执行结果
     */
    ExecutionResult apply(Proposal proposal);

    /**
     * 执行结果。
     */
    record ExecutionResult(boolean success, String message) {
        public static ExecutionResult ok(String msg) { return new ExecutionResult(true, msg); }
        public static ExecutionResult fail(String msg) { return new ExecutionResult(false, msg); }
    }
}
