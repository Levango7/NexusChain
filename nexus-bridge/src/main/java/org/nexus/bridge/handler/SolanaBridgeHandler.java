package org.nexus.bridge.handler;

import org.nexus.bridge.BridgeConfig;
import org.nexus.bridge.BridgeException;
import org.nexus.bridge.BurnRequest;
import org.nexus.bridge.LockRequest;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.UnlockRequest;
import org.nexus.bridge.adapter.SolanaAdapter;
import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.solana.SolanaRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Solana 桥处理器，实现 Solana 网络上的跨链操作。
 *
 * <p>本处理器通过 {@link SolanaAdapter} 与 Solana 节点通信，
 * 调用部署在 Solana 上的 NexusChain Bridge Program 完成
 * 锁定（lock）、铸造（mint）、销毁（burn）、解锁（unlock）操作。</p>
 *
 * <h2>Bridge Program 指令</h2>
 * <pre>
 *   lock(user: Pubkey, target: [u8;32], amount: u64)
 *   mint(lock_tx_id: [u8;32], user: Pubkey, amount: u64, target: [u8;32])
 *   burn(user: Pubkey, target: [u8;32], amount: u64)
 *   unlock(burn_tx_id: [u8;32], user: Pubkey, amount: u64, target: [u8;32])
 * </pre>
 *
 * <h2>状态机</h2>
 * <pre>
 *   正向跨链:  LOCK_PENDING ─► LOCKED ─► MINT_PENDING ─► MINTED
 *   反向跨链:  BURN_PENDING ─► BURNED ─► UNLOCK_PENDING ─► UNLOCKED
 *
 *   异常终态:  FAILED / CANCELLED / TIMEOUT
 * </pre>
 *
 * <h2>与 EVM 处理器的差异</h2>
 * <ul>
 *   <li>账户地址为 base58 编码的 32 字节公钥，非 hex 编码的 20 字节地址</li>
 *   <li>「目标地址」target 为 32 字节（兼容 EVM 时左补零到 32 字节）</li>
 *   <li>指令数据采用 Solana 的 borsh 序列化，而非 EVM ABI 编码</li>
 *   <li>交易提交前先用 simulateTransaction 验证可执行（fail-fast）</li>
 * </ul>
 *
 * <h2>安全说明</h2>
 * <p>本处理器不直接持有私钥，私钥由 nexus-signing-service 托管。
 * 实际生产中应由签名服务构造已签名交易后通过 {@code sendTransaction} 提交。
 * 当前实现采用与 {@link EthereumBridgeHandler} 一致的策略：
 * <ol>
 *   <li>用 simulateTransaction 验证指令可执行</li>
 *   <li>对指令数据计算确定性 SHA-256 摘要作为合成交易哈希</li>
 * </ol>
 * </p>
 *
 * @since 2.0.0
 */
public class SolanaBridgeHandler extends AbstractBridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(SolanaBridgeHandler.class);

    /** Solana 链 ID。 */
    private static final String CHAIN_ID = "solana";

    /** Solana 推荐确认数（Solana finality 约 30 个 slot）。 */
    private static final int RECOMMENDED_CONFIRMATIONS = 32;

    /** Solana Bridge Program ID（base58 编码）。 */
    private String bridgeProgramId;

    /** SPL Token Mint 公钥（NEX 代币在 Solana 上的 mint）。 */
    private String nexusTokenMint;

    /** RPC 端点 URL。 */
    private String rpcEndpoint;

    /** Solana 适配器（懒加载）。 */
    private volatile SolanaAdapter solanaAdapter;

    /**
     * 构造 Solana 桥处理器。
     *
     * @param config 桥配置
     */
    public SolanaBridgeHandler(BridgeConfig config) {
        super(config);
    }

    /**
     * 构造 Solana 桥处理器（带程序地址）。
     *
     * @param config             桥配置
     * @param bridgeProgramId    Bridge Program ID（base58）
     * @param nexusTokenMint     NEX 代币 SPL Mint 公钥（base58）
     * @param rpcEndpoint        RPC 端点 URL
     */
    public SolanaBridgeHandler(BridgeConfig config, String bridgeProgramId,
                               String nexusTokenMint, String rpcEndpoint) {
        super(config);
        this.bridgeProgramId = bridgeProgramId;
        this.nexusTokenMint = nexusTokenMint;
        this.rpcEndpoint = rpcEndpoint;
    }

    @Override
    public String getChainId() {
        return CHAIN_ID;
    }

    @Override
    protected String submitLockTransaction(LockRequest request) throws BridgeException {
        log.info("Submitting lock transaction on Solana: amount={}, user={}",
                request.getAmount(), request.getUserAddress());
        ensureAdapter();
        // lock(user: Pubkey, target: [u8;32], amount: u64)
        byte[] instructionData = encodeLockInstruction(
                request.getUserAddress(),
                request.getTargetAddress(),
                request.getAmount());
        // simulateTransaction 验证（fail-fast）
        simulateInstruction(instructionData);
        // 生成确定性合成交易哈希
        return synthesizeTxHash(CHAIN_ID, HexFormat.of().formatHex(instructionData));
    }

    @Override
    protected String submitMintTransaction(MintRequest request,
                                           BridgeTransaction lockTx) throws BridgeException {
        log.info("Submitting mint transaction on Solana: lockTxId={}, minter={}",
                request.getLockTxId(), request.getMinterAddress());
        ensureAdapter();
        // mint(lock_tx_id: [u8;32], user: Pubkey, amount: u64, target: [u8;32])
        byte[] instructionData = encodeMintInstruction(
                request.getLockTxId(),
                lockTx.getUserAddress(),
                lockTx.getAmount(),
                lockTx.getTargetAddress());
        simulateInstruction(instructionData);
        return synthesizeTxHash(CHAIN_ID, HexFormat.of().formatHex(instructionData));
    }

    @Override
    protected String submitBurnTransaction(BurnRequest request) throws BridgeException {
        log.info("Submitting burn transaction on Solana: amount={}, user={}",
                request.getAmount(), request.getUserAddress());
        ensureAdapter();
        // burn(user: Pubkey, target: [u8;32], amount: u64)
        byte[] instructionData = encodeBurnInstruction(
                request.getUserAddress(),
                request.getTargetAddress(),
                request.getAmount());
        simulateInstruction(instructionData);
        return synthesizeTxHash(CHAIN_ID, HexFormat.of().formatHex(instructionData));
    }

    @Override
    protected String submitUnlockTransaction(UnlockRequest request,
                                             BridgeTransaction burnTx) throws BridgeException {
        log.info("Submitting unlock transaction on Solana: burnTxId={}, unlocker={}",
                request.getBurnTxId(), request.getUnlockerAddress());
        ensureAdapter();
        // unlock(burn_tx_id: [u8;32], user: Pubkey, amount: u64, target: [u8;32])
        byte[] instructionData = encodeUnlockInstruction(
                request.getBurnTxId(),
                burnTx.getUserAddress(),
                burnTx.getAmount(),
                burnTx.getTargetAddress());
        simulateInstruction(instructionData);
        return synthesizeTxHash(CHAIN_ID, HexFormat.of().formatHex(instructionData));
    }

    @Override
    public int queryTransactionStatus(String txHash) {
        log.debug("Querying transaction status on Solana: txHash={}", txHash);
        ensureAdapter();
        // Solana 通过 getTransaction 查询；若返回 null 表示未确认
        Object tx = solanaAdapter.getTransactionReceipt(txHash);
        if (tx == null) {
            return 0;
        }
        // 简化处理：交易存在即视为已确认（实际应比较 slot 与当前 slot）
        return RECOMMENDED_CONFIRMATIONS;
    }

    /**
     * 等待交易达到 Solana 推荐确认数（32）。
     *
     * <p>简化实现：直接查询交易是否存在，存在即视为达到确认数。</p>
     *
     * @param txHash 交易哈希
     * @return 达到确认数返回 true；失败返回 false
     */
    public boolean awaitConfirmations(String txHash) {
        ensureAdapter();
        Object tx = solanaAdapter.getTransactionReceipt(txHash);
        return tx != null;
    }

    /**
     * 获取 Solana 推荐确认数。
     *
     * @return 推荐确认数
     */
    public int getRecommendedConfirmations() {
        return RECOMMENDED_CONFIRMATIONS;
    }

    // ==================== 指令编码（borsh 简化版） ====================

    /**
     * 编码 lock 指令数据。
     *
     * <p>格式：[1 字节指令标识][32 字节 user pubkey][32 字节 target][8 字节 amount LE]</p>
     *
     * @param userPubkey 用户公钥（base58）
     * @param target     目标地址（base58 或 hex，统一解码为 32 字节）
     * @param amount     锁定金额
     * @return 指令字节数组
     * @throws BridgeException 如果地址解码失败
     */
    private byte[] encodeLockInstruction(String userPubkey, String target, long amount) throws BridgeException {
        byte[] userBytes = decodePubkey(userPubkey, "user");
        byte[] targetBytes = decodeTarget(target);
        return packInstruction((byte) 0x01, userBytes, targetBytes, amount);
    }

    /**
     * 编码 mint 指令数据。
     *
     * @param lockTxId 锁定交易 ID
     * @param userPubkey 用户公钥（base58）
     * @param amount     铸造金额
     * @param target     目标地址
     * @return 指令字节数组
     * @throws BridgeException 如果解码失败
     */
    private byte[] encodeMintInstruction(String lockTxId, String userPubkey,
                                         long amount, String target) throws BridgeException {
        byte[] lockIdBytes = decodeTxId(lockTxId, "lockTxId");
        byte[] userBytes = decodePubkey(userPubkey, "user");
        byte[] targetBytes = decodeTarget(target);
        // mint 指令：[标识][lock_tx_id 32B][user 32B][amount 8B][target 32B]
        byte[] data = new byte[1 + 32 + 32 + 8 + 32];
        int offset = 0;
        data[offset++] = 0x02;
        System.arraycopy(lockIdBytes, 0, data, offset, 32); offset += 32;
        System.arraycopy(userBytes, 0, data, offset, 32); offset += 32;
        writeLongLE(data, offset, amount); offset += 8;
        System.arraycopy(targetBytes, 0, data, offset, 32);
        return data;
    }

    /**
     * 编码 burn 指令数据。
     *
     * @param userPubkey 用户公钥（base58）
     * @param target     目标地址
     * @param amount     销毁金额
     * @return 指令字节数组
     * @throws BridgeException 如果解码失败
     */
    private byte[] encodeBurnInstruction(String userPubkey, String target, long amount) throws BridgeException {
        byte[] userBytes = decodePubkey(userPubkey, "user");
        byte[] targetBytes = decodeTarget(target);
        return packInstruction((byte) 0x03, userBytes, targetBytes, amount);
    }

    /**
     * 编码 unlock 指令数据。
     *
     * @param burnTxId 销毁交易 ID
     * @param userPubkey 用户公钥（base58）
     * @param amount     解锁金额
     * @param target     目标地址
     * @return 指令字节数组
     * @throws BridgeException 如果解码失败
     */
    private byte[] encodeUnlockInstruction(String burnTxId, String userPubkey,
                                           long amount, String target) throws BridgeException {
        byte[] burnIdBytes = decodeTxId(burnTxId, "burnTxId");
        byte[] userBytes = decodePubkey(userPubkey, "user");
        byte[] targetBytes = decodeTarget(target);
        byte[] data = new byte[1 + 32 + 32 + 8 + 32];
        int offset = 0;
        data[offset++] = 0x04;
        System.arraycopy(burnIdBytes, 0, data, offset, 32); offset += 32;
        System.arraycopy(userBytes, 0, data, offset, 32); offset += 32;
        writeLongLE(data, offset, amount); offset += 8;
        System.arraycopy(targetBytes, 0, data, offset, 32);
        return data;
    }

    /**
     * 打包 lock/burn 指令：[标识][user 32B][target 32B][amount 8B]。
     *
     * @param opcode 指令标识
     * @param user   用户公钥字节
     * @param target 目标地址字节
     * @param amount 金额
     * @return 指令字节数组
     */
    private byte[] packInstruction(byte opcode, byte[] user, byte[] target, long amount) {
        byte[] data = new byte[1 + 32 + 32 + 8];
        int offset = 0;
        data[offset++] = opcode;
        System.arraycopy(user, 0, data, offset, 32); offset += 32;
        System.arraycopy(target, 0, data, offset, 32); offset += 32;
        writeLongLE(data, offset, amount);
        return data;
    }

    /**
     * 解码 base58 公钥为 32 字节。
     *
     * @param pubkey base58 公钥
     * @param label  字段名（用于错误信息）
     * @return 32 字节
     * @throws BridgeException 如果解码失败或长度不符
     */
    private byte[] decodePubkey(String pubkey, String label) throws BridgeException {
        try {
            byte[] bytes = org.nexus.bridge.solana.Base58.decode(pubkey);
            if (bytes == null || bytes.length != 32) {
                throw new BridgeException("INVALID_PUBKEY",
                        label + " must decode to 32 bytes, got " + (bytes == null ? 0 : bytes.length));
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new BridgeException("INVALID_PUBKEY", "Failed to decode " + label + ": " + e.getMessage(), e);
        }
    }

    /**
     * 解码目标地址为 32 字节。
     *
     * <p>支持 base58（Solana 原生地址）与 hex（EVM 地址左补零到 32 字节）两种格式。</p>
     *
     * @param target 目标地址
     * @return 32 字节
     * @throws BridgeException 如果解码失败
     */
    private byte[] decodeTarget(String target) throws BridgeException {
        if (target == null || target.isEmpty()) {
            throw new BridgeException("INVALID_ADDRESS", "Target address must not be empty");
        }
        // 尝试 base58
        try {
            byte[] bytes = org.nexus.bridge.solana.Base58.decode(target);
            if (bytes != null && bytes.length == 32) {
                return bytes;
            }
        } catch (IllegalArgumentException ignored) {
            // 非 base58，继续尝试 hex
        }
        // 尝试 hex（0x 开头或纯 hex）
        String hex = target.startsWith("0x") ? target.substring(2) : target;
        try {
            byte[] bytes = HexFormat.of().parseHex(hex);
            // EVM 地址 20 字节，左补零到 32 字节
            if (bytes.length == 20) {
                byte[] padded = new byte[32];
                System.arraycopy(bytes, 0, padded, 12, 20);
                return padded;
            }
            if (bytes.length == 32) {
                return bytes;
            }
            throw new BridgeException("INVALID_ADDRESS",
                    "Target hex must be 20 or 32 bytes, got " + bytes.length);
        } catch (IllegalArgumentException e) {
            throw new BridgeException("INVALID_ADDRESS",
                    "Target is neither valid base58 nor hex: " + target, e);
        }
    }

    /**
     * 解码交易 ID 为 32 字节。
     *
     * <p>交易 ID 可以是 base58（Solana 签名）或 hex（EVM 哈希）。</p>
     *
     * @param txId  交易 ID
     * @param label 字段名
     * @return 32 字节
     * @throws BridgeException 如果解码失败
     */
    private byte[] decodeTxId(String txId, String label) throws BridgeException {
        if (txId == null || txId.isEmpty()) {
            throw new BridgeException("INVALID_TX_ID", label + " must not be empty");
        }
        // 尝试 base58
        try {
            byte[] bytes = org.nexus.bridge.solana.Base58.decode(txId);
            if (bytes != null && bytes.length == 32) {
                return bytes;
            }
        } catch (IllegalArgumentException ignored) {
            // 非 base58
        }
        // 尝试 hex
        String hex = txId.startsWith("0x") ? txId.substring(2) : txId;
        try {
            byte[] bytes = HexFormat.of().parseHex(hex);
            if (bytes.length == 32) {
                return bytes;
            }
            // EVM 哈希也是 32 字节，若不是则报错
            throw new BridgeException("INVALID_TX_ID",
                    label + " hex must be 32 bytes, got " + bytes.length);
        } catch (IllegalArgumentException e) {
            throw new BridgeException("INVALID_TX_ID",
                    label + " is neither valid base58 nor hex: " + txId, e);
        }
    }

    /**
     * 以小端序写入 long 到字节数组的指定位置。
     *
     * @param data   目标数组
     * @param offset 起始偏移
     * @param value  待写入值
     */
    private static void writeLongLE(byte[] data, int offset, long value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
        data[offset + 4] = (byte) ((value >> 32) & 0xFF);
        data[offset + 5] = (byte) ((value >> 40) & 0xFF);
        data[offset + 6] = (byte) ((value >> 48) & 0xFF);
        data[offset + 7] = (byte) ((value >> 56) & 0xFF);
    }

    /**
     * 用 simulateTransaction 验证指令可执行（fail-fast）。
     *
     * <p>由于本模块不持有私钥，无法构造完整已签名交易。
     * 此处将指令数据视为「模拟输入」调用 simulateTransaction，
     * 若返回 err 则抛 {@link BridgeException}。</p>
     *
     * @param instructionData 指令数据
     * @throws BridgeException 如果模拟失败
     */
    private void simulateInstruction(byte[] instructionData) throws BridgeException {
        String base64Tx = java.util.Base64.getEncoder().encodeToString(instructionData);
        SolanaRpcClient.SimulationResult result = solanaAdapter.simulateTransaction(base64Tx);
        if (result == null) {
            throw new BridgeException("SIMULATE_FAILED",
                    "simulateTransaction returned null for instruction on chain " + CHAIN_ID);
        }
        if (!result.success()) {
            throw new BridgeException("SIMULATE_REJECTED",
                    "simulateTransaction rejected: " + result.err);
        }
        log.debug("simulateTransaction passed: unitsConsumed={}", result.unitsConsumed);
    }

    /**
     * 懒加载 Solana 适配器。
     *
     * @throws BridgeException 如果 RPC 端点未配置
     */
    private void ensureAdapter() throws BridgeException {
        if (solanaAdapter != null) {
            return;
        }
        if (rpcEndpoint == null || rpcEndpoint.isEmpty()) {
            throw new BridgeException("RPC_NOT_CONFIGURED",
                    "Solana RPC endpoint is not configured");
        }
        synchronized (this) {
            if (solanaAdapter == null) {
                solanaAdapter = new SolanaAdapter(
                        CHAIN_ID,
                        SolanaAdapter.DEFAULT_SPL_TOKEN_PROGRAM,
                        new SolanaRpcClient(rpcEndpoint));
                log.info("Initialized SolanaAdapter for Solana: {}", rpcEndpoint);
            }
        }
    }

    // ==================== Getter / Setter ====================

    /**
     * 获取 Bridge Program ID。
     *
     * @return Program ID
     */
    public String getBridgeProgramId() {
        return bridgeProgramId;
    }

    /**
     * 设置 Bridge Program ID。
     *
     * @param bridgeProgramId Program ID
     */
    public void setBridgeProgramId(String bridgeProgramId) {
        this.bridgeProgramId = bridgeProgramId;
    }

    /**
     * 获取 NEX 代币 SPL Mint 公钥。
     *
     * @return Mint 公钥
     */
    public String getNexusTokenMint() {
        return nexusTokenMint;
    }

    /**
     * 设置 NEX 代币 SPL Mint 公钥。
     *
     * @param nexusTokenMint Mint 公钥
     */
    public void setNexusTokenMint(String nexusTokenMint) {
        this.nexusTokenMint = nexusTokenMint;
    }

    /**
     * 获取 RPC 端点 URL。
     *
     * @return RPC 端点
     */
    public String getRpcEndpoint() {
        return rpcEndpoint;
    }

    /**
     * 设置 RPC 端点 URL，重置适配器。
     *
     * @param rpcEndpoint RPC 端点
     */
    public void setRpcEndpoint(String rpcEndpoint) {
        this.rpcEndpoint = rpcEndpoint;
        this.solanaAdapter = null;
    }

    /**
     * 注入 Solana 适配器（仅供测试使用）。
     *
     * @param adapter Solana 适配器
     */
    void setSolanaAdapter(SolanaAdapter adapter) {
        this.solanaAdapter = adapter;
        if (adapter != null && adapter.getRpcClient() != null) {
            this.rpcEndpoint = adapter.getRpcClient().getRpcEndpoint();
        }
    }
}