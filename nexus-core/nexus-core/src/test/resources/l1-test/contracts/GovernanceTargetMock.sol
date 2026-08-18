// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

/**
 * @title GovernanceTargetMock
 * @author NexusChain
 * @notice 治理执行目标合约（Mock），用于验证 Governor.execute 是否真正改变链上状态。
 * @dev 提供一个可设置的 value 字段与 setValue 函数，
 *   供 NexusGovernor 通过 TimelockController 调用以验证治理执行链路。
 *
 * <p>典型用法：</p>
 * <ol>
 *   <li>部署本合约</li>
 *   <li>构造 calldata = abi.encodeWithSignature("setValue(uint256)", 42)</li>
 *   <li>创建提案 target=本合约地址, data=calldata</li>
 *   <li>投票通过 → 排队 → timelock 到期 → 执行</li>
 *   <li>断言 value == 42</li>
 * </ol>
 *
 * @dev 2.1
 */
contract GovernanceTargetMock {
    // ==================== 事件 ====================

    /// @notice value 已被修改
    /// @param oldValue 旧值
    /// @param newValue 新值
    /// @param setter   调用者
    event ValueChanged(uint256 oldValue, uint256 newValue, address setter);

    // ==================== 状态 ====================

    /// @notice 可被治理修改的参数
    uint256 public value;

    /// @notice 修改次数（用于验证多次执行）
    uint256 public updateCount;

    // ==================== 函数 ====================

    /**
     * @notice 设置 value 的新值。
     * @param newValue 新值
     */
    function setValue(uint256 newValue) external {
        uint256 oldValue = value;
        value = newValue;
        updateCount += 1;
        emit ValueChanged(oldValue, newValue, msg.sender);
    }

    /**
     * @notice 查询当前 value。
     * @return 当前值
     */
    function getValue() external view returns (uint256) {
        return value;
    }
}