// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

/**
 * @title L2Bridge
 * @author NexusChain
 * @notice 简化的 L2↔L1 桥合约，用于 NexusChain L2 Rollup 端到端测试。
 * @dev 部署在 L1（Hardhat 本地节点）上，由 Java 侧 Web3jL1ContractClient 通过 JSON-RPC 调用。
 *
 * 核心函数（与 Java 侧 L1ContractClient 一一对应）：
 * - submitStateRoot(bytes32 stateRoot, uint256 batchId) — 提交状态根
 * - markBatchVerified(uint256 batchId) — 标记批次验证通过
 * - finalizeWithdraws(uint256 batchId) — 最终化提现
 * - challengeBatch(uint256 batchId, bytes32[] proof) — 挑战批次
 *
 * 事件：
 * - StateRootSubmitted — 状态根提交
 * - BatchVerified — 批次验证通过
 * - WithdrawFinalized — 提款最终化
 * - BatchChallenged — 批次被挑战
 *
 * 简化实现，仅满足端到端测试需求：
 * - 不实现完整的 Merkle 验证（challengeBatch 仅记录 proof 哈希）
 * - 不实现挑战期时间锁（markBatchVerified 可立即调用）
 * - 不实现签名验证（所有调用者均可操作）
 * - 不实现 ERC20 资产转移（finalizeWithdraws 仅置标志位）
 */
contract L2Bridge {
    // ==================== 事件 ====================

    /// @notice 状态根提交到 L1
    /// @param batchId   批次 ID
    /// @param stateRoot 提交的状态根
    /// @param submitter 提交者地址
    event StateRootSubmitted(uint256 indexed batchId, bytes32 stateRoot, address submitter);

    /// @notice 批次被标记为 VERIFIED
    /// @param batchId 批次 ID
    /// @param by      标记者地址
    event BatchVerified(uint256 indexed batchId, address by);

    /// @notice 批次提款被最终化
    /// @param batchId 批次 ID
    /// @param by      触发者地址
    event WithdrawFinalized(uint256 indexed batchId, address by);

    /// @notice 批次被挑战
    /// @param batchId    批次 ID
    /// @param challenger 挑战者地址
    /// @param proofHash  挑战证明的哈希
    event BatchChallenged(uint256 indexed batchId, address challenger, bytes32 proofHash);

    // ==================== 状态 ====================

    /// @notice 批次状态枚举
    enum BatchStatus {
        /// @dev 未提交
        NONE,
        /// @dev 已提交状态根，待验证
        SUBMITTED,
        /// @dev 已验证
        VERIFIED,
        /// @dev 提款已最终化
        FINALIZED,
        /// @dev 被挑战（无效）
        CHALLENGED
    }

    /// @notice 批次 ID -> 批次状态
    mapping(uint256 => BatchStatus) public batchStatus;

    /// @notice 批次 ID -> 提交的状态根
    mapping(uint256 => bytes32) public batchStateRoot;

    /// @notice 批次 ID -> 提交者地址
    mapping(uint256 => address) public batchSubmitter;

    /// @notice 批次 ID -> 挑战者地址
    mapping(uint256 => address) public batchChallenger;

    /// @notice 批次 ID -> 挑战证明哈希
    mapping(uint256 => bytes32) public batchChallengeProofHash;

    /// @notice 挑战期（秒），用于未来扩展（当前未强制）
    uint256 public challengePeriod;

    /// @notice 合约部署者
    address public owner;

    // ==================== 修饰符 ====================

    modifier onlyExistingBatch(uint256 batchId) {
        require(
            batchStatus[batchId] != BatchStatus.NONE,
            "L2Bridge: batch not submitted"
        );
        _;
    }

    // ==================== 构造函数 ====================

    /**
     * @notice 构造函数
     * @param _challengePeriod 挑战期（秒）
     */
    constructor(uint256 _challengePeriod) {
        owner = msg.sender;
        challengePeriod = _challengePeriod;
    }

    // ==================== 核心函数 ====================

    /**
     * @notice 提交状态根到 L1。
     * @dev 同一 batchId 重复提交将覆盖原状态根（简化语义，便于测试）。
     * @param stateRoot L2 状态根
     * @param batchId   批次 ID
     */
    function submitStateRoot(bytes32 stateRoot, uint256 batchId) external {
        batchStateRoot[batchId] = stateRoot;
        batchSubmitter[batchId] = msg.sender;
        batchStatus[batchId] = BatchStatus.SUBMITTED;

        emit StateRootSubmitted(batchId, stateRoot, msg.sender);
    }

    /**
     * @notice 标记批次为 VERIFIED。
     * @dev 要求批次已提交状态根（SUBMITTED 状态）。挑战后的批次无法被验证。
     * @param batchId 批次 ID
     */
    function markBatchVerified(uint256 batchId)
        external
        onlyExistingBatch(batchId)
    {
        require(
            batchStatus[batchId] == BatchStatus.SUBMITTED,
            "L2Bridge: batch not in SUBMITTED state"
        );
        require(
            batchStatus[batchId] != BatchStatus.CHALLENGED,
            "L2Bridge: batch already challenged"
        );

        batchStatus[batchId] = BatchStatus.VERIFIED;
        emit BatchVerified(batchId, msg.sender);
    }

    /**
     * @notice 最终化批次提款。
     * @dev 要求批次已 VERIFIED。
     * @param batchId 批次 ID
     */
    function finalizeWithdraws(uint256 batchId)
        external
        onlyExistingBatch(batchId)
    {
        require(
            batchStatus[batchId] == BatchStatus.VERIFIED,
            "L2Bridge: batch not VERIFIED"
        );

        batchStatus[batchId] = BatchStatus.FINALIZED;
        emit WithdrawFinalized(batchId, msg.sender);
    }

    /**
     * @notice 挑战批次（提交欺诈证明）。
     * @dev 简化实现：仅记录挑战者与证明哈希，将批次标记为 CHALLENGED。
     *   要求批次已提交状态根且未被挑战过。
     * @param batchId 批次 ID
     * @param proof   欺诈证明（bytes32 数组，简化为 Merkle 路径）
     */
    function challengeBatch(uint256 batchId, bytes32[] calldata proof)
        external
        onlyExistingBatch(batchId)
    {
        require(
            batchStatus[batchId] != BatchStatus.CHALLENGED,
            "L2Bridge: batch already challenged"
        );
        require(
            batchStatus[batchId] != BatchStatus.VERIFIED,
            "L2Bridge: batch already VERIFIED"
        );
        require(
            batchStatus[batchId] != BatchStatus.FINALIZED,
            "L2Bridge: batch already FINALIZED"
        );
        require(proof.length > 0, "L2Bridge: empty proof");

        // 计算证明哈希（keccak256 of concatenated proof elements）
        bytes32 proofHash = keccak256(abi.encodePacked(proof));

        batchChallenger[batchId] = msg.sender;
        batchChallengeProofHash[batchId] = proofHash;
        batchStatus[batchId] = BatchStatus.CHALLENGED;

        emit BatchChallenged(batchId, msg.sender, proofHash);
    }

    // ==================== View 函数 ====================

    /**
     * @notice 查询批次状态。
     * @param batchId 批次 ID
     * @return 批次状态枚举值
     */
    function getBatchStatus(uint256 batchId) external view returns (BatchStatus) {
        return batchStatus[batchId];
    }

    /**
     * @notice 查询批次是否已被挑战。
     * @param batchId 批次 ID
     * @return true 表示已被挑战
     */
    function isBatchChallenged(uint256 batchId) external view returns (bool) {
        return batchStatus[batchId] == BatchStatus.CHALLENGED;
    }

    /**
     * @notice 查询批次是否已 VERIFIED。
     * @param batchId 批次 ID
     * @return true 表示已 VERIFIED
     */
    function isBatchVerified(uint256 batchId) external view returns (bool) {
        return batchStatus[batchId] == BatchStatus.VERIFIED;
    }

    /**
     * @notice 查询批次提款是否已最终化。
     * @param batchId 批次 ID
     * @return true 表示已 FINALIZED
     */
    function isWithdrawsFinalized(uint256 batchId) external view returns (bool) {
        return batchStatus[batchId] == BatchStatus.FINALIZED;
    }
}
