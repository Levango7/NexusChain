// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

/**
 * @title MockERC20
 * @author NexusChain
 * @notice 简化的 ERC20 mock，用于 L2Bridge 提款测试。
 * @dev 仅实现 transfer / balanceOf / mint，不做完整 ERC20 实现。
 */
contract MockERC20 {
    string public name;
    string public symbol;
    uint8 public decimals;

    mapping(address => uint256) private _balances;
    uint256 public totalSupply;

    event Transfer(address indexed from, address indexed to, uint256 value);

    constructor(string memory _name, string memory _symbol, uint8 _decimals) {
        name = _name;
        symbol = _symbol;
        decimals = _decimals;
    }

    function balanceOf(address account) external view returns (uint256) {
        return _balances[account];
    }

    function transfer(address recipient, uint256 amount) external returns (bool) {
        require(_balances[msg.sender] >= amount, "MockERC20: insufficient balance");
        _balances[msg.sender] -= amount;
        _balances[recipient] += amount;
        emit Transfer(msg.sender, recipient, amount);
        return true;
    }

    /// @notice 测试用：给 account 铸造 amount
    function mint(address account, uint256 amount) external {
        _balances[account] += amount;
        totalSupply += amount;
        emit Transfer(address(0), account, amount);
    }
}