package org.nexus.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 链上治理客户端——通过 Web3j 调用 NexusGovernor / TimelockController 合约。
 *
 * <p>本类为 Java 侧 {@link GovernanceExecutor} 提供链上治理合约调用封装，
 * 与 {@code Web3jL1ContractClient} 风格一致。合约 ABI 文件位于
 * {@code src/main/resources/abi/} 目录：</p>
 * <ul>
 *   <li>{@code NexusGovernor.abi.json} — 治理器合约 ABI</li>
 *   <li>{@code TimelockController.abi.json} — 时间锁控制器 ABI</li>
 *   <li>{@code GovernanceTargetMock.abi.json} — 测试目标合约 ABI</li>
 * </ul>
 *
 * <h2>配置</h2>
 * <pre>
 *   nexus.governance.on-chain-enabled=true
 *   nexus.governance.rpc-url=http://localhost:8545
 *   nexus.governance.governor-address=0x...
 *   nexus.governance.timelock-address=0x...
 *   nexus.governance.from-address=0x...
 *   nexus.governance.private-key=0x...
 *   nexus.governance.chain-id=31337
 *   nexus.governance.gas-limit=500000
 *   nexus.governance.gas-price=1000000000
 * </pre>
 *
 * <h2>与 GovernanceExecutor 集成</h2>
 * <p>当 {@code nexus.governance.on-chain-enabled=true} 时，本类被 Spring 注入。
 * {@link GovernanceExecutor} 可选择性地将 propose/vote/queue/execute 操作
 * 同步到链上，实现链下治理与链上治理的双轨一致性。</p>
 *
 * <h2>合约对应关系</h2>
 * <table>
 *   <caption>表：Java 方法与 Solidity 函数对照表</caption>
 *   <tr><th>Java 方法</th><th>Solidity 函数</th><th>合约</th></tr>
 *   <tr><td>{@link #proposeOnChain}</td><td>propose()</td><td>NexusGovernor</td></tr>
 *   <tr><td>{@link #castVoteOnChain}</td><td>castVote()</td><td>NexusGovernor</td></tr>
 *   <tr><td>{@link #queueOnChain}</td><td>queue()</td><td>NexusGovernor</td></tr>
 *   <tr><td>{@link #executeOnChain}</td><td>execute()</td><td>NexusGovernor</td></tr>
 *   <tr><td>{@link #cancelOnChain}</td><td>cancel()</td><td>NexusGovernor</td></tr>
 *   <tr><td>{@link #queryProposalState}</td><td>proposalState()</td><td>NexusGovernor</td></tr>
 *   <tr><td>{@link #scheduleOnTimelock}</td><td>schedule()</td><td>TimelockController</td></tr>
 *   <tr><td>{@link #executeOnTimelock}</td><td>executeById()</td><td>TimelockController</td></tr>
 *   <tr><td>{@link #cancelOnTimelock}</td><td>cancel()</td><td>TimelockController</td></tr>
 * </table>
 *
 * @since 2.1
 */
@Component
@ConditionalOnProperty(prefix = "nexus.governance", name = "on-chain-enabled", havingValue = "true")
public class OnChainGovernanceClient {

    private static final Logger logger = LoggerFactory.getLogger(OnChainGovernanceClient.class);

    /** 默认 gas 上限 */
    private static final long DEFAULT_GAS_LIMIT = 500_000L;

    /** 默认 gas price（1 Gwei） */
    private static final long DEFAULT_GAS_PRICE = 1_000_000_000L;

    /** 交易回执轮询间隔（毫秒） */
    private static final long RECEIPT_POLL_INTERVAL_MS = 1000L;

    /** 交易回执轮询次数 */
    private static final int RECEIPT_POLL_ATTEMPTS = 40;

    @Value("${nexus.governance.rpc-url:}")
    private String rpcEndpoint;

    @Value("${nexus.governance.governor-address:}")
    private String governorAddress;

    @Value("${nexus.governance.timelock-address:}")
    private String timelockAddress;

    @Value("${nexus.governance.from-address:}")
    private String fromAddress;

    // P0/密钥管理修复：默认值改为 #{null}（而非空字符串），配置缺失时为 null，
    // 由 init() fail-closed 校验。环境变量 NEXUS_GOVERNANCE_PRIVATE_KEY 可通过
    // application.yml 中 ${NEXUS_GOVERNANCE_PRIVATE_KEY:} 占位符覆盖。
    @Value("${nexus.governance.private-key:#{null}}")
    private String privateKey;

    @Value("${nexus.governance.chain-id:31337}")
    private long chainId;

    @Value("${nexus.governance.gas-limit:" + DEFAULT_GAS_LIMIT + "}")
    private long gasLimit;

    @Value("${nexus.governance.gas-price:" + DEFAULT_GAS_PRICE + "}")
    private long gasPrice;

    /** Web3j 客户端实例 */
    private Web3j web3j;

    /** 交易管理器 */
    private RawTransactionManager txManager;

    /** 凭证（用于签名） */
    private Credentials credentials;

    /** 是否成功初始化 */
    private volatile boolean ready = false;

    // ==================== 生命周期 ====================

    /**
     * 初始化 Web3j 客户端与交易管理器。
     *
     * <p>校验 rpcEndpoint / governorAddress 非空，缺失则标记
     * {@code ready=false}，后续调用直接返回 false。</p>
     *
     * <p><b>fail-closed（P0/密钥管理修复）</b>：本 Bean 仅在
     * {@code nexus.governance.on-chain-enabled=true} 时创建（见类级
     * {@code @ConditionalOnProperty}）。既然用户已显式启用链上治理，
     * {@code privateKey} 为 null 或空视为配置错误，直接抛出
     * {@link IllegalStateException} 拒绝启动，避免无密钥的"伪启用"状态。</p>
     */
    @PostConstruct
    public void init() {
        if (rpcEndpoint == null || rpcEndpoint.isEmpty()) {
            logger.warn("On-chain governance disabled: rpc-url not configured");
            return;
        }
        if (governorAddress == null || governorAddress.isEmpty()) {
            logger.warn("On-chain governance disabled: governor-address not configured");
            return;
        }
        // fail-closed: on-chain-enabled=true 但 privateKey 缺失 → 拒绝启动
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalStateException(
                    "OnChainGovernanceClient init failed: nexus.governance.on-chain-enabled=true but "
                            + "nexus.governance.private-key is not configured. "
                            + "Set NEXUS_GOVERNANCE_PRIVATE_KEY environment variable or nexus.governance.private-key property.");
        }
        try {
            this.web3j = Web3j.build(new HttpService(rpcEndpoint));
            this.credentials = Credentials.create(privateKey);
            this.txManager = new RawTransactionManager(
                    web3j, credentials, chainId,
                    new PollingTransactionReceiptProcessor(web3j, RECEIPT_POLL_INTERVAL_MS, RECEIPT_POLL_ATTEMPTS));
            if (fromAddress == null || fromAddress.isEmpty()) {
                fromAddress = credentials.getAddress();
            }
            this.ready = true;
            logger.info("On-chain governance initialized: endpoint={}, governor={}, timelock={}, from={}, chainId={}",
                    rpcEndpoint, governorAddress, timelockAddress, fromAddress, chainId);
        } catch (Exception e) {
            logger.error("Failed to initialize on-chain governance client: {}", e.getMessage(), e);
            this.ready = false;
        }
    }

    /**
     * 销毁时关闭 Web3j 客户端。
     */
    @PreDestroy
    public void shutdown() {
        if (web3j != null) {
            web3j.shutdown();
            logger.info("On-chain governance client shutdown");
        }
    }

    /**
     * 查询客户端是否已就绪。
     *
     * @return true 表示 Web3j 已初始化成功
     */
    public boolean isReady() {
        return ready;
    }

    // ==================== NexusGovernor 调用 ====================

    /**
     * 在链上创建治理提案。
     *
     * <p>对应 NexusGovernor.propose(address[],bytes[],uint256[],string)。</p>
     *
     * @param targets     目标合约地址数组（hex，0x 前缀）
     * @param calldatas   调用 calldata 数组（hex，0x 前缀）
     * @param values      调用附带的 ETH 数量数组（wei）
     * @param description 提案描述
     * @return 提案 ID；失败返回 -1
     */
    public long proposeOnChain(List<String> targets, List<byte[]> calldatas,
                                List<BigInteger> values, String description) {
        if (!ready || targets == null || calldatas == null || values == null
                || targets.size() != calldatas.size() || targets.size() != values.size()) {
            return -1L;
        }
        try {
            // 构造动态数组参数
            List<Type> params = new java.util.ArrayList<>();
            params.add(buildAddressArray(targets));
            params.add(buildByteArray(calldatas));
            params.add(buildUint256Array(values));
            params.add(new Utf8String(description));

            Function function = new Function("propose", params,
                    Collections.singletonList(new TypeReference<Uint256>() {}));

            String txHash = sendGovernorTransaction(function, "propose");
            if (txHash == null) {
                logger.warn("propose tx failed on chain");
                return -1L;
            }
            // 从回执中解析 proposalId（通过 ProposalCreated 事件）
            long proposalId = extractProposalIdFromReceipt(txHash);
            logger.info("Proposal created on chain: id={} txHash={}", proposalId, txHash);
            return proposalId;
        } catch (Exception e) {
            logger.error("proposeOnChain failed: {}", e.getMessage(), e);
            return -1L;
        }
    }

    /**
     * 在链上对提案投票。
     *
     * <p>对应 NexusGovernor.castVote(uint256,uint8)。</p>
     *
     * @param proposalId 提案 ID
     * @param support    投票选项（0=Against, 1=For, 2=Abstain）
     * @return 投票成功返回 true
     */
    public boolean castVoteOnChain(long proposalId, int support) {
        if (!ready) {
            return false;
        }
        try {
            Function function = new Function(
                    "castVote",
                    Arrays.asList(
                            new Uint256(BigInteger.valueOf(proposalId)),
                            new Uint8(support)),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendGovernorTransaction(function, "castVote");
            if (txHash == null) {
                logger.warn("castVote tx failed for proposal {} support {}", proposalId, support);
                return false;
            }
            logger.info("Vote cast on chain: proposal={} support={} txHash={}", proposalId, support, txHash);
            return true;
        } catch (Exception e) {
            logger.error("castVoteOnChain failed for proposal {}: {}", proposalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 在链上将已通过提案排度到 TimelockController。
     *
     * <p>对应 NexusGovernor.queue(uint256)。</p>
     *
     * @param proposalId 提案 ID
     * @return 排队成功返回 true
     */
    public boolean queueOnChain(long proposalId) {
        if (!ready) {
            return false;
        }
        try {
            Function function = new Function(
                    "queue",
                    Collections.singletonList(new Uint256(BigInteger.valueOf(proposalId))),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendGovernorTransaction(function, "queue");
            if (txHash == null) {
                logger.warn("queue tx failed for proposal {}", proposalId);
                return false;
            }
            logger.info("Proposal queued on chain: id={} txHash={}", proposalId, txHash);
            return true;
        } catch (Exception e) {
            logger.error("queueOnChain failed for proposal {}: {}", proposalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 在链上执行已排队且 timelock 到期的提案。
     *
     * <p>对应 NexusGovernor.execute(uint256)。</p>
     *
     * @param proposalId 提案 ID
     * @return 执行成功返回 true
     */
    public boolean executeOnChain(long proposalId) {
        if (!ready) {
            return false;
        }
        try {
            Function function = new Function(
                    "execute",
                    Collections.singletonList(new Uint256(BigInteger.valueOf(proposalId))),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendGovernorTransaction(function, "execute");
            if (txHash == null) {
                logger.warn("execute tx failed for proposal {}", proposalId);
                return false;
            }
            logger.info("Proposal executed on chain: id={} txHash={}", proposalId, txHash);
            return true;
        } catch (Exception e) {
            logger.error("executeOnChain failed for proposal {}: {}", proposalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 在链上取消提案。
     *
     * <p>对应 NexusGovernor.cancel(uint256)。</p>
     *
     * @param proposalId 提案 ID
     * @return 取消成功返回 true
     */
    public boolean cancelOnChain(long proposalId) {
        if (!ready) {
            return false;
        }
        try {
            Function function = new Function(
                    "cancel",
                    Collections.singletonList(new Uint256(BigInteger.valueOf(proposalId))),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendGovernorTransaction(function, "cancel");
            if (txHash == null) {
                logger.warn("cancel tx failed for proposal {}", proposalId);
                return false;
            }
            logger.info("Proposal canceled on chain: id={} txHash={}", proposalId, txHash);
            return true;
        } catch (Exception e) {
            logger.error("cancelOnChain failed for proposal {}: {}", proposalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 查询链上提案状态。
     *
     * <p>对应 NexusGovernor.proposalState(uint256)。
     * 返回值：0=Active, 1=Defeated, 2=Succeeded, 3=Queued, 4=Executed, 5=Canceled。</p>
     *
     * @param proposalId 提案 ID
     * @return 状态枚举值；查询失败返回 -1
     */
    public int queryProposalState(long proposalId) {
        if (!ready) {
            return -1;
        }
        try {
            Function function = new Function(
                    "proposalState",
                    Collections.singletonList(new Uint256(BigInteger.valueOf(proposalId))),
                    Collections.singletonList(new TypeReference<Uint8>() {}));

            String result = callGovernorView(function);
            if (result == null) {
                return -1;
            }
            List<Type> decoded = FunctionReturnDecoder.decode(
                    result, function.getOutputParameters());
            if (decoded.isEmpty()) {
                return -1;
            }
            return ((Uint8) decoded.get(0)).getValue().intValue();
        } catch (Exception e) {
            logger.error("queryProposalState failed for proposal {}: {}", proposalId, e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 查询链上提案计数器。
     *
     * @return 提案总数；查询失败返回 -1
     */
    public long queryProposalCount() {
        if (!ready) {
            return -1;
        }
        try {
            Function function = new Function(
                    "proposalCount",
                    Collections.<Type>emptyList(),
                    Collections.singletonList(new TypeReference<Uint256>() {}));

            String result = callGovernorView(function);
            if (result == null) {
                return -1;
            }
            List<Type> decoded = FunctionReturnDecoder.decode(
                    result, function.getOutputParameters());
            if (decoded.isEmpty()) {
                return -1;
            }
            return ((Uint256) decoded.get(0)).getValue().longValueExact();
        } catch (Exception e) {
            logger.error("queryProposalCount failed: {}", e.getMessage(), e);
            return -1;
        }
    }

    // ==================== TimelockController 调用 ====================

    /**
     * 在 TimelockController 上排度操作。
     *
     * <p>对应 TimelockController.schedule(address,bytes,uint256,uint256)。</p>
     *
     * @param target 目标合约地址（hex）
     * @param data   调用 calldata
     * @param value  附带 ETH 数量（wei）
     * @param delay  延迟秒数
     * @return 操作 ID（hex）；失败返回 null
     */
    public String scheduleOnTimelock(String target, byte[] data, BigInteger value, BigInteger delay) {
        if (!ready || timelockAddress == null || timelockAddress.isEmpty()) {
            return null;
        }
        try {
            Function function = new Function(
                    "schedule",
                    Arrays.asList(
                            new Address(target),
                            new DynamicBytes(data),
                            new Uint256(value),
                            new Uint256(delay)),
                    Collections.singletonList(new TypeReference<Bytes32>() {}));

            String txHash = sendTimelockTransaction(function, "schedule");
            if (txHash == null) {
                logger.warn("schedule tx failed on timelock");
                return null;
            }
            // 从回执事件中提取 operationId
            String operationId = extractOperationIdFromReceipt(txHash);
            logger.info("Operation scheduled on timelock: id={} txHash={}", operationId, txHash);
            return operationId;
        } catch (Exception e) {
            logger.error("scheduleOnTimelock failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 在 TimelockController 上执行操作。
     *
     * <p>对应 TimelockController.executeById(bytes32,address,bytes,uint256)。</p>
     *
     * @param operationId 操作 ID（hex）
     * @param target      目标合约地址（hex）
     * @param data        调用 calldata
     * @param value       附带 ETH 数量（wei）
     * @return 执行成功返回 true
     */
    public boolean executeOnTimelock(String operationId, String target, byte[] data, BigInteger value) {
        if (!ready || timelockAddress == null || timelockAddress.isEmpty()) {
            return false;
        }
        try {
            byte[] opIdBytes = Numeric.hexStringToByteArray(operationId);
            Function function = new Function(
                    "executeById",
                    Arrays.asList(
                            new Bytes32(opIdBytes),
                            new Address(target),
                            new DynamicBytes(data),
                            new Uint256(value)),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendTimelockTransaction(function, "executeById");
            if (txHash == null) {
                logger.warn("executeById tx failed on timelock for op {}", operationId);
                return false;
            }
            logger.info("Operation executed on timelock: id={} txHash={}", operationId, txHash);
            return true;
        } catch (Exception e) {
            logger.error("executeOnTimelock failed for op {}: {}", operationId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 在 TimelockController 上取消操作。
     *
     * <p>对应 TimelockController.cancel(bytes32)。</p>
     *
     * @param operationId 操作 ID（hex）
     * @return 取消成功返回 true
     */
    public boolean cancelOnTimelock(String operationId) {
        if (!ready || timelockAddress == null || timelockAddress.isEmpty()) {
            return false;
        }
        try {
            byte[] opIdBytes = Numeric.hexStringToByteArray(operationId);
            Function function = new Function(
                    "cancel",
                    Collections.singletonList(new Bytes32(opIdBytes)),
                    Collections.<TypeReference<?>>emptyList());

            String txHash = sendTimelockTransaction(function, "cancel");
            if (txHash == null) {
                logger.warn("cancel tx failed on timelock for op {}", operationId);
                return false;
            }
            logger.info("Operation canceled on timelock: id={} txHash={}", operationId, txHash);
            return true;
        } catch (Exception e) {
            logger.error("cancelOnTimelock failed for op {}: {}", operationId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 查询 TimelockController 上操作的状态。
     *
     * <p>对应 TimelockController.getOperationState(bytes32)。
     * 返回值：0=Unset, 1=Pending, 2=Ready, 3=Done, 4=Cancelled。</p>
     *
     * @param operationId 操作 ID（hex）
     * @return 状态枚举值；查询失败返回 -1
     */
    public int queryOperationState(String operationId) {
        if (!ready || timelockAddress == null || timelockAddress.isEmpty()) {
            return -1;
        }
        try {
            byte[] opIdBytes = Numeric.hexStringToByteArray(operationId);
            Function function = new Function(
                    "getOperationState",
                    Collections.singletonList(new Bytes32(opIdBytes)),
                    Collections.singletonList(new TypeReference<Uint8>() {}));

            String result = callTimelockView(function);
            if (result == null) {
                return -1;
            }
            List<Type> decoded = FunctionReturnDecoder.decode(
                    result, function.getOutputParameters());
            if (decoded.isEmpty()) {
                return -1;
            }
            return ((Uint8) decoded.get(0)).getValue().intValue();
        } catch (Exception e) {
            logger.error("queryOperationState failed for op {}: {}", operationId, e.getMessage(), e);
            return -1;
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 向 Governor 合约发送交易。
     */
    private String sendGovernorTransaction(Function function, String funcName) throws Exception {
        return sendTransaction(governorAddress, function, funcName);
    }

    /**
     * 向 Timelock 合约发送交易。
     */
    private String sendTimelockTransaction(Function function, String funcName) throws Exception {
        return sendTransaction(timelockAddress, function, funcName);
    }

    /**
     * 通用交易发送。
     */
    private String sendTransaction(String contractAddr, Function function, String funcName) throws Exception {
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
                nonce, gasPriceBi, BigInteger.valueOf(gasLimit), contractAddr, encodedFunction);

        EthSendTransaction sendResponse = txManager.signAndSend(rawTransaction);
        if (sendResponse.hasError()) {
            logger.error("Transaction {} failed: code={}, message={}",
                    funcName, sendResponse.getError().getCode(), sendResponse.getError().getMessage());
            return null;
        }
        String txHash = sendResponse.getTransactionHash();

        // 等待回执
        TransactionReceipt receipt = waitForReceipt(txHash);
        if (receipt == null || !receipt.isStatusOK()) {
            logger.error("Transaction {} receipt not OK: status={}", funcName,
                    receipt == null ? "null" : receipt.getStatus());
            return null;
        }
        return txHash;
    }

    /**
     * 调用 Governor view 函数。
     */
    private String callGovernorView(Function function) throws Exception {
        return callView(governorAddress, function);
    }

    /**
     * 调用 Timelock view 函数。
     */
    private String callTimelockView(Function function) throws Exception {
        return callView(timelockAddress, function);
    }

    /**
     * 通用 view 调用。
     */
    private String callView(String contractAddr, Function function) throws Exception {
        String encodedFunction = FunctionEncoder.encode(function);
        Transaction transaction = Transaction.createEthCallTransaction(
                fromAddress, contractAddr, encodedFunction);
        EthCall ethCall = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();
        if (ethCall.hasError()) {
            logger.error("View call failed: {}", ethCall.getError().getMessage());
            return null;
        }
        return ethCall.getValue();
    }

    /**
     * 等待交易回执。
     */
    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        for (int i = 0; i < RECEIPT_POLL_ATTEMPTS; i++) {
            var receiptOptional = web3j.ethGetTransactionReceipt(txHash).send();
            if (receiptOptional.getTransactionReceipt().isPresent()) {
                return receiptOptional.getTransactionReceipt().get();
            }
            Thread.sleep(RECEIPT_POLL_INTERVAL_MS);
        }
        logger.warn("Transaction receipt timeout for {}", txHash);
        return null;
    }

    /**
     * 从回执中提取 proposalId（解析 ProposalCreated 事件）。
     *
     * <p>ProposalCreated 事件签名：
     * ProposalCreated(uint256,address,address[],uint256[],uint256,uint256,string)</p>
     */
    private long extractProposalIdFromReceipt(String txHash) throws Exception {
        TransactionReceipt receipt = waitForReceipt(txHash);
        if (receipt == null) {
            return -1L;
        }
        // ProposalCreated 事件 topic0
        String eventSignature = "ProposalCreated(uint256,address,address[],uint256[],uint256,uint256,string)";
        String topic0 = org.web3j.crypto.Hash.sha3String(eventSignature);

        for (var log : receipt.getLogs()) {
            if (log.getTopics() != null && !log.getTopics().isEmpty()
                    && log.getTopics().get(0).equalsIgnoreCase(topic0)) {
                // proposalId 在 topic1 中（indexed）
                String topic1 = log.getTopics().get(1);
                return Numeric.toBigInt(topic1).longValueExact();
            }
        }
        logger.warn("ProposalCreated event not found in receipt {}", txHash);
        return -1L;
    }

    /**
     * 从回执中提取 operationId（解析 OperationScheduled 事件）。
     *
     * <p>OperationScheduled 事件签名：
     * OperationScheduled(bytes32,address,uint256,uint256,uint256)</p>
     */
    private String extractOperationIdFromReceipt(String txHash) throws Exception {
        TransactionReceipt receipt = waitForReceipt(txHash);
        if (receipt == null) {
            return null;
        }
        String eventSignature = "OperationScheduled(bytes32,address,uint256,uint256,uint256)";
        String topic0 = org.web3j.crypto.Hash.sha3String(eventSignature);

        for (var log : receipt.getLogs()) {
            if (log.getTopics() != null && !log.getTopics().isEmpty()
                    && log.getTopics().get(0).equalsIgnoreCase(topic0)) {
                // operationId 在 topic1 中（indexed）
                return log.getTopics().get(1);
            }
        }
        logger.warn("OperationScheduled event not found in receipt {}", txHash);
        return null;
    }

    /**
     * 构造 Web3j Address 动态数组。
     */
    private org.web3j.abi.datatypes.DynamicArray<Address> buildAddressArray(List<String> addresses) {
        List<Address> list = new java.util.ArrayList<>(addresses.size());
        for (String addr : addresses) {
            list.add(new Address(addr));
        }
        return new org.web3j.abi.datatypes.DynamicArray<>(Address.class, list);
    }

    /**
     * 构造 Web3j bytes[] 动态数组。
     */
    private org.web3j.abi.datatypes.DynamicArray<DynamicBytes> buildByteArray(List<byte[]> arrays) {
        List<DynamicBytes> list = new java.util.ArrayList<>(arrays.size());
        for (byte[] arr : arrays) {
            list.add(new DynamicBytes(arr));
        }
        return new org.web3j.abi.datatypes.DynamicArray<>(DynamicBytes.class, list);
    }

    /**
     * 构造 Web3j uint256[] 动态数组。
     */
    private org.web3j.abi.datatypes.DynamicArray<Uint256> buildUint256Array(List<BigInteger> values) {
        List<Uint256> list = new java.util.ArrayList<>(values.size());
        for (BigInteger v : values) {
            list.add(new Uint256(v));
        }
        return new org.web3j.abi.datatypes.DynamicArray<>(Uint256.class, list);
    }

    // ==================== 编程式构造（用于测试） ====================

    /**
     * 编程式构造实例（用于端到端测试，绕过 Spring 注入）。
     *
     * <p>构造后需调用 {@link #init()} 完成初始化。</p>
     *
     * @param rpcEndpoint     L1 JSON-RPC 端点
     * @param governorAddress NexusGovernor 合约地址
     * @param timelockAddress TimelockController 合约地址
     * @param privateKey      发送方私钥
     * @param chainId         链 ID
     * @return 未初始化的实例
     */
    public static OnChainGovernanceClient createForTesting(
            String rpcEndpoint, String governorAddress, String timelockAddress,
            String privateKey, long chainId) {
        OnChainGovernanceClient client = new OnChainGovernanceClient();
        client.rpcEndpoint = rpcEndpoint;
        client.governorAddress = governorAddress;
        client.timelockAddress = timelockAddress;
        client.privateKey = privateKey;
        client.chainId = chainId;
        client.gasLimit = DEFAULT_GAS_LIMIT;
        client.gasPrice = DEFAULT_GAS_PRICE;
        return client;
    }
}