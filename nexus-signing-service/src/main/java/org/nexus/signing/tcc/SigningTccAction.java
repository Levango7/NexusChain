package org.nexus.signing.tcc;

import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

import java.math.BigDecimal;

/**
 * 签名服务 TCC 接口（TwoPhaseBusinessAction）。
 *
 * <p>Phase 3 任务 #62：signing-service 作为 Seata TCC 分支事务参与方，
 * 与 gateway 侧 AT 模式混合，实现签名+广播的分布式事务一致性（设计文档 §4.2.4）。</p>
 *
 * <p>三阶段语义：
 * <ul>
 *   <li><b>Try</b>（{@link #prepareSignTransfer}）：预锁定 nonce（从 {@code NoncePool}
 *       获取并标记为 LOCKED），不签名不广播。nonce 获取失败则抛异常触发全局回滚。</li>
 *   <li><b>Confirm</b>（{@link #confirmSignTransfer}）：使用 Try 锁定的 nonce 执行
 *       签名 + 广播，释放 nonce 锁定（标记为 USED）。返回交易哈希。</li>
 *   <li><b>Cancel</b>（{@link #cancelSignTransfer}）：释放 nonce 锁定（标记为 AVAILABLE），
 *       nonce 未被使用，可被后续事务获取。计日志。</li>
 * </ul></p>
 *
 * <p>Seata 2.0.0 API：{@code @LocalTCC} 标注本地 TCC 接口，
 * {@code @TwoPhaseBusinessAction} 标注 Try 方法并声明 commit/rollback 方法名
 * （Seata 2.0.0 实际注解为 {@link TwoPhaseBusinessAction}，非 design.md 笔误的
 * {@code TccActionMethod}——后者属 Seata 2.1+ API）。</p>
 *
 * <p>幂等性：Confirm/Cancel 可能被 Seata TM 重试，实现需保证幂等
 * （{@code NoncePool.confirmNonce/cancelNonce} 已处理无锁定记录场景）。</p>
 */
@LocalTCC
public interface SigningTccAction {

    /**
     * Try 阶段：预锁定 nonce。
     *
     * <p>从 {@code NoncePool} 获取 nonce（maxNonce 或 RPC getNonce）并标记为 LOCKED，
     * 把 {@code fromPubkey/toPubkeyHash/amount/nonce/address} 写入
     * {@link BusinessActionContext} 供 Confirm/Cancel 使用。不签名不广播。</p>
     *
     * @param actionContext Seata 注入的业务上下文（Try 阶段可 addActionContext）
     * @param fromPubkey    转出方公钥（必须匹配平台 keystore 公钥）
     * @param toPubkeyHash  转入方公钥哈希
     * @param amount        转账金额
     * @return true 表示 Try 成功；false 或抛异常表示 Try 失败（全局事务回滚）
     */
    @TwoPhaseBusinessAction(
            name = "signTransferTcc",
            commitMethod = "confirmSignTransfer",
            rollbackMethod = "cancelSignTransfer"
    )
    boolean prepareSignTransfer(BusinessActionContext actionContext,
                                String fromPubkey,
                                String toPubkeyHash,
                                BigDecimal amount);

    /**
     * Confirm 阶段：签名 + 广播 + 释放 nonce 锁定。
     *
     * <p>从 {@link BusinessActionContext} 获取 Try 阶段锁定的 nonce 及转账参数，
     * 执行 {@code TxUtils.ClientToTransferAccount} 签名 + {@code NodeController.sendTransaction}
     * 广播，调用 {@code NoncePool.confirmNonce} 释放锁定（标记为 USED）。</p>
     *
     * @param actionContext Seata 注入的业务上下文（含 Try 阶段写入的参数）
     * @return true 表示 Confirm 成功
     */
    boolean confirmSignTransfer(BusinessActionContext actionContext);

    /**
     * Cancel 阶段：释放 nonce 锁定。
     *
     * <p>从 {@link BusinessActionContext} 获取 Try 阶段锁定的 nonce，
     * 调用 {@code NoncePool.cancelNonce} 释放锁定（标记为 AVAILABLE）。
     * nonce 未被使用，可被后续事务获取。</p>
     *
     * @param actionContext Seata 注入的业务上下文（含 Try 阶段写入的参数）
     * @return true 表示 Cancel 成功
     */
    boolean cancelSignTransfer(BusinessActionContext actionContext);
}