// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

/**
 * @title ERC20Mock
 * @author NexusChain
 * @notice 简化的 ERC20 测试代币，供跨链桥单元测试使用。
 * @dev 仅实现最小可用的 ERC20 接口（mint/burn/transfer/approve/transferFrom），
 *   不包含许可、EIP-2612 等扩展。任何调用者均可自由 mint/burn，仅用于测试场景。
 *
 * 接口：
 * - mint(address to, uint256 amount) — 任意地址可铸造
 * - burn(address from, uint256 amount) — 任意地址可销毁
 * - transfer/approve/transferFrom — 标准 ERC20 行为
 */
contract ERC20Mock {
    // ==================== 事件 ====================

    /// @notice 转账事件
    event Transfer(address indexed from, address indexed to, uint256 value);

    /// @notice 授权事件
    event Approval(address indexed owner, address indexed spender, uint256 value);

    // ==================== 状态 ====================

    /// @notice 代币名称
    string public name;

    /// @notice 代币符号
    string public symbol;

    /// @notice 小数位数
    uint8 public decimals;

    /// @notice 总供应量
    uint256 public totalSupply;

    /// @notice 账户余额
    mapping(address => uint256) public balanceOf;

    /// @notice 授权额度
    mapping(address => mapping(address => uint256)) public allowance;

    // ==================== 构造函数 ====================

    /**
     * @notice 构造函数
     * @param _name      代币名称
     * @param _symbol    代币符号
     * @param _decimals  小数位数
     * @param _initialSupply 初始供应量（铸造给 msg.sender）
     */
    constructor(
        string memory _name,
        string memory _symbol,
        uint8 _decimals,
        uint256 _initialSupply
    ) {
        name = _name;
        symbol = _symbol;
        decimals = _decimals;
        totalSupply = _initialSupply;
        balanceOf[msg.sender] = _initialSupply;
        emit Transfer(address(0), msg.sender, _initialSupply);
    }

    // ==================== 内部函数 ====================

    /**
     * @notice 内部转账
     * @param from   来源地址
     * @param to     目标地址
     * @param amount 金额
     */
    function _transfer(address from, address to, uint256 amount) internal {
        require(from != address(0), "ERC20Mock: transfer from zero address");
        require(to != address(0), "ERC20Mock: transfer to zero address");
        require(balanceOf[from] >= amount, "ERC20Mock: insufficient balance");

        unchecked {
            balanceOf[from] -= amount;
            balanceOf[to] += amount;
        }
        emit Transfer(from, to, amount);
    }

    // ==================== 外部函数 ====================

    /**
     * @notice 转账
     * @param to     目标地址
     * @param amount 金额
     * @return true 表示成功
     */
    function transfer(address to, uint256 amount) external returns (bool) {
        _transfer(msg.sender, to, amount);
        return true;
    }

    /**
     * @notice 授权
     * @param spender 被授权地址
     * @param amount  金额
     * @return true 表示成功
     */
    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    /**
     * @notice 代授权转账
     * @param from   来源地址
     * @param to     目标地址
     * @param amount 金额
     * @return true 表示成功
     */
    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        uint256 allowed = allowance[from][msg.sender];
        require(allowed >= amount, "ERC20Mock: insufficient allowance");

        unchecked {
            allowance[from][msg.sender] = allowed - amount;
        }
        _transfer(from, to, amount);
        return true;
    }

    /**
     * @notice 铸造代币（测试用，无权限控制）
     * @param to     目标地址
     * @param amount 金额
     */
    function mint(address to, uint256 amount) external {
        require(to != address(0), "ERC20Mock: mint to zero address");
        unchecked {
            totalSupply += amount;
            balanceOf[to] += amount;
        }
        emit Transfer(address(0), to, amount);
    }

    /**
     * @notice 销毁代币（测试用，无权限控制）
     * @param from   来源地址
     * @param amount 金额
     */
    function burn(address from, uint256 amount) external {
        require(balanceOf[from] >= amount, "ERC20Mock: burn exceeds balance");
        unchecked {
            totalSupply -= amount;
            balanceOf[from] -= amount;
        }
        emit Transfer(from, address(0), amount);
    }
}