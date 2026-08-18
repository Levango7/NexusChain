// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

/**
 * @title MockERC20
 * @author NexusChain
 * @notice 简化的 ERC20 mock，用于 L2Bridge 提款测试。
 * @dev 实现 transfer / transferFrom / approve / balanceOf / mint，
 *   满足 L2Bridge 提款流程与 BridgeSource.lock（需 approve/transferFrom）的测试需求。
 *   不包含 permit / EIP-2612 等扩展，仅用于测试场景。
 */
contract MockERC20 {
    string public name;
    string public symbol;
    uint8 public decimals;

    mapping(address => uint256) private _balances;
    /// @notice 授权额度：owner => spender => amount
    mapping(address => mapping(address => uint256)) public allowance;
    uint256 public totalSupply;

    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner, address indexed spender, uint256 value);

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

    /// @notice 授权 spender 使用 msg.sender 最多 amount 余额
    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    /// @notice 代授权转账：从 from 转移 amount 到 to
    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        require(_balances[from] >= amount, "MockERC20: insufficient balance");
        require(allowance[from][msg.sender] >= amount, "MockERC20: insufficient allowance");
        unchecked {
            allowance[from][msg.sender] -= amount;
        }
        _balances[from] -= amount;
        _balances[to] += amount;
        emit Transfer(from, to, amount);
        return true;
    }

    /// @notice 测试用：给 account 铸造 amount
    function mint(address account, uint256 amount) external {
        _balances[account] += amount;
        totalSupply += amount;
        emit Transfer(address(0), account, amount);
    }
}