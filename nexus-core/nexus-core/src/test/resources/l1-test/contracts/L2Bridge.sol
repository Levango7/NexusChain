// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

/**
 * @title MerkleLib
 * @author NexusChain
 * @notice Merkle 树验证辅助库，支持按位置（左/右）验证 Merkle 路径。
 * @dev 用于 L2Bridge 的欺诈证明验证与提款 Merkle 路径验证。
 *   采用 keccak256(abi.encodePacked(left, right)) 的二叉哈希方案。
 */
library MerkleLib {
    /**
     * @notice 验证 Merkle Proof。
     * @param root     Merkle 根
     * @param leaf     叶子节点哈希
     * @param proof    Merkle 路径（每层的兄弟节点哈希）
     * @param isRight  每层中 leaf 是否在右侧（true=右，false=左）
     * @return true 表示 proof 验证通过且 computed root 与给定 root 一致
     */
    function verifyMerkleProof(
        bytes32 root,
        bytes32 leaf,
        bytes32[] memory proof,
        bool[] memory isRight
    ) internal pure returns (bool) {
        require(proof.length == isRight.length, "MerkleLib: length mismatch");
        bytes32 computed = leaf;
        for (uint256 i = 0; i < proof.length; i++) {
            if (isRight[i]) {
                // leaf 在右侧，proof 兄弟在左侧
                computed = keccak256(abi.encodePacked(proof[i], computed));
            } else {
                // leaf 在左侧，proof 兄弟在右侧
                computed = keccak256(abi.encodePacked(computed, proof[i]));
            }
        }
        return computed == root;
    }

    /**
     * @notice 根据 leaf 与 proof 计算 Merkle 根（不验证，仅计算）。
     * @param leaf     叶子节点哈希
     * @param proof    Merkle 路径
     * @param isRight  每层位置标记
     * @return 计算得到的 Merkle 根
     */
    function computeRoot(
        bytes32 leaf,
        bytes32[] memory proof,
        bool[] memory isRight
    ) internal pure returns (bytes32) {
        require(proof.length == isRight.length, "MerkleLib: length mismatch");
        bytes32 computed = leaf;
        for (uint256 i = 0; i < proof.length; i++) {
            if (isRight[i]) {
                // leaf 在右侧，proof 兄弟在左侧
                computed = keccak256(abi.encodePacked(proof[i], computed));
            } else {
                // leaf 在左侧，proof 兄弟在右侧
                computed = keccak256(abi.encodePacked(computed, proof[i]));
            }
        }
        return computed;
    }
}

/**
 * @title IERC20
 * @author NexusChain
 * @notice 简化的 ERC20 接口，仅包含 L2Bridge 提款所需的 transfer / balanceOf。
 */
interface IERC20 {
    /// @notice 转移 amount 到 recipient
    function transfer(address recipient, uint256 amount) external returns (bool);

    /// @notice 查询 account 余额
    function balanceOf(address account) external view returns (uint256);
}

/**
 * @title L2Bridge
 * @author NexusChain
 * @notice 生产级 L2↔L1 桥合约，用于 NexusChain L2 Rollup 欺诈证明上链验证。
 * @dev 部署在 L1（Hardhat 本地节点）上，由 Java 侧 Web3jL1ContractClient 通过 JSON-RPC 调用。
 *
 * 核心函数（与 Java 侧 L1ContractClient 一一对应）：
 * - submitStateRoot(bytes32 stateRoot, uint256 batchId) — 提交状态根（向后兼容）
 * - submitStateRootWithSig(bytes32, uint256, uint256, bytes) — Sequencer 签名提交状态根
 * - markBatchVerified(uint256 batchId) — 标记批次验证通过（挑战期结束后）
 * - finalizeWithdraws(uint256 batchId) — 最终化提款（向后兼容，仅置标志位）
 * - finalizeWithdrawsWithProof(...) — 验证 Merkle proof 后实际转移 ERC20
 * - challengeBatch(uint256 batchId, bytes32[] proof) — 挑战批次（向后兼容）
 * - challengeBatchWithProof(...) — Merkle 验证挑战 + 罚没 sequencer bond
 * - submitWithdrawals(uint256, Withdrawal[], bytes32) — 提交提款批次
 *
 * 增强功能：
 * - Merkle 验证（MerkleLib 库 + challengeBatchWithProof + finalizeWithdrawsWithProof）
 * - 挑战期时间锁（batchSubmitTime + challengePeriod 检查）
 * - Sequencer 签名验证（EIP-712 域分离 + submitStateRootWithSig）
 * - ERC20 提款（finalizeWithdrawsWithProof 实际转移 ERC20）
 * - 罚没机制（sequencerBond + challengeBatchWithProof 罚没给挑战者）
 * - AccessControl（owner / authorizedSequencer / challenger 角色）
 *
 * 向后兼容性：
 * - 保留所有原有事件和函数签名
 * - submitStateRoot 在未设置 authorizedSequencer 时任何人可调用
 * - finalizeWithdraws 保留原语义（仅置标志位）
 * - challengeBatch 保留原签名（增加挑战期内检查）
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

    // ---- 新增事件（生产级增强） ----

    /// @notice 授权 Sequencer 被设置
    /// @param sequencer 新的授权 Sequencer 地址
    event SequencerSet(address indexed sequencer);

    /// @notice 质押金额被更新
    /// @param sequencerBond  Sequencer 质押金额
    /// @param challengerBond 挑战者质押金额
    event BondsUpdated(uint256 sequencerBond, uint256 challengerBond);

    /// @notice Sequencer 质押
    /// @param sequencer 质押的 Sequencer
    /// @param amount    质押金额
    event SequencerBonded(address indexed sequencer, uint256 amount);

    /// @notice 挑战者质押
    /// @param challenger 质押的挑战者
    /// @param amount     质押金额
    event ChallengerBonded(address indexed challenger, uint256 amount);

    /// @notice Sequencer 被罚没
    /// @param batchId    批次 ID
    /// @param sequencer  被罚没的 Sequencer
    /// @param challenger 罚没收受者（挑战者）
    /// @param amount     罚没金额
    event SubmitterSlashed(uint256 indexed batchId, address indexed sequencer, address challenger, uint256 amount);

    /// @notice 提款根被提交
    /// @param batchId        批次 ID
    /// @param withdrawalRoot 提款 Merkle 根
    /// @param count          提款笔数
    event WithdrawalsSubmitted(uint256 indexed batchId, bytes32 withdrawalRoot, uint256 count);

    /// @notice 单笔提款被最终化（含 ERC20 转账详情）
    /// @param batchId   批次 ID
    /// @param index     提款在批次中的索引
    /// @param recipient 收款人
    /// @param token     ERC20 代币地址
    /// @param amount    提款金额
    event WithdrawFinalizedDetailed(
        uint256 indexed batchId,
        uint256 indexed index,
        address recipient,
        address token,
        uint256 amount
    );

    /// @notice Sequencer 签名提交状态根
    /// @param batchId       批次 ID
    /// @param stateRoot     状态根
    /// @param sequencer     Sequencer 地址
    /// @param targetChainId 目标链 ID
    event StateRootSubmittedWithSig(
        uint256 indexed batchId,
        bytes32 stateRoot,
        address sequencer,
        uint256 targetChainId
    );

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

    /// @notice 挑战期（秒）
    uint256 public challengePeriod;

    /// @notice 合约部署者（owner 角色）
    address public owner;

    // ---- 新增状态（生产级增强） ----

    /// @notice 授权 Sequencer 地址（由 owner 设置）
    address public authorizedSequencer;

    /// @notice 批次 ID -> 提交时间戳（用于挑战期时间锁）
    mapping(uint256 => uint256) public batchSubmitTime;

    /// @notice Sequencer 质押金额要求
    uint256 public sequencerBondAmount;

    /// @notice 挑战者质押金额要求
    uint256 public challengerBondAmount;

    /// @notice 挑战者地址 -> 已质押金额
    mapping(address => uint256) public challengerBondBalances;

    /// @notice Sequencer 当前已质押金额
    uint256 public sequencerBondBalance;

    /// @notice Sequencer 是否已质押
    bool public sequencerBondDeposited;

    /// @notice 批次 ID -> 提款 Merkle 根
    mapping(uint256 => bytes32) public batchWithdrawalRoot;

    /// @notice 批次 ID -> 提款索引 -> 是否已最终化
    mapping(uint256 => mapping(uint256 => bool)) public withdrawalFinalized;

    // ---- EIP-712 域分离常量 ----

    /// @notice EIP-712 Domain typeHash
    bytes32 public constant EIP712_DOMAIN_TYPEHASH =
        keccak256("EIP712Domain(string name,uint256 chainId,address verifyingContract)");

    /// @notice Submit 结构 typeHash
    bytes32 public constant SUBMIT_TYPEHASH =
        keccak256("Submit(bytes32 stateRoot,uint256 batchId,uint256 targetChainId)");

    // ==================== 修饰符 ====================

    modifier onlyExistingBatch(uint256 batchId) {
        require(
            batchStatus[batchId] != BatchStatus.NONE,
            "L2Bridge: batch not submitted"
        );
        _;
    }

    /// @notice 仅 owner 可调用
    modifier onlyOwner() {
        require(msg.sender == owner, "L2Bridge: not owner");
        _;
    }

    /// @notice 仅授权 Sequencer 可调用
    modifier onlySequencer() {
        require(msg.sender == authorizedSequencer, "L2Bridge: not sequencer");
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

    // ==================== 角色与质押管理 ====================

    /**
     * @notice 设置授权 Sequencer 地址（仅 owner）。
     * @dev 设置为 address(0) 表示禁用 Sequencer 权限检查（向后兼容模式）。
     * @param sequencer 新的授权 Sequencer 地址
     */
    function setAuthorizedSequencer(address sequencer) external onlyOwner {
        authorizedSequencer = sequencer;
        emit SequencerSet(sequencer);
    }

    /**
     * @notice 设置 Sequencer 与挑战者的质押金额要求（仅 owner）。
     * @param _sequencerBond  Sequencer 质押金额要求（wei）
     * @param _challengerBond 挑战者质押金额要求（wei）
     */
    function setBonds(uint256 _sequencerBond, uint256 _challengerBond) external onlyOwner {
        sequencerBondAmount = _sequencerBond;
        challengerBondAmount = _challengerBond;
        emit BondsUpdated(_sequencerBond, _challengerBond);
    }

    /**
     * @notice Sequencer 质押 bond（仅授权 Sequencer）。
     * @dev msg.value 必须等于 sequencerBondAmount。
     */
    function depositSequencerBond() external payable onlySequencer {
        require(msg.value == sequencerBondAmount, "L2Bridge: wrong bond amount");
        require(!sequencerBondDeposited, "L2Bridge: already bonded");
        sequencerBondBalance = msg.value;
        sequencerBondDeposited = true;
        emit SequencerBonded(msg.sender, msg.value);
    }

    /**
     * @notice 挑战者质押 bond。
     * @dev msg.value 必须等于 challengerBondAmount。可多次质押累加。
     */
    function depositChallengerBond() external payable {
        require(msg.value == challengerBondAmount, "L2Bridge: wrong bond amount");
        challengerBondBalances[msg.sender] += msg.value;
        emit ChallengerBonded(msg.sender, msg.value);
    }

    // ==================== 核心函数（向后兼容） ====================

    /**
     * @notice 提交状态根到 L1（向后兼容版本）。
     * @dev 若设置了 authorizedSequencer（非零地址），则要求 msg.sender 为授权 Sequencer；
     *   否则任何人可调用（向后兼容模式）。记录提交时间用于挑战期时间锁。
     * @param stateRoot L2 状态根
     * @param batchId   批次 ID
     */
    function submitStateRoot(bytes32 stateRoot, uint256 batchId) external {
        if (authorizedSequencer != address(0)) {
            require(msg.sender == authorizedSequencer, "L2Bridge: not sequencer");
        }
        batchStateRoot[batchId] = stateRoot;
        batchSubmitter[batchId] = msg.sender;
        batchStatus[batchId] = BatchStatus.SUBMITTED;
        batchSubmitTime[batchId] = block.timestamp;

        emit StateRootSubmitted(batchId, stateRoot, msg.sender);
    }

    /**
     * @notice 通过 EIP-712 签名提交状态根（生产级，Sequencer 签名验证）。
     * @dev 签名内容为 EIP-712 结构化数据：
     *   Submit(bytes32 stateRoot, uint256 batchId, uint256 targetChainId)
     *   签名者必须为 authorizedSequencer。
     * @param stateRoot     L2 状态根
     * @param batchId       批次 ID
     * @param targetChainId 目标链 ID（防跨链重放）
     * @param signature     Sequencer 的 65 字节 ECDSA 签名
     */
    function submitStateRootWithSig(
        bytes32 stateRoot,
        uint256 batchId,
        uint256 targetChainId,
        bytes calldata signature
    ) external {
        require(authorizedSequencer != address(0), "L2Bridge: sequencer not set");

        bytes32 domainSeparator = keccak256(
            abi.encode(
                EIP712_DOMAIN_TYPEHASH,
                keccak256("L2Bridge"),
                block.chainid,
                address(this)
            )
        );
        bytes32 structHash = keccak256(
            abi.encode(SUBMIT_TYPEHASH, stateRoot, batchId, targetChainId)
        );
        bytes32 digest = keccak256(abi.encodePacked("\x19\x01", domainSeparator, structHash));

        address signer = recoverSigner(digest, signature);
        require(signer == authorizedSequencer, "L2Bridge: invalid sequencer signature");

        batchStateRoot[batchId] = stateRoot;
        batchSubmitter[batchId] = signer;
        batchStatus[batchId] = BatchStatus.SUBMITTED;
        batchSubmitTime[batchId] = block.timestamp;

        emit StateRootSubmitted(batchId, stateRoot, signer);
        emit StateRootSubmittedWithSig(batchId, stateRoot, signer, targetChainId);
    }

    /**
     * @notice 标记批次为 VERIFIED（挑战期结束后）。
     * @dev 要求批次已提交状态根（SUBMITTED 状态），且挑战期已过。
     *   挑战后的批次无法被验证。
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
            block.timestamp >= batchSubmitTime[batchId] + challengePeriod,
            "L2Bridge: challenge period not over"
        );

        batchStatus[batchId] = BatchStatus.VERIFIED;
        emit BatchVerified(batchId, msg.sender);
    }

    /**
     * @notice 最终化批次提款（向后兼容版本，仅置标志位）。
     * @dev 要求批次已 VERIFIED。生产级提款请使用 finalizeWithdrawsWithProof。
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
     * @notice 挑战批次（提交欺诈证明，向后兼容版本）。
     * @dev 仅记录挑战者与证明哈希，将批次标记为 CHALLENGED。
     *   要求批次已提交状态根且未被挑战过，且在挑战期内。
     *   生产级 Merkle 验证挑战请使用 challengeBatchWithProof。
     * @param batchId 批次 ID
     * @param proof   欺诈证明（bytes32 数组）
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
        require(
            block.timestamp < batchSubmitTime[batchId] + challengePeriod,
            "L2Bridge: challenge period over"
        );

        bytes32 proofHash = keccak256(abi.encodePacked(proof));

        batchChallenger[batchId] = msg.sender;
        batchChallengeProofHash[batchId] = proofHash;
        batchStatus[batchId] = BatchStatus.CHALLENGED;

        emit BatchChallenged(batchId, msg.sender, proofHash);
    }

    // ==================== 生产级：Merkle 验证挑战 + 罚没 ====================

    /**
     * @notice 通过 Merkle Proof 挑战批次（生产级，含罚没机制）。
     * @dev 验证 leaf 在 batchStateRoot[batchId] 的 Merkle 树中。
     *   验证通过后罚没 sequencer bond 给挑战者。
     *   要求：
     *   - 批次处于 SUBMITTED 状态
     *   - 在挑战期内
     *   - 挑战者已质押 challengerBondAmount
     *   - Sequencer 已质押 sequencerBond
     *   - Merkle proof 验证通过
     * @param batchId  批次 ID
     * @param leaf     叶子节点哈希
     * @param proof    Merkle 路径
     * @param isRight  每层位置标记（true=右，false=左）
     */
    function challengeBatchWithProof(
        uint256 batchId,
        bytes32 leaf,
        bytes32[] calldata proof,
        bool[] calldata isRight
    ) external onlyExistingBatch(batchId) {
        require(
            batchStatus[batchId] == BatchStatus.SUBMITTED,
            "L2Bridge: batch not in SUBMITTED state"
        );
        require(
            block.timestamp < batchSubmitTime[batchId] + challengePeriod,
            "L2Bridge: challenge period over"
        );
        require(
            challengerBondBalances[msg.sender] >= challengerBondAmount,
            "L2Bridge: insufficient challenger bond"
        );
        require(sequencerBondDeposited, "L2Bridge: sequencer not bonded");

        // 验证 Merkle proof
        bool valid = MerkleLib.verifyMerkleProof(
            batchStateRoot[batchId],
            leaf,
            proof,
            isRight
        );
        require(valid, "L2Bridge: invalid merkle proof");

        // 罚没 sequencer bond 给挑战者
        uint256 slashAmount = sequencerBondBalance;
        sequencerBondBalance = 0;
        sequencerBondDeposited = false;

        batchChallenger[batchId] = msg.sender;
        batchStatus[batchId] = BatchStatus.CHALLENGED;
        batchChallengeProofHash[batchId] = keccak256(abi.encodePacked(proof));

        // 转移罚没金额给挑战者
        (bool success, ) = msg.sender.call{value: slashAmount}("");
        require(success, "L2Bridge: slash transfer failed");

        emit BatchChallenged(batchId, msg.sender, batchChallengeProofHash[batchId]);
        emit SubmitterSlashed(batchId, batchSubmitter[batchId], msg.sender, slashAmount);
    }

    // ==================== 生产级：ERC20 提款 ====================

    /// @notice 提款结构（用于批量提交）
    struct Withdrawal {
        /// @dev ERC20 代币地址
        address token;
        /// @dev 收款人地址
        address recipient;
        /// @dev 提款金额
        uint256 amount;
    }

    /**
     * @notice 提交批次提款根（仅 Sequencer）。
     * @dev 提款根为所有提款的 Merkle 根，叶节点为
     *   keccak256(abi.encode(token, recipient, amount, index))。
     * @param batchId        批次 ID
     * @param withdrawals    提款列表（用于事件记录与验证）
     * @param withdrawalRoot 提款 Merkle 根
     */
    function submitWithdrawals(
        uint256 batchId,
        Withdrawal[] calldata withdrawals,
        bytes32 withdrawalRoot
    ) external onlySequencer {
        require(
            batchStatus[batchId] != BatchStatus.NONE,
            "L2Bridge: batch not submitted"
        );
        require(withdrawals.length > 0, "L2Bridge: empty withdrawals");
        batchWithdrawalRoot[batchId] = withdrawalRoot;
        emit WithdrawalsSubmitted(batchId, withdrawalRoot, withdrawals.length);
    }

    /**
     * @notice 最终化单笔提款（生产级，验证 Merkle proof 后实际转移 ERC20）。
     * @dev 叶节点为 keccak256(abi.encode(token, recipient, amount, index))。
     *   要求批次已 VERIFIED 且该笔提款未最终化。
     * @param batchId   批次 ID
     * @param index     提款在批次中的索引
     * @param token     ERC20 代币地址
     * @param recipient 收款人地址
     * @param amount    提款金额
     * @param proof     Merkle 路径
     * @param isRight   每层位置标记
     */
    function finalizeWithdrawsWithProof(
        uint256 batchId,
        uint256 index,
        address token,
        address recipient,
        uint256 amount,
        bytes32[] calldata proof,
        bool[] calldata isRight
    ) external onlyExistingBatch(batchId) {
        require(
            batchStatus[batchId] == BatchStatus.VERIFIED ||
                batchStatus[batchId] == BatchStatus.FINALIZED,
            "L2Bridge: batch not VERIFIED"
        );
        require(
            !withdrawalFinalized[batchId][index],
            "L2Bridge: withdrawal already finalized"
        );
        require(
            batchWithdrawalRoot[batchId] != bytes32(0),
            "L2Bridge: withdrawal root not set"
        );

        // 计算叶节点哈希
        bytes32 leaf = keccak256(abi.encode(token, recipient, amount, index));
        bool valid = MerkleLib.verifyMerkleProof(
            batchWithdrawalRoot[batchId],
            leaf,
            proof,
            isRight
        );
        require(valid, "L2Bridge: invalid withdrawal proof");

        withdrawalFinalized[batchId][index] = true;

        // 实际转移 ERC20
        require(
            IERC20(token).transfer(recipient, amount),
            "L2Bridge: ERC20 transfer failed"
        );

        if (batchStatus[batchId] == BatchStatus.VERIFIED) {
            batchStatus[batchId] = BatchStatus.FINALIZED;
        }

        emit WithdrawFinalizedDetailed(batchId, index, recipient, token, amount);
        emit WithdrawFinalized(batchId, msg.sender);
    }

    // ==================== 内部辅助函数 ====================

    /**
     * @notice 从哈希与签名恢复签名者地址。
     * @param hash      已前缀化的消息哈希（eth_sign 前缀或 EIP-712 digest）
     * @param signature 65 字节 ECDSA 签名 (r, s, v)
     * @return 签名者地址
     */
    function recoverSigner(bytes32 hash, bytes calldata signature)
        internal
        pure
        returns (address)
    {
        require(signature.length == 65, "L2Bridge: invalid signature length");
        bytes32 r;
        bytes32 s;
        uint8 v;
        assembly {
            r := calldataload(signature.offset)
            s := calldataload(add(signature.offset, 32))
            v := byte(0, calldataload(add(signature.offset, 64)))
        }
        if (v < 27) {
            v += 27;
        }
        require(v == 27 || v == 28, "L2Bridge: invalid signature v");
        return ecrecover(hash, v, r, s);
    }

    // ==================== View 函数（向后兼容） ====================

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

    // ==================== 新增 View 函数 ====================

    /**
     * @notice 查询批次提交时间。
     * @param batchId 批次 ID
     * @return 提交时间戳（unix seconds）
     */
    function getBatchSubmitTime(uint256 batchId) external view returns (uint256) {
        return batchSubmitTime[batchId];
    }

    /**
     * @notice 查询批次提款 Merkle 根。
     * @param batchId 批次 ID
     * @return 提款 Merkle 根
     */
    function getWithdrawalRoot(uint256 batchId) external view returns (bytes32) {
        return batchWithdrawalRoot[batchId];
    }

    /**
     * @notice 查询某笔提款是否已最终化。
     * @param batchId 批次 ID
     * @param index   提款索引
     * @return true 表示已最终化
     */
    function isWithdrawalFinalized(uint256 batchId, uint256 index)
        external
        view
        returns (bool)
    {
        return withdrawalFinalized[batchId][index];
    }

    /**
     * @notice 查询挑战期结束时间。
     * @param batchId 批次 ID
     * @return 挑战期结束时间戳（batchSubmitTime + challengePeriod）
     */
    function getChallengeDeadline(uint256 batchId) external view returns (uint256) {
        return batchSubmitTime[batchId] + challengePeriod;
    }

    /**
     * @notice 查询挑战者可用质押余额。
     * @param challenger 挑战者地址
     * @return 质押余额
     */
    function getChallengerBondBalance(address challenger)
        external
        view
        returns (uint256)
    {
        return challengerBondBalances[challenger];
    }

    // ==================== 接收 Ether ====================

    /// @notice 接收 Ether（用于质押与罚没转账）
    receive() external payable {}
}
