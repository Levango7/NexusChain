// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

import "./TimelockController.sol";

/**
 * @title NexusGovernor
 * @author NexusChain
 * @notice NexusChain 链上治理器，实现提案/投票/排队/执行完整流程。
 * @dev 与 Java 侧 GovernanceService / GovernanceExecutor / OnChainGovernance 接口对应。
 *
 * <h3>治理流程</h3>
 * <ol>
 *   <li>提案者调用 propose 创建提案，状态 Pending → Active（同区块）</li>
 *   <li>投票者在 votingPeriod 内调用 castVote 投票（For/Against/Abstain）</li>
 *   <li>投票期结束后，若 forVotes > againstVotes 且 forVotes >= quorumVotes()，提案 Succeeded</li>
 *   <li>调用 queue 将提案通过 TimelockController 排度，状态 Queued</li>
 *   <li>timelock 到期后调用 execute 执行，状态 Executed</li>
 *   <li>提案者可在 Queued 之前调用 cancel 取消提案</li>
 * </ol>
 *
 * <h3>投票权重</h3>
 * <p>简化实现：基于投票时账户的 ether 余额（含本合约持有余额，但通常为 0）。
 * 为便于测试，提供 setVotingWeight 由 owner 设置模拟权重。</p>
 *
 * <h3>提案状态机</h3>
 * <pre>
 *   Pending ──(propose)──▶ Active ──(voting end)──▶ Defeated / Succeeded
 *                                                  │
 *                                                  └──▶ Queued ──(timelock)──▶ Executed
 *                                                       │
 *                                                       └──▶ Canceled
 * </pre>
 *
 * @dev 2.1
 */
contract NexusGovernor {
    // ==================== 事件 ====================

    /// @notice 提案已创建
    /// @param proposalId   提案 ID
    /// @param proposer     提案者
    /// @param targets      目标合约地址数组
    /// @param values       调用附带的 ETH 数量数组
    /// @param startBlock   投票起始区块
    /// @param endBlock     投票结束区块
    /// @param description  提案描述
    event ProposalCreated(
        uint256 indexed proposalId,
        address indexed proposer,
        address[] targets,
        uint256[] values,
        uint256 startBlock,
        uint256 endBlock,
        string description
    );

    /// @notice 已投票
    /// @param proposalId 提案 ID
    /// @param voter      投票者
    /// @param support    投票选项（0=Against, 1=For, 2=Abstain）
    /// @param weight     投票权重
    event VoteCast(
        uint256 indexed proposalId,
        address indexed voter,
        uint8 support,
        uint256 weight
    );

    /// @notice 提案已排队（通过 TimelockController 排度）
    /// @param proposalId  提案 ID
    /// @param operationId 对应的 TimelockController 操作 ID
    /// @param eta         预计到期时间戳
    event ProposalQueued(uint256 indexed proposalId, bytes32 operationId, uint256 eta);

    /// @notice 提案已执行
    /// @param proposalId 提案 ID
    event ProposalExecuted(uint256 indexed proposalId);

    /// @notice 提案已取消
    /// @param proposalId 提案 ID
    event ProposalCanceled(uint256 indexed proposalId);

    // ==================== 状态枚举 ====================

    /// @notice 提案状态枚举（与 Java 侧 ProposalStatus 大体对应）
    enum ProposalState {
        /// @dev 投票进行中（对应 Java VOTING）
        Active,
        /// @dev 投票未通过（forVotes <= againstVotes 或未达 quorum）
        Defeated,
        /// @dev 投票通过，待排队
        Succeeded,
        /// @dev 已排队，等待 timelock 到期（对应 Java QUEUED）
        Queued,
        /// @dev 已执行（对应 Java EXECUTED）
        Executed,
        /// @dev 已取消
        Canceled
    }

    /// @notice 投票选项
    enum VoteType {
        Against,
        For,
        Abstain
    }

    // ==================== 提案结构 ====================

    /// @notice 提案数据结构
    struct Proposal {
        /// @dev 目标合约地址数组
        address[] targets;
        /// @dev 调用 calldata 数组
        bytes[] calldatas;
        /// @dev 调用附带的 ETH 数量数组
        uint256[] values;
        /// @dev 投票起始区块
        uint256 startBlock;
        /// @dev 投票结束区块
        uint256 endBlock;
        /// @dev 赞成票数
        uint256 forVotes;
        /// @dev 反对票数
        uint256 againstVotes;
        /// @dev 弃权票数
        uint256 abstainVotes;
        /// @dev 是否已执行
        bool executed;
        /// @dev 是否已取消
        bool canceled;
        /// @dev 是否已排队
        bool queued;
        /// @dev 对应 TimelockController 操作 ID
        bytes32 operationId;
        /// @dev 提案者
        address proposer;
    }

    // ==================== 状态 ====================

    /// @notice 提案 ID -> 提案数据
    mapping(uint256 => Proposal) public proposals;

    /// @notice 提案 ID -> 投票者 -> 投票选项（0 表示未投票，1=Against, 2=For, 3=Abstain）
    mapping(uint256 => mapping(address => uint8)) public proposalVotes;

    /// @notice 提案计数器（下一个提案 ID）
    uint256 public proposalCount;

    /// @notice 投票期区块数
    uint256 public votingPeriodBlocks;

    /// @notice 法定人数（绝对票数阈值）
    uint256 public quorumThreshold;

    /// @notice 关联的 TimelockController
    TimelockController public timelock;

    /// @notice 合约部署者 / 治理 owner
    address public owner;

    /// @notice 账户 -> 模拟投票权重（由 owner 设置，0 时回退到 ether 余额）
    mapping(address => uint256) public votingWeights;

    /// @notice 是否使用模拟权重模式
    bool public useMockWeights;

    // ==================== 修饰符 ====================

    modifier onlyOwner() {
        require(msg.sender == owner, "NexusGovernor: not owner");
        _;
    }

    // ==================== 构造函数 ====================

    /**
     * @notice 构造函数
     * @param _timelock           关联的 TimelockController 地址
     * @param _votingPeriodBlocks 投票期区块数
     * @param _quorumThreshold    法定人数阈值（绝对票数）
     */
    constructor(
        address _timelock,
        uint256 _votingPeriodBlocks,
        uint256 _quorumThreshold
    ) {
        owner = msg.sender;
        timelock = TimelockController(_timelock);
        votingPeriodBlocks = _votingPeriodBlocks;
        quorumThreshold = _quorumThreshold;
        useMockWeights = false;
    }

    // ==================== 核心函数 ====================

    /**
     * @notice 创建提案。
     * @dev 要求 targets/calldatas/values 长度一致且非空。
     *   提案 ID 从 1 开始递增。提案创建后立即进入 Active 状态。
     * @param targets     目标合约地址数组
     * @param calldatas   调用 calldata 数组
     * @param values      调用附带的 ETH 数量数组
     * @param description 提案描述
     * @return proposalId 提案 ID
     */
    function propose(
        address[] memory targets,
        bytes[] memory calldatas,
        uint256[] memory values,
        string memory description
    ) external returns (uint256 proposalId) {
        require(
            targets.length == calldatas.length && targets.length == values.length,
            "NexusGovernor: length mismatch"
        );
        require(targets.length > 0, "NexusGovernor: empty proposal");

        proposalId = ++proposalCount;
        uint256 startBlock = block.number;
        uint256 endBlock = startBlock + votingPeriodBlocks;

        Proposal storage p = proposals[proposalId];
        p.targets = targets;
        p.calldatas = calldatas;
        p.values = values;
        p.startBlock = startBlock;
        p.endBlock = endBlock;
        p.proposer = msg.sender;

        emit ProposalCreated(
            proposalId,
            msg.sender,
            targets,
            values,
            startBlock,
            endBlock,
            description
        );
    }

    /**
     * @notice 对提案投票。
     * @dev 要求提案处于 Active 状态，且投票者未投过票。
     *   投票权重 = 模拟权重（若启用）或账户 ether 余额。
     * @param proposalId 提案 ID
     * @param support    投票选项（0=Against, 1=For, 2=Abstain）
     */
    function castVote(uint256 proposalId, uint8 support) external {
        Proposal storage p = proposals[proposalId];
        require(p.endBlock > 0, "NexusGovernor: proposal not found");
        require(
            block.number >= p.startBlock && block.number < p.endBlock,
            "NexusGovernor: voting not active"
        );
        require(!p.canceled, "NexusGovernor: proposal canceled");
        require(
            proposalVotes[proposalId][msg.sender] == 0,
            "NexusGovernor: already voted"
        );
        require(support <= 2, "NexusGovernor: invalid support");

        uint256 weight = getVotingWeight(msg.sender);
        require(weight > 0, "NexusGovernor: no voting weight");

        proposalVotes[proposalId][msg.sender] = support + 1; // 1=Against, 2=For, 3=Abstain

        if (support == uint8(VoteType.For)) {
            p.forVotes += weight;
        } else if (support == uint8(VoteType.Against)) {
            p.againstVotes += weight;
        } else {
            p.abstainVotes += weight;
        }

        emit VoteCast(proposalId, msg.sender, support, weight);
    }

    /**
     * @notice 将已通过的提案排度到 TimelockController。
     * @dev 要求提案状态为 Succeeded。本合约需拥有 TimelockController 的 PROPOSER 角色。
     *   仅支持单一 action 提案（targets.length == 1）的简化排度；
     *   多 action 提案需扩展为批量 schedule。
     * @param proposalId 提案 ID
     * @return operationId TimelockController 操作 ID
     */
    function queue(uint256 proposalId)
        external
        returns (bytes32 operationId)
    {
        Proposal storage p = proposals[proposalId];
        require(p.endBlock > 0, "NexusGovernor: proposal not found");
        require(
            proposalState(proposalId) == ProposalState.Succeeded,
            "NexusGovernor: not succeeded"
        );

        // 简化：仅支持单 action 提案
        require(p.targets.length == 1, "NexusGovernor: only single-action supported");

        uint256 delay = timelock.minDelay();
        operationId = timelock.schedule(
            p.targets[0],
            p.calldatas[0],
            p.values[0],
            delay
        );

        p.queued = true;
        p.operationId = operationId;

        uint256 eta = block.timestamp + delay;
        emit ProposalQueued(proposalId, operationId, eta);
    }

    /**
     * @notice 执行已排队且 timelock 到期的提案。
     * @dev 要求提案状态为 Queued 且 TimelockController 操作 Ready。
     *   本合约需拥有 TimelockController 的 EXECUTOR 角色。
     * @param proposalId 提案 ID
     */
    function execute(uint256 proposalId) external payable {
        Proposal storage p = proposals[proposalId];
        require(p.endBlock > 0, "NexusGovernor: proposal not found");
        require(p.queued, "NexusGovernor: not queued");
        require(!p.executed, "NexusGovernor: already executed");
        require(!p.canceled, "NexusGovernor: proposal canceled");

        timelock.executeById{value: p.values[0]}(
            p.operationId,
            p.targets[0],
            p.calldatas[0],
            p.values[0]
        );

        p.executed = true;
        emit ProposalExecuted(proposalId);
    }

    /**
     * @notice 取消提案。
     * @dev 仅提案者或 owner 可取消。要求提案未执行。
     *   若已排队，同时取消 TimelockController 操作。
     * @param proposalId 提案 ID
     */
    function cancel(uint256 proposalId) external {
        Proposal storage p = proposals[proposalId];
        require(p.endBlock > 0, "NexusGovernor: proposal not found");
        require(!p.executed, "NexusGovernor: already executed");
        require(!p.canceled, "NexusGovernor: already canceled");
        require(
            msg.sender == p.proposer || msg.sender == owner,
            "NexusGovernor: not authorized"
        );

        p.canceled = true;

        // 若已排队，同时取消 timelock 操作
        if (p.queued) {
            timelock.cancel(p.operationId);
        }

        emit ProposalCanceled(proposalId);
    }

    // ==================== 治理参数管理 ====================

    /**
     * @notice 设置账户模拟投票权重。
     * @dev 仅 owner 可调用，启用后投票权重取自 mapping 而非 ether 余额。
     * @param account 账户地址
     * @param weight  权重值
     */
    function setVotingWeight(address account, uint256 weight) external onlyOwner {
        votingWeights[account] = weight;
        useMockWeights = true;
    }

    /**
     * @notice 批量启用模拟权重模式。
     * @dev 仅 owner 可调用。
     */
    function enableMockWeightsMode() external onlyOwner {
        useMockWeights = true;
    }

    /**
     * @notice 更新法定人数阈值。
     * @dev 仅 owner 可调用。
     * @param _quorumThreshold 新阈值
     */
    function setQuorumThreshold(uint256 _quorumThreshold) external onlyOwner {
        quorumThreshold = _quorumThreshold;
    }

    /**
     * @notice 更新投票期区块数。
     * @dev 仅 owner 可调用。
     * @param _votingPeriodBlocks 新投票期
     */
    function setVotingPeriodBlocks(uint256 _votingPeriodBlocks) external onlyOwner {
        votingPeriodBlocks = _votingPeriodBlocks;
    }

    // ==================== View 函数 ====================

    /**
     * @notice 查询账户投票权重。
     * @param account 账户地址
     * @return 权重值
     */
    function getVotingWeight(address account) public view returns (uint256) {
        if (useMockWeights) {
            return votingWeights[account];
        }
        return account.balance;
    }

    /**
     * @notice 法定人数阈值（绝对票数）。
     * @return 阈值
     */
    function quorumVotes() external view returns (uint256) {
        return quorumThreshold;
    }

    /**
     * @notice 投票期区块数。
     * @return 区块数
     */
    function votingPeriod() external view returns (uint256) {
        return votingPeriodBlocks;
    }

    /**
     * @notice 查询提案状态。
     * @dev 状态机：
     *   <ul>
     *     <li>canceled → Canceled</li>
     *     <li>executed → Executed</li>
     *     <li>block.number < startBlock → Active（理论上 propose 后立即 Active）</li>
     *     <li>block.number < endBlock → Active</li>
     *     <li>投票期结束：forVotes > againstVotes 且 forVotes >= quorum → Succeeded（或 Queued 若已排队）</li>
     *     <li>否则 → Defeated</li>
     *   </ul>
     * @param proposalId 提案 ID
     * @return ProposalState 枚举值
     */
    function proposalState(uint256 proposalId) public view returns (ProposalState) {
        Proposal storage p = proposals[proposalId];
        if (p.endBlock == 0) {
            revert("NexusGovernor: proposal not found");
        }
        if (p.canceled) {
            return ProposalState.Canceled;
        }
        if (p.executed) {
            return ProposalState.Executed;
        }
        if (p.queued) {
            return ProposalState.Queued;
        }
        if (block.number < p.endBlock) {
            return ProposalState.Active;
        }
        // 投票期结束
        if (p.forVotes > p.againstVotes && p.forVotes >= quorumThreshold) {
            return ProposalState.Succeeded;
        }
        return ProposalState.Defeated;
    }

    /**
     * @notice 查询提案完整数据。
     * @dev 由于 Proposal 包含动态数组，需逐字段返回。
     * @param proposalId 提案 ID
     */
    function getProposal(uint256 proposalId)
        external
        view
        returns (
            address[] memory targets,
            bytes[] memory calldatas,
            uint256[] memory values,
            uint256 startBlock,
            uint256 endBlock,
            uint256 forVotes,
            uint256 againstVotes,
            uint256 abstainVotes,
            bool executed,
            bool canceled,
            bool queued,
            bytes32 operationId,
            address proposer
        )
    {
        Proposal storage p = proposals[proposalId];
        return (
            p.targets,
            p.calldatas,
            p.values,
            p.startBlock,
            p.endBlock,
            p.forVotes,
            p.againstVotes,
            p.abstainVotes,
            p.executed,
            p.canceled,
            p.queued,
            p.operationId,
            p.proposer
        );
    }

    /**
     * @notice 查询投票者对某提案的投票选项。
     * @param proposalId 提案 ID
     * @param voter      投票者
     * @return 0=未投票, 1=Against, 2=For, 3=Abstain
     */
    function getVote(uint256 proposalId, address voter) external view returns (uint8) {
        return proposalVotes[proposalId][voter];
    }
}