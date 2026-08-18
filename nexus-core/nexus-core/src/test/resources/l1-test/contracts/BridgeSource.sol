// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.20;

/**
 * @title BridgeSource
 * @author NexusChain
 * @notice 跨链桥源链合约，负责锁定（lock）与释放（unlock）用户资产。
 * @dev 部署在源链上，由 Java 侧 EthereumBridgeHandler 通过 Web3j 调用。
 *
 * 核心函数：
 * - lock(token, recipient, amount, targetChainId, nonce) — 用户锁定 ERC20 资产，发起跨链转账
 * - unlock(token, recipient, amount, nonce, signature) — relayer 签名验证后释放资产
 *
 * 安全机制：
 * - nonce 幂等性：mapping(bytes32 => bool) processedNonces 去重
 * - 签名验证：ECDSA recover，验证授权 relayer 签名
 * - 角色控制：owner 管理授权 relayer 集合
 * - 重入锁：简单 bool 变量防止重入
 *
 * 事件：
 * - Locked — 资产锁定
 * - Unlocked — 资产释放
 * - RelayerAdded — relayer 添加
 * - RelayerRemoved — relayer 移除
 */
contract BridgeSource {
    // ==================== 事件 ====================

    /// @notice 资产锁定事件
    /// @param token          锁定的 ERC20 代币地址
    /// @param locker         锁定者（用户）地址
    /// @param recipient      目标链接收者地址
    /// @param amount         锁定金额
    /// @param targetChainId  目标链 ID
    /// @param nonce          跨链交易 nonce（幂等键）
    event Locked(
        address indexed token,
        address indexed locker,
        address recipient,
        uint256 amount,
        uint256 targetChainId,
        bytes32 nonce
    );

    /// @notice 资产释放事件
    /// @param token     释放的 ERC20 代币地址
    /// @param recipient 接收者地址
    /// @param amount    释放金额
    /// @param nonce     跨链交易 nonce（幂等键）
    /// @param relayer   触发释放的 relayer 地址
    event Unlocked(
        address indexed token,
        address indexed recipient,
        uint256 amount,
        bytes32 indexed nonce,
        address relayer
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

    /// @notice 重入锁状态
    bool private _locked;

    // ==================== 修饰符 ====================

    /// @dev 仅 owner 可调用
    modifier onlyOwner() {
        require(msg.sender == owner, "BridgeSource: not owner");
        _;
    }

    /// @dev 重入保护
    modifier nonReentrant() {
        require(!_locked, "BridgeSource: reentrant call");
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
        require(relayer != address(0), "BridgeSource: zero relayer");
        require(!isRelayer[relayer], "BridgeSource: already relayer");
        isRelayer[relayer] = true;
        emit RelayerAdded(relayer);
    }

    /**
     * @notice 移除授权 relayer
     * @param relayer relayer 地址
     */
    function removeRelayer(address relayer) external onlyOwner {
        require(isRelayer[relayer], "BridgeSource: not relayer");
        isRelayer[relayer] = false;
        emit RelayerRemoved(relayer);
    }

    // ==================== 核心函数 ====================

    /**
     * @notice 用户锁定 ERC20 资产，发起跨链转账。
     * @dev 调用前用户需对桥合约执行 ERC20.approve。nonce 重复将 revert。
     * @param token         锁定的 ERC20 代币地址
     * @param recipient     目标链接收者地址
     * @param amount        锁定金额
     * @param targetChainId 目标链 ID
     * @param nonce         跨链交易 nonce（幂等键，由链下协调器生成）
     */
    function lock(
        address token,
        address recipient,
        uint256 amount,
        uint256 targetChainId,
        bytes32 nonce
    ) external nonReentrant {
        require(token != address(0), "BridgeSource: zero token");
        require(recipient != address(0), "BridgeSource: zero recipient");
        require(amount > 0, "BridgeSource: zero amount");
        require(!processedNonces[nonce], "BridgeSource: nonce already processed");

        // 标记 nonce 已处理（先标记后转账，防止 ERC20 钩子重入绕过幂等）
        processedNonces[nonce] = true;

        // ERC20 转入本合约
        bool ok = IERC20(token).transferFrom(msg.sender, address(this), amount);
        require(ok, "BridgeSource: transferFrom failed");

        emit Locked(token, msg.sender, recipient, amount, targetChainId, nonce);
    }

    /**
     * @notice relayer 签名验证后释放资产给指定接收者。
     * @dev 签名消息哈希 = keccak256(abi.encodePacked(token, recipient, amount, nonce, "UNLOCK"))。
     *   nonce 重复将 revert。
     * @param token     释放的 ERC20 代币地址
     * @param recipient 接收者地址
     * @param amount    释放金额
     * @param nonce     跨链交易 nonce（幂等键）
     * @param signature relayer 签名（65 字节 r+s+v）
     */
    function unlock(
        address token,
        address recipient,
        uint256 amount,
        bytes32 nonce,
        bytes calldata signature
    ) external nonReentrant {
        require(token != address(0), "BridgeSource: zero token");
        require(recipient != address(0), "BridgeSource: zero recipient");
        require(amount > 0, "BridgeSource: zero amount");
        require(!processedNonces[nonce], "BridgeSource: nonce already processed");
        require(signature.length == 65, "BridgeSource: invalid signature length");

        // 验证签名
        bytes32 messageHash = keccak256(
            abi.encodePacked(token, recipient, amount, nonce, "UNLOCK")
        );
        bytes32 ethSignedHash = keccak256(
            abi.encodePacked("\x19Ethereum signed message:\n32", messageHash)
        );
        address signer = _recoverSigner(ethSignedHash, signature);
        require(isRelayer[signer], "BridgeSource: signer not relayer");

        // 标记 nonce 已处理
        processedNonces[nonce] = true;

        // ERC20 转出本合约
        bool ok = IERC20(token).transfer(recipient, amount);
        require(ok, "BridgeSource: transfer failed");

        emit Unlocked(token, recipient, amount, nonce, signer);
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
        require(v == 27 || v == 28, "BridgeSource: invalid v");
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
}

/**
 * @title IERC20
 * @notice 最小 ERC20 接口，供桥合约调用
 */
interface IERC20 {
    function transfer(address to, uint256 amount) external returns (bool);
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
}