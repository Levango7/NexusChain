package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.utils.Numeric;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;

import org.nexus.l2.abi.Withdrawal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * L1 合约客户端——Web3j 真实 L1 合约交互实现。
 *
 * <p>通过 Web3j JSON-RPC 调用 L1 桥合约，实现：</p>
 * <ul>
 *   <li>{@code submitStateRoot(uint256 batchId, bytes32 stateRoot)} — 状态根提交</li>
 *   <li>{@code markBatchVerified(uint256 batchId)} — 批次验证标记</li>
 *   <li>{@code finalizeWithdraws(uint256 batchId)} — 批次提款 finalize</li>
 *   <li>{@code challengeBatch(uint256 batchId, bytes proofData)} — 批次挑战</li>
 * </ul>
 *
 * <h2>配置</h2>
 * <p>支持两套等价配置命名空间（P5-T6 引入 {@code nexus.l2.l1.*}，向后兼容
 * {@code nexus.l2.l1-bridge.*}）：</p>
 * <pre>
 * # 新命名空间（P5-T6，推荐）
 * nexus.l2.l1.real-l1-enabled=true
 * nexus.l2.l1.rpc-url=http://localhost:8545
 * nexus.l2.l1.bridge-contract-address=0x...
 * nexus.l2.l1.from-address=0x...
 * nexus.l2.l1.private-key=0x...
 * nexus.l2.l1.chain-id=31337
 * nexus.l2.l1.gas-limit=500000
 * nexus.l2.l1.gas-price=1000000000
 * nexus.l2.l1.challenge-period=604800
 *
 * # 旧命名空间（向后兼容，application.properties 中通过 ${nexus.l2.l1.*} 联动）
 * nexus.l2.l1-bridge.enabled=true
 * nexus.l2.l1-bridge.rpc-endpoint=http://localhost:8545
 * nexus.l2.l1-bridge.contract-address=0x...
 * ...
 * </pre>
 *
 * <h2>切换逻辑</h2>
 * <p>当 {@code nexus.l2.l1-bridge.enabled=true}（等价于
 * {@code nexus.l2.l1.real-l1-enabled=true}）时，Spring 注入本类替代
 * {@link L1ContractClient}（内存模拟）。两套配置通过 application.properties
 * 中的占位符引用联动，设置任一即可。</p>
 *
 * <h2>Fallback 策略</h2>
 * <p>当 Web3j 调用失败（RPC 异常、交易被拒、配置缺失）时，回退到内存模拟
 * （调用 {@code super} 方法），保证 L2 流程不因 L1 不可用而中断。
 * 详见审计报告 §3.4 / 任务 #83。</p>
 *
 * <h2>编程式初始化</h2>
 * <p>除 Spring 注入外，可通过 {@link #createForTesting(String, String, String, long)}
 * 工厂方法构造实例，用于端到端测试（参见
 * {@code org.nexus.l2.integration.L2L1EndToEndTest}）。</p>
 *
 * @since 1.4
 */
@Component
@ConditionalOnProperty(prefix = "nexus.l2.l1-bridge", name = "enabled", havingValue = "true")
public class Web3jL1ContractClient extends L1ContractClient {

    private static final Logger logger = LoggerFactory.getLogger(Web3jL1ContractClient.class);

    /** 默认 gas 上限 */
    private static final long DEFAULT_GAS_LIMIT = 500_000L;

    /** 默认 gas price（1 Gwei） */
    private static final long DEFAULT_GAS_PRICE = 1_000_000_000L;

    /** 交易回执轮询间隔（毫秒） */
    private static final long RECEIPT_POLL_INTERVAL_MS = 1000L;

    /** 交易回执轮询次数 */
    private static final int RECEIPT_POLL_ATTEMPTS = 40;

    @Value("${nexus.l2.l1-bridge.rpc-endpoint:}")
    private String rpcEndpoint;

    @Value("${nexus.l2.l1-bridge.contract-address:}")
    private String contractAddress;

    @Value("${nexus.l2.l1-bridge.from-address:}")
    private String fromAddress;

    @Value("${nexus.l2.l1-bridge.private-key:}")
    private String privateKey;

    @Value("${nexus.l2.l1-bridge.chain-id:1}")
    private long chainId;

    @Value("${nexus.l2.l1-bridge.gas-limit:" + DEFAULT_GAS_LIMIT + "}")
    private long gasLimit;

    @Value("${nexus.l2.l1-bridge.gas-price:" + DEFAULT_GAS_PRICE + "}")
    private long gasPrice;

    /** 挑战期（秒，P5-T6 新增，仅供配置文档化与未来扩展，当前未强制使用） */
    @Value("${nexus.l2.l1-bridge.challenge-period:604800}")
    private long challengePeriod;

    /** Web3j 客户端实例，@PostConstruct 中初始化 */
    private Web3j web3j;

    /** 交易管理器（用 Credentials 签名） */
    private RawTransactionManager txManager;

    /** 是否成功初始化 Web3j（false 时所有调用回退内存模拟） */
    private volatile boolean web3jReady = false;

    /**
     * 编程式构造实例（用于端到端测试，绕过 Spring 注入）。
     *
     * <p>构造后需调用 {@link #init()} 完成初始化。</p>
     *
     * @param rpcEndpoint     L1 JSON-RPC 端点
     * @param contractAddress L2 桥合约地址
     * @param privateKey      发送方私钥（0x 前缀 32 字节 hex）
     * @param chainId         L1 链 ID
     * @return 未初始化的 {@code Web3jL1ContractClient} 实例
     * @since 2.0
     */
    public static Web3jL1ContractClient createForTesting(
            String rpcEndpoint, String contractAddress, String privateKey, long chainId) {
        Web3jL1ContractClient client = new Web3jL1ContractClient();
        client.rpcEndpoint = rpcEndpoint;
        client.contractAddress = contractAddress;
        client.privateKey = privateKey;
        client.chainId = chainId;
        client.gasLimit = DEFAULT_GAS_LIMIT;
        client.gasPrice = DEFAULT_GAS_PRICE;
        client.challengePeriod = 604800L;
        return client;
    }

    /**
     * 初始化 Web3j 客户端与交易管理器。
     *
     * <p>校验 rpcEndpoint / contractAddress / privateKey 非空，任一缺失则
     * 标记 {@code web3jReady=false}，后续调用回退内存模拟。</p>
     */
    @PostConstruct
    public void init() {
        if (rpcEndpoint == null || rpcEndpoint.isEmpty()) {
            logger.warn("L1 bridge Web3j disabled: rpc-endpoint not configured, fallback to in-memory simulation");
            return;
        }
        if (contractAddress == null || contractAddress.isEmpty()) {
            logger.warn("L1 bridge Web3j disabled: contract-address not configured, fallback to in-memory simulation");
            return;
        }
        if (privateKey == null || privateKey.isEmpty()) {
            logger.warn("L1 bridge Web3j disabled: private-key not configured, fallback to in-memory simulation");
            return;
        }
        try {
            this.web3j = Web3j.build(new HttpService(rpcEndpoint));
            Credentials credentials = Credentials.create(privateKey);
            this.txManager = new RawTransactionManager(
                    web3j, credentials, chainId,
                    new PollingTransactionReceiptProcessor(web3j, RECEIPT_POLL_INTERVAL_MS, RECEIPT_POLL_ATTEMPTS));
            // 用 fromAddress 覆盖 credentials 推导的地址（允许显式指定）
            if (fromAddress == null || fromAddress.isEmpty()) {
                fromAddress = credentials.getAddress();
            }
            this.web3jReady = true;
            logger.info("L1 bridge Web3j initialized: endpoint={}, contract={}, from={}, chainId={}",
                    rpcEndpoint, contractAddress, fromAddress, chainId);
        } catch (Exception e) {
            logger.error("Failed to initialize L1 bridge Web3j client, fallback to in-memory simulation: {}", e.getMessage(), e);
            this.web3jReady = false;
        }
    }

    /**
     * 销毁时关闭 Web3j 客户端，释放底层 HTTP 连接资源。
     */
    @PreDestroy
    public void shutdown() {
        if (web3j != null) {
            web3j.shutdown();
            logger.info("L1 bridge Web3j client shutdown");
        }
    }

    /**
     * 查询 Web3j 客户端是否已成功初始化（用于测试与诊断）。
     *
     * @return true 表示 Web3j 已就绪，false 表示将回退内存模拟
     * @since 2.0
     */
    public boolean isWeb3jReady() {
        return web3jReady;
    }

    @Override
    public boolean submitStateRootToL1(long batchId, String root) {
        if (root == null) {
            return false;
        }
        if (!web3jReady) {
            return super.submitStateRootToL1(batchId, root);
        }
        try {
            // 编码 stateRoot 为 bytes32
            byte[] rootBytes = Numeric.hexStringToByteArray(root);
            if (rootBytes.length != 32) {
                logger.warn("State root for batch {} is not 32 bytes (got {}), fallback to in-memory", batchId, rootBytes.length);
                return super.submitStateRootToL1(batchId, root);
            }
            // 参数顺序与 L2Bridge.sol 的 submitStateRoot(bytes32 stateRoot, uint256 batchId) 一致
            Function function = new Function(
                    "submitStateRoot",
                    Arrays.asList(
                            new Bytes32(rootBytes),
                            new Uint256(BigInteger.valueOf(batchId))),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendTransaction(function, "submitStateRoot", batchId);
            if (txHash == null) {
                logger.warn("submitStateRoot tx failed for batch {}, fallback to in-memory", batchId);
                return super.submitStateRootToL1(batchId, root);
            }
            // 内存同步记录（便于本地查询）
            l1StateRoots.put(batchId, root);
            logger.info("State root submitted to L1 contract for batch {} root={} txHash={}", batchId, root, txHash);
            return true;
        } catch (Exception e) {
            logger.error("submitStateRootToL1 Web3j call failed for batch {}, fallback to in-memory: {}", batchId, e.getMessage(), e);
            return super.submitStateRootToL1(batchId, root);
        }
    }

    @Override
    public boolean markBatchVerifiedOnL1(long batchId) {
        if (!web3jReady) {
            return super.markBatchVerifiedOnL1(batchId);
        }
        try {
            Function function = new Function(
                    "markBatchVerified",
                    Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendTransaction(function, "markBatchVerified", batchId);
            if (txHash == null) {
                logger.warn("markBatchVerified tx failed for batch {}, fallback to in-memory", batchId);
                return super.markBatchVerifiedOnL1(batchId);
            }
            l1FinalizedBatches.put(batchId, true);
            logger.info("Batch {} marked VERIFIED on L1 contract txHash={}", batchId, txHash);
            return true;
        } catch (Exception e) {
            logger.error("markBatchVerifiedOnL1 Web3j call failed for batch {}, fallback to in-memory: {}", batchId, e.getMessage(), e);
            return super.markBatchVerifiedOnL1(batchId);
        }
    }

    @Override
    public boolean finalizeWithdrawsOnL1(long batchId) {
        if (!web3jReady) {
            return super.finalizeWithdrawsOnL1(batchId);
        }
        try {
            Function function = new Function(
                    "finalizeWithdraws",
                    Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendTransaction(function, "finalizeWithdraws", batchId);
            if (txHash == null) {
                logger.warn("finalizeWithdraws tx failed for batch {}, fallback to in-memory", batchId);
                return super.finalizeWithdrawsOnL1(batchId);
            }
            l1FinalizedWithdraws.put(batchId, true);
            logger.info("Withdraws finalized on L1 contract for batch {} txHash={}", batchId, txHash);
            return true;
        } catch (Exception e) {
            logger.error("finalizeWithdrawsOnL1 Web3j call failed for batch {}, fallback to in-memory: {}", batchId, e.getMessage(), e);
            return super.finalizeWithdrawsOnL1(batchId);
        }
    }

    @Override
    public boolean challengeBatchOnL1(long batchId, byte[] proofData) {
        if (proofData == null || proofData.length == 0) {
            logger.warn("Cannot challenge batch {} on L1: empty proof data", batchId);
            return false;
        }
        if (!web3jReady) {
            return super.challengeBatchOnL1(batchId, proofData);
        }
        try {
            // L2Bridge.sol 的 challengeBatch(uint256 batchId, bytes32[] calldata proof)
            // 将 byte[] 转换为 bytes32[]：每 32 字节为一个元素，不足 32 字节右侧零填充
            int numElements = (proofData.length + 31) / 32;
            List<Bytes32> proofList = new ArrayList<>(numElements);
            for (int i = 0; i < numElements; i++) {
                byte[] element = new byte[32];
                int offset = i * 32;
                int length = Math.min(32, proofData.length - offset);
                System.arraycopy(proofData, offset, element, 0, length);
                proofList.add(new Bytes32(element));
            }
            Function function = new Function(
                    "challengeBatch",
                    Arrays.asList(
                            new Uint256(BigInteger.valueOf(batchId)),
                            new org.web3j.abi.datatypes.DynamicArray<>(Bytes32.class, proofList)),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendTransaction(function, "challengeBatch", batchId);
            if (txHash == null) {
                logger.warn("challengeBatch tx failed for batch {}, fallback to in-memory", batchId);
                return super.challengeBatchOnL1(batchId, proofData);
            }
            l1ChallengedBatches.put(batchId, true);
            logger.info("Batch {} challenged on L1 contract txHash={} (proofSize={})", batchId, txHash, proofData.length);
            return true;
        } catch (Exception e) {
            logger.error("challengeBatchOnL1 Web3j call failed for batch {}, fallback to in-memory: {}", batchId, e.getMessage(), e);
            return super.challengeBatchOnL1(batchId, proofData);
        }
    }

    /**
     * 通过 RawTransactionManager 发送合约交易并等待回执。
     *
     * <p>流程：编码函数 → 获取 nonce/gasPrice → 签名 → ethSendRawTransaction → 等待回执。</p>
     *
     * @param function 合约函数调用
     * @param funcName 函数名（日志用）
     * @param batchId  批次 ID（日志用）
     * @return 交易哈希；失败返回 null
     */
    private String sendTransaction(Function function, String funcName, long batchId) throws IOException {
        String encodedFunction = FunctionEncoder.encode(function);
        BigInteger nonce = web3j.ethGetTransactionCount(
                fromAddress, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        BigInteger gasPriceBi = BigInteger.valueOf(gasPrice);
        // 尝试从链上获取 gas price（更准确），失败回退配置值
        try {
            BigInteger chainGasPrice = web3j.ethGasPrice().send().getGasPrice();
            if (chainGasPrice != null && chainGasPrice.signum() > 0) {
                gasPriceBi = chainGasPrice;
            }
        } catch (Exception e) {
            logger.debug("Failed to fetch chain gas price, using configured value {}: {}", gasPrice, e.getMessage());
        }
        RawTransaction rawTransaction = RawTransaction.createTransaction(
                nonce, gasPriceBi, BigInteger.valueOf(gasLimit), contractAddress, encodedFunction);

        EthSendTransaction sendResponse = txManager.signAndSend(rawTransaction);
        if (sendResponse.hasError()) {
            logger.error("eth_sendRawTransaction failed for {} batch {}: code={}, message={}",
                    funcName, batchId, sendResponse.getError().getCode(), sendResponse.getError().getMessage());
            return null;
        }
        String txHash = sendResponse.getTransactionHash();
        logger.debug("{} tx submitted for batch {} hash={}", funcName, batchId, txHash);
        return txHash;
    }

    /**
     * 只读调用合约函数（eth_call，不发送交易，用于 view/pure 函数）。
     *
     * <p>当前 L1 桥合约的 submitStateRoot/markBatchVerified/finalizeWithdraws/challengeBatch
     * 均为 state-changing 函数，不走此方法。保留供未来 view 函数查询使用。</p>
     *
     * @param function 合约函数调用
     * @return 调用返回的 hex 数据；失败返回 null
     */
    @SuppressWarnings("unused")
    private String callContractView(Function function) {
        String encodedFunction = FunctionEncoder.encode(function);
        try {
            Transaction call = Transaction.createEthCallTransaction(fromAddress, contractAddress, encodedFunction);
            EthCall response = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();
            if (response.hasError()) {
                logger.error("eth_call failed: code={}, message={}",
                        response.getError().getCode(), response.getError().getMessage());
                return null;
            }
            return response.getValue();
        } catch (IOException e) {
            logger.error("eth_call IO error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 等待交易回执确认（可选，txManager 已内置轮询，此方法用于显式等待）。
     *
     * @param txHash 交易哈希
     * @return 交易回执；失败或超时返回 null
     */
    @SuppressWarnings("unused")
    private TransactionReceipt awaitReceipt(String txHash) {
        try {
            return web3j.ethGetTransactionReceipt(txHash).send()
                    .getTransactionReceipt().orElse(null);
        } catch (IOException e) {
            logger.error("Failed to fetch receipt for {}: {}", txHash, e.getMessage());
            return null;
        }
    }

    // ==================== 生产级增强功能（L2Bridge v2 新增） ====================

    /**
     * 在 L1 上设置授权 Sequencer 地址。
     *
     * <p>对应 L2Bridge 合约 {@code setAuthorizedSequencer(address sequencer)} 函数。
     * 仅 owner 可调用。</p>
     *
     * @param sequencerAddress 授权 Sequencer 地址（hex，0x 前缀）
     * @return 设置成功返回 true
     * @since 2.1
     */
    public boolean setAuthorizedSequencerOnL1(String sequencerAddress) {
        if (!web3jReady) {
            logger.warn("setAuthorizedSequencer skipped: Web3j not ready");
            return false;
        }
        try {
            Function function = new Function(
                    "setAuthorizedSequencer",
                    Arrays.asList(new Address(sequencerAddress)),
                    Collections.<TypeReference<?>>emptyList());
            String txHash = sendTransaction(function, "setAuthorizedSequencer", -1L);
            if (txHash == null) {
                logger.warn("setAuthorizedSequencer tx failed for {}", sequencerAddress);
                return false;
            }
            logger.info("Authorized sequencer set on L1: {} txHash={}", sequencerAddress, txHash);
            return true;
        } catch (Exception e) {
            logger.error("setAuthorizedSequencerOnL1 failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 在 L1 上设置 Sequencer 与挑战者的质押金额要求。
     *
     * <p>对应 L2Bridge 合约 {@code setBonds(uint256 sequencerBond, uint256 challengerBond)} 函数。
     * 仅 owner 可调用。</p>
     *
     * @param sequencerBond  Sequencer 质押金额要求（wei）
     * @param challengerBond 挑战者质押金额要求（wei）
     * @return 设置成功返回 true
     * @since 2.1
     */
    public boolean setBondsOnL1(BigInteger sequencerBond, BigInteger challengerBond) {
        if (!web3jReady) {
            logger.warn("setBonds skipped: Web3j not ready");
            return false;
        }
        try {
            Function function = new Function(
                    "setBonds",
                    Arrays.asList(new Uint256(sequencerBond), new Uint256(challengerBond)),
                    Collections.<TypeReference<?>>emptyList());
            String txHash = sendTransaction(function, "setBonds", -1L);
            if (txHash == null) {
                logger.warn("setBonds tx failed");
                return false;
            }
            logger.info("Bonds set on L1: sequencer={} challenger={} txHash={}",
                    sequencerBond, challengerBond, txHash);
            return true;
        } catch (Exception e) {
            logger.error("setBondsOnL1 failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Sequencer 在 L1 上质押 bond。
     *
     * <p>对应 L2Bridge 合约 {@code depositSequencerBond()} payable 函数。
     * 仅授权 Sequencer 可调用，msg.value 必须等于 sequencerBondAmount。</p>
     *
     * @param bondAmount 质押金额（wei）
     * @return 质押成功返回 true
     * @since 2.1
     */
    public boolean depositSequencerBondOnL1(BigInteger bondAmount) {
        if (!web3jReady) {
            logger.warn("depositSequencerBond skipped: Web3j not ready");
            return false;
        }
        try {
            Function function = new Function(
                    "depositSequencerBond",
                    Collections.<Type>emptyList(),
                    Collections.<TypeReference<?>>emptyList());
            String txHash = sendTransactionWithValue(function, "depositSequencerBond", -1L, bondAmount);
            if (txHash == null) {
                logger.warn("depositSequencerBond tx failed");
                return false;
            }
            logger.info("Sequencer bond deposited on L1: amount={} txHash={}", bondAmount, txHash);
            return true;
        } catch (Exception e) {
            logger.error("depositSequencerBondOnL1 failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 挑战者在 L1 上质押 bond。
     *
     * <p>对应 L2Bridge 合约 {@code depositChallengerBond()} payable 函数。
     * msg.value 必须等于 challengerBondAmount。</p>
     *
     * @param bondAmount 质押金额（wei）
     * @return 质押成功返回 true
     * @since 2.1
     */
    public boolean depositChallengerBondOnL1(BigInteger bondAmount) {
        if (!web3jReady) {
            logger.warn("depositChallengerBond skipped: Web3j not ready");
            return false;
        }
        try {
            Function function = new Function(
                    "depositChallengerBond",
                    Collections.<Type>emptyList(),
                    Collections.<TypeReference<?>>emptyList());
            String txHash = sendTransactionWithValue(function, "depositChallengerBond", -1L, bondAmount);
            if (txHash == null) {
                logger.warn("depositChallengerBond tx failed");
                return false;
            }
            logger.info("Challenger bond deposited on L1: amount={} txHash={}", bondAmount, txHash);
            return true;
        } catch (Exception e) {
            logger.error("depositChallengerBondOnL1 failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 通过 EIP-712 签名提交状态根到 L1（生产级，Sequencer 签名验证）。
     *
     * <p>对应 L2Bridge 合约
     * {@code submitStateRootWithSig(bytes32 stateRoot, uint256 batchId, uint256 targetChainId, bytes signature)} 函数。
     * 签名者必须为 authorizedSequencer。</p>
     *
     * @param stateRoot     L2 状态根（hex，0x 前缀 32 字节）
     * @param batchId       批次 ID
     * @param targetChainId 目标链 ID（防跨链重放）
     * @param signature     Sequencer 的 65 字节 ECDSA 签名（hex，0x 前缀）
     * @return 提交成功返回 true
     * @since 2.1
     */
    public boolean submitStateRootWithSigToL1(
            String stateRoot, long batchId, long targetChainId, String signature) {
        if (stateRoot == null || signature == null) {
            return false;
        }
        if (!web3jReady) {
            logger.warn("submitStateRootWithSig skipped: Web3j not ready");
            return false;
        }
        try {
            byte[] rootBytes = Numeric.hexStringToByteArray(stateRoot);
            if (rootBytes.length != 32) {
                logger.warn("State root for batch {} is not 32 bytes (got {})", batchId, rootBytes.length);
                return false;
            }
            byte[] sigBytes = Numeric.hexStringToByteArray(signature);
            if (sigBytes.length != 65) {
                logger.warn("Signature for batch {} is not 65 bytes (got {})", batchId, sigBytes.length);
                return false;
            }
            Function function = new Function(
                    "submitStateRootWithSig",
                    Arrays.asList(
                            new Bytes32(rootBytes),
                            new Uint256(BigInteger.valueOf(batchId)),
                            new Uint256(BigInteger.valueOf(targetChainId)),
                            new DynamicBytes(sigBytes)),
                    Collections.<TypeReference<?>>emptyList());
            String txHash = sendTransaction(function, "submitStateRootWithSig", batchId);
            if (txHash == null) {
                logger.warn("submitStateRootWithSig tx failed for batch {}", batchId);
                return false;
            }
            l1StateRoots.put(batchId, stateRoot);
            logger.info("State root submitted with signature to L1 for batch {} root={} txHash={}",
                    batchId, stateRoot, txHash);
            return true;
        } catch (Exception e) {
            logger.error("submitStateRootWithSigToL1 failed for batch {}: {}", batchId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 在 L1 上提交批次提款根（仅 Sequencer，完整 Withdrawal[] 编码）。
     *
     * <p>对应 L2Bridge 合约
     * {@code submitWithdrawals(uint256 batchId, Withdrawal[] withdrawals, bytes32 withdrawalRoot)} 函数。</p>
     *
     * <p>本方法使用 {@link Withdrawal}（继承 {@code StaticStruct}）封装 Solidity
     * {@code struct Withdrawal { address token; address recipient; uint256 amount; }}，
     * 通过 {@link DynamicArray}&lt;Withdrawal&gt; 编码动态结构体数组，与 Solidity ABI
     * 规范一致（head/tail 编码）。</p>
     *
     * @param batchId        批次 ID
     * @param withdrawals    提款列表（{@link Withdrawal} 结构体列表，非空）
     * @param withdrawalRoot 提款 Merkle 根（hex，0x 前缀 32 字节）
     * @return 提交成功返回 true
     * @since 2.3
     */
    public boolean submitWithdrawalsToL1(long batchId, List<Withdrawal> withdrawals, String withdrawalRoot) {
        if (withdrawalRoot == null) {
            return false;
        }
        if (withdrawals == null || withdrawals.isEmpty()) {
            logger.warn("submitWithdrawals skipped: empty withdrawals for batch {}", batchId);
            return false;
        }
        if (!web3jReady) {
            logger.warn("submitWithdrawals skipped: Web3j not ready");
            return false;
        }
        try {
            byte[] rootBytes = Numeric.hexStringToByteArray(withdrawalRoot);
            if (rootBytes.length != 32) {
                logger.warn("Withdrawal root for batch {} is not 32 bytes (got {})", batchId, rootBytes.length);
                return false;
            }
            // 编码 Withdrawal[] 动态结构体数组：DynamicArray<Withdrawal>
            // Web3j 4.11.0 的 TypeEncoder.encodeDynamicArray 支持结构体数组的 head/tail 编码
            DynamicArray<Withdrawal> withdrawalArray = new DynamicArray<>(Withdrawal.class, withdrawals);
            Function function = new Function(
                    "submitWithdrawals",
                    Arrays.asList(
                            new Uint256(BigInteger.valueOf(batchId)),
                            withdrawalArray,
                            new Bytes32(rootBytes)),
                    Collections.<TypeReference<?>>emptyList());
            String txHash = sendTransaction(function, "submitWithdrawals", batchId);
            if (txHash == null) {
                logger.warn("submitWithdrawals tx failed for batch {}", batchId);
                return false;
            }
            logger.info("Withdrawals submitted to L1 for batch {} root={} count={} txHash={}",
                    batchId, withdrawalRoot, withdrawals.size(), txHash);
            return true;
        } catch (Exception e) {
            logger.error("submitWithdrawalsToL1 failed for batch {}: {}", batchId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 在 L1 上提交批次提款根（仅 Sequencer，向后兼容版本）。
     *
     * <p>对应 L2Bridge 合约
     * {@code submitWithdrawals(uint256 batchId, Withdrawal[] withdrawals, bytes32 withdrawalRoot)} 函数。</p>
     *
     * <p>本方法为向后兼容保留，不传递完整 Withdrawal[] 结构体数组（调用方仅有 withdrawalRoot
     * 时使用）。注意：L2Bridge 合约要求 {@code withdrawals.length > 0}，因此本方法
     * 实际无法通过合约校验，仅用于日志记录场景。生产环境应使用
     * {@link #submitWithdrawalsToL1(long, List, String)} 传递完整提款列表。</p>
     *
     * @param batchId        批次 ID
     * @param withdrawalRoot 提款 Merkle 根（hex，0x 前缀 32 字节）
     * @param withdrawalCount 提款笔数（用于日志）
     * @return 提交成功返回 true；由于空数组会被合约拒绝，实际总返回 false
     * @since 2.1
     * @deprecated 使用 {@link #submitWithdrawalsToL1(long, List, String)} 传递完整 Withdrawal 列表
     */
    @Deprecated
    public boolean submitWithdrawalsToL1(long batchId, String withdrawalRoot, int withdrawalCount) {
        if (withdrawalRoot == null) {
            return false;
        }
        if (!web3jReady) {
            logger.warn("submitWithdrawals skipped: Web3j not ready");
            return false;
        }
        logger.warn("submitWithdrawalsToL1(batchId, root, count) is deprecated and cannot pass contract validation "
                + "(empty withdrawals array rejected by L2Bridge). "
                + "Use submitWithdrawalsToL1(batchId, List<Withdrawal>, root) instead. batch={}", batchId);
        return false;
    }

    /**
     * 在 L1 上通过 Merkle Proof 挑战批次（生产级，含罚没机制）。
     *
     * <p>对应 L2Bridge 合约
     * {@code challengeBatchWithProof(uint256 batchId, bytes32 leaf, bytes32[] proof, bool[] isRight)} 函数。
     * 验证通过后罚没 sequencer bond 给挑战者。</p>
     *
     * @param batchId  批次 ID
     * @param leaf     叶子节点哈希（hex，0x 前缀 32 字节）
     * @param proof    Merkle 路径（每层兄弟节点哈希，hex 数组）
     * @param isRight  每层位置标记（true=leaf 在右侧，false=leaf 在左侧）
     * @return 挑战成功返回 true
     * @since 2.1
     */
    public boolean challengeBatchWithProofOnL1(
            long batchId, String leaf, String[] proof, boolean[] isRight) {
        if (leaf == null || proof == null || isRight == null) {
            return false;
        }
        if (proof.length != isRight.length) {
            logger.warn("challengeBatchWithProof proof length {} != isRight length {}",
                    proof.length, isRight.length);
            return false;
        }
        if (proof.length == 0) {
            logger.warn("challengeBatchWithProof empty proof for batch {}", batchId);
            return false;
        }
        if (!web3jReady) {
            logger.warn("challengeBatchWithProof skipped: Web3j not ready");
            return false;
        }
        try {
            byte[] leafBytes = Numeric.hexStringToByteArray(leaf);
            if (leafBytes.length != 32) {
                logger.warn("Leaf for batch {} is not 32 bytes (got {})", batchId, leafBytes.length);
                return false;
            }
            // 构建 proof 数组
            List<Bytes32> proofList = new ArrayList<>(proof.length);
            for (String p : proof) {
                byte[] pBytes = Numeric.hexStringToByteArray(p);
                if (pBytes.length != 32) {
                    logger.warn("Proof element for batch {} is not 32 bytes (got {})", batchId, pBytes.length);
                    return false;
                }
                proofList.add(new Bytes32(pBytes));
            }
            // 构建 isRight 数组
            List<Bool> isRightList = new ArrayList<>(isRight.length);
            for (boolean b : isRight) {
                isRightList.add(new Bool(b));
            }
            Function function = new Function(
                    "challengeBatchWithProof",
                    Arrays.asList(
                            new Uint256(BigInteger.valueOf(batchId)),
                            new Bytes32(leafBytes),
                            new org.web3j.abi.datatypes.DynamicArray<>(Bytes32.class, proofList),
                            new org.web3j.abi.datatypes.DynamicArray<>(Bool.class, isRightList)),
                    Collections.<TypeReference<?>>emptyList());
            String txHash = sendTransaction(function, "challengeBatchWithProof", batchId);
            if (txHash == null) {
                logger.warn("challengeBatchWithProof tx failed for batch {}", batchId);
                return false;
            }
            l1ChallengedBatches.put(batchId, true);
            logger.info("Batch {} challenged with Merkle proof on L1 txHash={} (proofSize={})",
                    batchId, txHash, proof.length);
            return true;
        } catch (Exception e) {
            logger.error("challengeBatchWithProofOnL1 failed for batch {}: {}", batchId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 在 L1 上最终化单笔提款（生产级，验证 Merkle proof 后实际转移 ERC20）。
     *
     * <p>对应 L2Bridge 合约
     * {@code finalizeWithdrawsWithProof(uint256 batchId, uint256 index, address token, address recipient, uint256 amount, bytes32[] proof, bool[] isRight)} 函数。</p>
     *
     * @param batchId   批次 ID
     * @param index     提款在批次中的索引
     * @param token     ERC20 代币地址（hex，0x 前缀）
     * @param recipient 收款人地址（hex，0x 前缀）
     * @param amount    提款金额（wei）
     * @param proof     Merkle 路径（hex 数组）
     * @param isRight   每层位置标记
     * @return 最终化成功返回 true
     * @since 2.1
     */
    public boolean finalizeWithdrawsWithProofOnL1(
            long batchId, long index, String token, String recipient,
            BigInteger amount, String[] proof, boolean[] isRight) {
        if (token == null || recipient == null || proof == null || isRight == null) {
            return false;
        }
        if (proof.length != isRight.length) {
            logger.warn("finalizeWithdrawsWithProof proof length {} != isRight length {}",
                    proof.length, isRight.length);
            return false;
        }
        if (!web3jReady) {
            logger.warn("finalizeWithdrawsWithProof skipped: Web3j not ready");
            return false;
        }
        try {
            // 构建 proof 数组
            List<Bytes32> proofList = new ArrayList<>(proof.length);
            for (String p : proof) {
                byte[] pBytes = Numeric.hexStringToByteArray(p);
                if (pBytes.length != 32) {
                    logger.warn("Proof element for batch {} is not 32 bytes (got {})", batchId, pBytes.length);
                    return false;
                }
                proofList.add(new Bytes32(pBytes));
            }
            // 构建 isRight 数组
            List<Bool> isRightList = new ArrayList<>(isRight.length);
            for (boolean b : isRight) {
                isRightList.add(new Bool(b));
            }
            Function function = new Function(
                    "finalizeWithdrawsWithProof",
                    Arrays.asList(
                            new Uint256(BigInteger.valueOf(batchId)),
                            new Uint256(BigInteger.valueOf(index)),
                            new Address(token),
                            new Address(recipient),
                            new Uint256(amount),
                            new org.web3j.abi.datatypes.DynamicArray<>(Bytes32.class, proofList),
                            new org.web3j.abi.datatypes.DynamicArray<>(Bool.class, isRightList)),
                    Collections.<TypeReference<?>>emptyList());
            String txHash = sendTransaction(function, "finalizeWithdrawsWithProof", batchId);
            if (txHash == null) {
                logger.warn("finalizeWithdrawsWithProof tx failed for batch {}", batchId);
                return false;
            }
            l1FinalizedWithdraws.put(batchId, true);
            logger.info("Withdraw finalized with proof on L1 for batch {} index={} token={} recipient={} amount={} txHash={}",
                    batchId, index, token, recipient, amount, txHash);
            return true;
        } catch (Exception e) {
            logger.error("finalizeWithdrawsWithProofOnL1 failed for batch {}: {}", batchId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 通过 RawTransactionManager 发送带 value 的合约交易并等待回执。
     *
     * <p>用于 payable 函数（如 depositSequencerBond / depositChallengerBond）。</p>
     *
     * @param function   合约函数调用
     * @param funcName   函数名（日志用）
     * @param batchId    批次 ID（日志用）
     * @param value      附带的 ETH 金额（wei）
     * @return 交易哈希；失败返回 null
     */
    private String sendTransactionWithValue(Function function, String funcName, long batchId, BigInteger value)
            throws IOException {
        String encodedFunction = FunctionEncoder.encode(function);
        BigInteger nonce = web3j.ethGetTransactionCount(
                fromAddress, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        BigInteger gasPriceBi = BigInteger.valueOf(gasPrice);
        try {
            BigInteger chainGasPrice = web3j.ethGasPrice().send().getGasPrice();
            if (chainGasPrice != null && chainGasPrice.signum() > 0) {
                gasPriceBi = chainGasPrice;
            }
        } catch (Exception e) {
            logger.debug("Failed to fetch chain gas price, using configured value {}: {}", gasPrice, e.getMessage());
        }
        RawTransaction rawTransaction = RawTransaction.createTransaction(
                nonce, gasPriceBi, BigInteger.valueOf(gasLimit), contractAddress, value, encodedFunction);

        EthSendTransaction sendResponse = txManager.signAndSend(rawTransaction);
        if (sendResponse.hasError()) {
            logger.error("eth_sendRawTransaction failed for {} batch {}: code={}, message={}",
                    funcName, batchId, sendResponse.getError().getCode(), sendResponse.getError().getMessage());
            return null;
        }
        String txHash = sendResponse.getTransactionHash();
        logger.debug("{} tx submitted for batch {} hash={}", funcName, batchId, txHash);
        return txHash;
    }
}