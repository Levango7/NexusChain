package org.nexus.bridge;

import org.nexus.bridge.model.BridgeTransaction;

/**
 * 桥服务主接口，定义跨链桥的核心操作。
 *
 * <p>本接口是 NexusChain 跨链桥模块的入口点，封装了 Lock-Mint 模式下
 * 的四种核心跨链操作：锁定、铸造、销毁、解锁。</p>
 *
 * <h2>操作流程</h2>
 *
 * <h3>正向跨链（原链 → 目标链）</h3>
 * <pre>
 *   用户原链发起 LOCK ──► 桥验证者确认 ──► 目标链执行 MINT
 * </pre>
 *
 * <h3>反向跨链（目标链 → 原链）</h3>
 * <pre>
 *   用户目标链发起 BURN ──► 桥验证者确认 ──► 原链执行 UNLOCK
 * </pre>
 *
 * <h2>安全约束</h2>
 * <ul>
 *   <li>所有操作受 {@link BridgeState} 状态机约束</li>
 *   <li>MINT 和 UNLOCK 需达到验证者多签阈值</li>
 *   <li>单笔金额不得超过 {@link BridgeConfig} 配置的上限</li>
 *   <li>24 小时累计流出不得超过日限额</li>
 * </ul>
 *
 * @see LockRequest
 * @see MintRequest
 * @see BridgeConfig
 * @see BridgeTransaction
 * @since 1.0.0
 */
public interface BridgeService {

    /**
     * 在原链锁定 NEX 代币（BRIDGE_LOCK）。
     *
     * <p>用户在源链调用桥合约，将指定数量的 NEX 锁定到托管地址。
     * 锁定成功后生成桥交易记录，等待验证者确认后在目标链铸造。</p>
     *
     * @param request 锁定请求，包含源链、目标链、金额、用户地址等信息
     * @return 桥交易记录
     * @throws BridgeException 如果桥处于 PAUSED 或 EMERGENCY_STOP 状态，
     *                         或金额超过单笔上限，或日限额不足
     */
    BridgeTransaction lock(LockRequest request);

    /**
     * 在目标链铸造 NEX 代币（BRIDGE_MINT）。
     *
     * <p>验证者确认源链锁定交易后，在目标链铸造等量 NEX 给用户。
     * 此操作需达到验证者多签阈值方可执行。</p>
     *
     * @param request 铸造请求，包含锁定交易引用、验证者签名集合等信息
     * @return 更新后的桥交易记录（状态为 MINTED）
     * @throws BridgeException 如果桥非 ACTIVE 状态、签名不足、
     *                         或锁定交易不存在 / 已被铸造
     */
    BridgeTransaction mint(MintRequest request);

    /**
     * 在目标链销毁 NEX 代币（BRIDGE_BURN）。
     *
     * <p>用户在目标链调用桥合约销毁指定数量的 NEX，发起反向跨链。
     * 销毁成功后生成桥交易记录，等待验证者确认后在原链解锁。</p>
     *
     * @param request 销毁请求，包含源链、目标链、金额、用户地址等信息
     * @return 桥交易记录
     * @throws BridgeException 如果桥处于 EMERGENCY_STOP 状态，
     *                         或金额超过单笔上限，或日限额不足
     */
    BridgeTransaction burn(BurnRequest request);

    /**
     * 在原链解锁 NEX 代币（BRIDGE_UNLOCK）。
     *
     * <p>验证者确认目标链销毁交易后，在原链解锁等量 NEX 给用户。
     * 此操作需达到验证者多签阈值方可执行。</p>
     *
     * <p>当桥处于 PAUSED 状态时，仅允许执行此操作，以保护用户资产。</p>
     *
     * @param request 解锁请求，包含销毁交易引用、验证者签名集合等信息
     * @return 更新后的桥交易记录（状态为 UNLOCKED）
     * @throws BridgeException 如果签名不足，或销毁交易不存在 / 已被解锁
     */
    BridgeTransaction unlock(UnlockRequest request);

    /**
     * 根据桥交易 ID 查询交易详情。
     *
     * @param txId 桥交易 ID
     * @return 桥交易记录，不存在时返回 {@code null}
     */
    BridgeTransaction getTransaction(String txId);

    /**
     * 根据源链交易哈希查询桥交易。
     *
     * <p>用于跨链操作的对账与追溯，通过源链交易哈希定位
     * 对应的桥交易记录。</p>
     *
     * @param sourceTxHash 源链交易哈希
     * @return 桥交易记录，不存在时返回 {@code null}
     */
    BridgeTransaction getTransactionBySourceHash(String sourceTxHash);

    /**
     * 查询桥当前运行状态与限额使用情况。
     *
     * @return 桥状态信息
     */
    BridgeStatus getStatus();

    /**
     * 验证者暂停桥，将状态切换为 PAUSED。
     *
     * <p>暂停后仅允许 {@code BRIDGE_UNLOCK} 操作。
     * 任何验证者均可发起暂停，无需多签。</p>
     *
     * @param validatorId 发起暂停的验证者 ID
     * @throws BridgeException 如果桥已处于 EMERGENCY_STOP 状态
     */
    void pause(String validatorId);

    /**
     * 验证者恢复桥，将状态从 PAUSED 切换回 ACTIVE。
     *
     * <p>恢复操作需达到验证者多签阈值。</p>
     *
     * @param validatorIds 参与恢复的验证者 ID 集合
     * @throws BridgeException 如果桥当前非 PAUSED 状态，或签名不足
     */
    void resume(java.util.Set<String> validatorIds);
}
