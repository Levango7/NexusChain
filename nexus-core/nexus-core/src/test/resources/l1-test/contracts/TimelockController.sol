// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

/**
 * @title TimelockController
 * @author NexusChain
 * @notice 治理时间锁控制器，提案通过后不立即执行，需经过固定延迟期。
 * @dev 参考 OpenZeppelin GovernorTimelock 设计，但自实现不依赖 OZ。
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>PROPOSER 调用 schedule 排度操作，状态置 Pending</li>
 *   <li>延迟到期后状态自动转为 Ready</li>
 *   <li>EXECUTOR 调用 execute 执行操作，状态置 Done</li>
 *   <li>PROPOSER 可在执行前调用 cancel 取消，状态置 Cancelled</li>
 * </ol>
 *
 * <p>角色控制：</p>
 * <ul>
 *   <li>PROPOSER 角色：可 schedule / cancel</li>
 *   <li>EXECUTOR 角色：可 execute</li>
 * </ul>
 *
 * <p>操作 ID = keccak256(abi.encode(target, data, value, scheduleTime))，
 * 其中 scheduleTime 为 schedule 调用时的 block.timestamp。</p>
 *
 * @dev 2.1
 */
contract TimelockController {
    // ==================== 事件 ====================

    /// @notice 操作已排度
    /// @param operationId 操作 ID
    /// @param target      目标合约地址
    /// @param value       调用附带的 ETH 数量
    /// @param delay       实际延迟（秒）
    /// @param eta         预计到期时间戳
    event OperationScheduled(
        bytes32 indexed operationId,
        address indexed target,
        uint256 value,
        uint256 delay,
        uint256 eta
    );

    /// @notice 操作已执行
    /// @param operationId 操作 ID
    /// @param target      目标合约地址
    /// @param value       调用附带的 ETH 数量
    event OperationExecuted(
        bytes32 indexed operationId,
        address indexed target,
        uint256 value
    );

    /// @notice 操作已取消
    /// @param operationId 操作 ID
    event OperationCancelled(bytes32 indexed operationId);

    // ==================== 状态枚举 ====================

    /// @notice 操作状态枚举
    enum OperationState {
        /// @dev 不存在（占位）
        Unset,
        /// @dev 已排度，等待延迟到期
        Pending,
        /// @dev 延迟已到期，可执行
        Ready,
        /// @dev 已执行
        Done,
        /// @dev 已取消
        Cancelled
    }

    // ==================== 角色常量 ====================

    /// @notice PROPOSER 角色：可 schedule / cancel
    bytes32 public constant PROPOSER_ROLE = keccak256("PROPOSER_ROLE");

    /// @notice EXECUTOR 角色：可 execute
    bytes32 public constant EXECUTOR_ROLE = keccak256("EXECUTOR_ROLE");

    // ==================== 状态 ====================

    /// @notice 操作 ID -> 排度时间戳（0 表示未排度）
    mapping(bytes32 => uint256) public operationScheduleTime;

    /// @notice 操作 ID -> 实际延迟（秒）
    mapping(bytes32 => uint256) public operationDelay;

    /// @notice 操作 ID -> 是否已执行
    mapping(bytes32 => bool) public operationDone;

    /// @notice 操作 ID -> 是否已取消
    mapping(bytes32 => bool) public operationCancelled;

    /// @notice 角色 -> 账户 -> 是否拥有该角色
    mapping(bytes32 => mapping(address => bool)) public hasRole;

    /// @notice 最小延迟（秒），schedule 时的 delay 不得小于此值
    uint256 public minDelay;

    /// @notice 合约部署者
    address public owner;

    // ==================== 修饰符 ====================

    modifier onlyRole(bytes32 role) {
        require(hasRole[role][msg.sender], "TimelockController: unauthorized");
        _;
    }

    // ==================== 构造函数 ====================

    /**
     * @notice 构造函数
     * @param _minDelay     最小延迟（秒）
     * @param _proposers    初始 PROPOSER 角色账户列表
     * @param _executors    初始 EXECUTOR 角色账户列表
     */
    constructor(
        uint256 _minDelay,
        address[] memory _proposers,
        address[] memory _executors
    ) {
        owner = msg.sender;
        minDelay = _minDelay;

        for (uint256 i = 0; i < _proposers.length; i++) {
            hasRole[PROPOSER_ROLE][_proposers[i]] = true;
        }
        for (uint256 i = 0; i < _executors.length; i++) {
            hasRole[EXECUTOR_ROLE][_executors[i]] = true;
        }
    }

    // ==================== 核心函数 ====================

    /**
     * @notice 排度一个延迟操作。
     * @dev 要求调用者拥有 PROPOSER 角色，且 delay >= minDelay。
     *   操作 ID = keccak256(abi.encode(target, data, value, scheduleTime))，
     *   其中 scheduleTime = block.timestamp。同一操作 ID 不可重复排度。
     * @param target 目标合约地址
     * @param data   调用 calldata
     * @param value  调用附带的 ETH 数量
     * @param delay  延迟秒数，不得小于 minDelay
     * @return operationId 操作 ID
     */
    function schedule(
        address target,
        bytes memory data,
        uint256 value,
        uint256 delay
    ) external onlyRole(PROPOSER_ROLE) returns (bytes32 operationId) {
        require(delay >= minDelay, "TimelockController: delay below minimum");

        uint256 scheduleTime = block.timestamp;
        operationId = keccak256(abi.encode(target, data, value, scheduleTime));

        require(
            operationScheduleTime[operationId] == 0 &&
                !operationDone[operationId] &&
                !operationCancelled[operationId],
            "TimelockController: operation already exists"
        );

        operationScheduleTime[operationId] = scheduleTime;
        operationDelay[operationId] = delay;

        emit OperationScheduled(operationId, target, value, delay, scheduleTime + delay);
    }

    /**
     * @notice 执行已到期的操作（占位接口，实际请使用 executeById）。
     * @dev 因无法从 (target, data, value) 反查 operationId，本函数始终 revert。
     *   请使用 executeById() 通过 operationId 执行。
     */
    function execute(
        address,
        bytes memory,
        uint256
    ) external payable onlyRole(EXECUTOR_ROLE) {
        // 反查操作 ID：尝试所有可能的 scheduleTime 不现实，
        // 因此要求调用者提供 scheduleTime（通过 hashOperation 公式预计算）。
        // 简化方案：要求调用者先调用 hashOperation 取得 ID，再通过 ID 查 scheduleTime。
        // 这里采用遍历映射的方式不可行，改为要求调用 executeById。
        // 但为满足接口签名，本函数 revert，引导用户使用 executeById。
        // 参数 _target/_data/_value 仅用于接口占位，实际执行请使用 executeById。
        revert("TimelockController: use executeById");
    }

    /**
     * @notice 执行已到期的操作（按操作 ID）。
     * @dev 要求调用者拥有 EXECUTOR 角色，操作状态为 Ready。
     *   使用 low-level call 调用目标合约，要求调用成功。
     * @param operationId 操作 ID
     * @param target      目标合约地址（用于实际调用）
     * @param data        调用 calldata
     * @param value       调用附带的 ETH 数量
     */
    function executeById(
        bytes32 operationId,
        address target,
        bytes memory data,
        uint256 value
    ) external payable onlyRole(EXECUTOR_ROLE) {
        require(
            operationScheduleTime[operationId] != 0,
            "TimelockController: operation not found"
        );
        require(!operationDone[operationId], "TimelockController: already executed");
        require(!operationCancelled[operationId], "TimelockController: already cancelled");

        // 校验操作 ID 与参数一致
        bytes32 expectedId = keccak256(
            abi.encode(target, data, value, operationScheduleTime[operationId])
        );
        require(
            operationId == expectedId,
            "TimelockController: id mismatch"
        );

        // 校验已到期
        require(
            block.timestamp >= operationScheduleTime[operationId] + operationDelay[operationId],
            "TimelockController: operation not yet mature"
        );

        // 执行 low-level call
        (bool success, bytes memory result) = target.call{value: value}(data);
        if (!success) {
            // bubble up revert reason
            if (result.length > 0) {
                assembly {
                    revert(add(result, 32), mload(result))
                }
            }
            revert("TimelockController: underlying call failed");
        }

        operationDone[operationId] = true;
        emit OperationExecuted(operationId, target, value);
    }

    /**
     * @notice 取消操作。
     * @dev 要求调用者拥有 PROPOSER 角色，操作存在且未执行未取消。
     * @param operationId 操作 ID
     */
    function cancel(bytes32 operationId) external onlyRole(PROPOSER_ROLE) {
        require(
            operationScheduleTime[operationId] != 0,
            "TimelockController: operation not found"
        );
        require(!operationDone[operationId], "TimelockController: already executed");
        require(!operationCancelled[operationId], "TimelockController: already cancelled");

        operationCancelled[operationId] = true;
        emit OperationCancelled(operationId);
    }

    // ==================== 角色管理 ====================

    /**
     * @notice 授予角色。
     * @dev 仅 owner 可调用。
     * @param role    角色常量
     * @param account 账户地址
     */
    function grantRole(bytes32 role, address account) external {
        require(msg.sender == owner, "TimelockController: not owner");
        hasRole[role][account] = true;
    }

    /**
     * @notice 撤销角色。
     * @dev 仅 owner 可调用。
     * @param role    角色常量
     * @param account 账户地址
     */
    function revokeRole(bytes32 role, address account) external {
        require(msg.sender == owner, "TimelockController: not owner");
        hasRole[role][account] = false;
    }

    // ==================== View 函数 ====================

    /**
     * @notice 计算操作 ID（不写入状态）。
     * @param target      目标合约地址
     * @param data        调用 calldata
     * @param value       调用附带的 ETH 数量
     * @param scheduleTime 排度时间戳
     * @return 操作 ID
     */
    function hashOperation(
        address target,
        bytes memory data,
        uint256 value,
        uint256 scheduleTime
    ) external pure returns (bytes32) {
        return keccak256(abi.encode(target, data, value, scheduleTime));
    }

    /**
     * @notice 查询操作状态。
     * @param operationId 操作 ID
     * @return OperationState 枚举值
     */
    function getOperationState(bytes32 operationId) external view returns (OperationState) {
        if (operationCancelled[operationId]) {
            return OperationState.Cancelled;
        }
        if (operationDone[operationId]) {
            return OperationState.Done;
        }
        if (operationScheduleTime[operationId] == 0) {
            return OperationState.Unset;
        }
        if (
            block.timestamp >= operationScheduleTime[operationId] + operationDelay[operationId]
        ) {
            return OperationState.Ready;
        }
        return OperationState.Pending;
    }

    /**
     * @notice 查询操作的预计到期时间戳。
     * @param operationId 操作 ID
     * @return 到期时间戳；不存在返回 0
     */
    function getOperationEta(bytes32 operationId) external view returns (uint256) {
        if (operationScheduleTime[operationId] == 0) {
            return 0;
        }
        return operationScheduleTime[operationId] + operationDelay[operationId];
    }
}