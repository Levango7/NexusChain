// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

/**
 * @title BridgeTarget
 * @author NexusChain
 * @notice 跨链桥目标链合约，负责铸造（mint）与销毁（burn）wrapped 资产。
 * @dev 部署在目标链上，由 Java 侧 EthereumBridgeHandler 通过 Web3j 调用。
 *
 * 核心函数：
 * - mint(token, recipient, amount, nonce, signature) — relayer 签名验证后铸造 wrapped 资产
 * - burn(token, amount, nonce, targetRecipient) — 用户销毁 wrapped 资产以赎回源链
 *
 * wrapped 资产模型：
 * - 使用内部账本 mapping(address => mapping(address => uint256)) wrappedBalance
 *   模拟 wrapped token 余额（不强制部署新 ERC20）
 * - 第一个 address 为源链原始 token，第二个 address 为持有者
 *
 * 安全机制：
 * - nonce 幂等性：mapping(bytes32 => bool) processedNonces 去重
 * - 签名验证：ECDSA recover，验证授权 relayer 签名（仅 mint 需要）
 * - 角色控制：owner 管理授权 relayer 集合
 * - 重入锁：简单 bool 变量防止重入
 *
 * 事件：
 * - Minted — wrapped 资产铸造
 * - Burned — wrapped 资产销毁
 * - RelayerAdded — relayer 添加
 * - RelayerRemoved — relayer 移除
 */
contract BridgeTarget {
    // ==================== 事件 ====================

    /// @notice wrapped 资产铸造事件
    /// @param token     源链原始 ERC20 代币地址
    /// @param recipient 接收者地址
    /// @param amount    铸造金额
    /// @param nonce     跨链交易 nonce（幂等键）
    /// @param relayer   触发铸造的 relayer 地址
    event Minted(
        address indexed token,
        address indexed recipient,
        uint256 amount,
        bytes32 indexed nonce,
        address relayer
    );

    /// @notice wrapped 资产销毁事件
    /// @param token          源链原始 ERC20 代币地址
    /// @param burner         销毁者（用户）地址
    /// @param targetRecipient 源链接收者地址
    /// @param amount         销毁金额
    /// @param nonce          跨链交易 nonce（幂等键）
    event Burned(
        address indexed token,
        address indexed burner,
        address targetRecipient,
        uint256 amount,
        bytes32 indexed nonce
    );

    /// @notice relayer 添加事件
    event RelayerAdded(address indexed relayer);

    /// @notice relayer 移除事件
    event RelayerRemoved(address indexed relayer);

    // ==================== 状态 ====================

    /// @notice 合约 owner
    address public owner;

    /// @notice 授权 relayer 集合
    mapping(address => bool) public isRelayer;

    /// @notice 已处理的 nonce（幂等键）
    mapping(bytes32 => bool) public processedNonces;

    /// @notice wrapped 资产余额：源链 token => 持有者 => 余额
    mapping(address => mapping(address => uint256)) public wrappedBalance;

    /// @notice wrapped 资产总供应量：源链 token => 总量
    mapping(address => uint256) public wrappedTotalSupply;

    /// @notice 重入锁状态
    bool private _locked;

    // ==================== 修饰符 ====================

    /// @dev 仅 owner 可调用
    modifier onlyOwner() {
        require(msg.sender == owner, "BridgeTarget: not owner");
        _;
    }

    /// @dev 重入保护
    modifier nonReentrant() {
        require(!_locked, "BridgeTarget: reentrant call");
        _locked = true;
        _;
        _locked = false;
    }

    // ==================== 构造函数 ====================

    /**
     * @notice 构造函数
     * @dev 部署者自动成为 owner 与初始 relayer
     * @param _initialRelayer 初始授权 relayer 地址（可为零地址表示不预置）
     */
    constructor(address _initialRelayer) {
        owner = msg.sender;
        if (_initialRelayer != address(0)) {
            isRelayer[_initialRelayer] = true;
            emit RelayerAdded(_initialRelayer);
        }
        // owner 默认也是 relayer，便于测试与初始运维
        isRelayer[msg.sender] = true;
        emit RelayerAdded(msg.sender);
    }

    // ==================== 管理函数 ====================

    /**
     * @notice 添加授权 relayer
     * @param relayer relayer 地址
     */
    function addRelayer(address relayer) external onlyOwner {
        require(relayer != address(0), "BridgeTarget: zero relayer");
        require(!isRelayer[relayer], "BridgeTarget: already relayer");
        isRelayer[relayer] = true;
        emit RelayerAdded(relayer);
    }

    /**
     * @notice 移除授权 relayer
     * @param relayer relayer 地址
     */
    function removeRelayer(address relayer) external onlyOwner {
        require(isRelayer[relayer], "BridgeTarget: not relayer");
        isRelayer[relayer] = false;
        emit RelayerRemoved(relayer);
    }

    // ==================== 核心函数 ====================

    /**
     * @notice relayer 签名验证后铸造 wrapped 资产给接收者。
     * @dev 签名消息哈希 = keccak256(abi.encodePacked(token, recipient, amount, nonce, "MINT"))。
     *   nonce 重复将 revert。
     * @param token     源链原始 ERC20 代币地址
     * @param recipient 接收者地址
     * @param amount    铸造金额
     * @param nonce     跨链交易 nonce（幂等键）
     * @param signature relayer 签名（65 字节 r+s+v）
     */
    function mint(
        address token,
        address recipient,
        uint256 amount,
        bytes32 nonce,
        bytes calldata signature
    ) external nonReentrant {
        require(token != address(0), "BridgeTarget: zero token");
        require(recipient != address(0), "BridgeTarget: zero recipient");
        require(amount > 0, "BridgeTarget: zero amount");
        require(!processedNonces[nonce], "BridgeTarget: nonce already processed");
        require(signature.length == 65, "BridgeTarget: invalid signature length");

        // 验证签名
        bytes32 messageHash = keccak256(
            abi.encodePacked(token, recipient, amount, nonce, "MINT")
        );
        bytes32 ethSignedHash = keccak256(
            abi.encodePacked("\x19Ethereum signed message:\n32", messageHash)
        );
        address signer = _recoverSigner(ethSignedHash, signature);
        require(isRelayer[signer], "BridgeTarget: signer not relayer");

        // 标记 nonce 已处理
        processedNonces[nonce] = true;

        // 铸造 wrapped 资产（内部账本）
        wrappedBalance[token][recipient] += amount;
        wrappedTotalSupply[token] += amount;

        emit Minted(token, recipient, amount, nonce, signer);
    }

    /**
     * @notice 用户销毁 wrapped 资产以赎回源链。
     * @dev nonce 重复将 revert。销毁后由 relayer 在源链上执行 unlock 释放原始资产。
     * @param token           源链原始 ERC20 代币地址
     * @param amount          销毁金额
     * @param nonce           跨链交易 nonce（幂等键）
     * @param targetRecipient 源链接收者地址
     */
    function burn(
        address token,
        uint256 amount,
        bytes32 nonce,
        address targetRecipient
    ) external nonReentrant {
        require(token != address(0), "BridgeTarget: zero token");
        require(amount > 0, "BridgeTarget: zero amount");
        require(targetRecipient != address(0), "BridgeTarget: zero target recipient");
        require(!processedNonces[nonce], "BridgeTarget: nonce already processed");
        require(wrappedBalance[token][msg.sender] >= amount, "BridgeTarget: insufficient wrapped balance");

        // 标记 nonce 已处理
        processedNonces[nonce] = true;

        // 销毁 wrapped 资产（内部账本）
        unchecked {
            wrappedBalance[token][msg.sender] -= amount;
            wrappedTotalSupply[token] -= amount;
        }

        emit Burned(token, msg.sender, targetRecipient, amount, nonce);
    }

    // ==================== 内部函数 ====================

    /**
     * @notice 从签名恢复签名者地址
     * @param hash      消息哈希（已加 EIP-191 前缀）
     * @param signature 65 字节签名
     * @return 签名者地址
     */
    function _recoverSigner(bytes32 hash, bytes calldata signature) internal pure returns (address) {
        bytes32 r;
        bytes32 s;
        uint8 v;
        // 内联解码 65 字节签名
        assembly {
            r := calldataload(signature.offset)
            s := calldataload(add(signature.offset, 32))
            v := byte(0, calldataload(add(signature.offset, 64)))
        }
        if (v < 27) {
            v += 27;
        }
        require(v == 27 || v == 28, "BridgeTarget: invalid v");
        return ecrecover(hash, v, r, s);
    }

    // ==================== View 函数 ====================

    /**
     * @notice 查询 nonce 是否已处理
     * @param nonce 跨链交易 nonce
     * @return true 表示已处理
     */
    function isNonceProcessed(bytes32 nonce) external view returns (bool) {
        return processedNonces[nonce];
    }

    /**
     * @notice 查询指定 token 与持有者的 wrapped 余额
     * @param token 源链原始 ERC20 代币地址
     * @param holder 持有者地址
     * @return wrapped 余额
     */
    function getWrappedBalance(address token, address holder) external view returns (uint256) {
        return wrappedBalance[token][holder];
    }
}