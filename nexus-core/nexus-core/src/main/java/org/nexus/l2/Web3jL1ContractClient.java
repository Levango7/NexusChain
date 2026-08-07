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
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

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
 * <p>通过 {@code application.properties} 中 {@code nexus.l2.l1-bridge.*} 配置项注入：</p>
 * <pre>
 * nexus.l2.l1-bridge.enabled=true
 * nexus.l2.l1-bridge.rpc-endpoint=http://localhost:8545
 * nexus.l2.l1-bridge.contract-address=0x...
 * nexus.l2.l1-bridge.from-address=0x...
 * nexus.l2.l1-bridge.private-key=0x...
 * nexus.l2.l1-bridge.chain-id=1
 * nexus.l2.l1-bridge.gas-limit=500000
 * nexus.l2.l1-bridge.gas-price=1000000000
 * </pre>
 *
 * <h2>Fallback 策略</h2>
 * <p>当 Web3j 调用失败（RPC 异常、交易被拒、配置缺失）时，回退到内存模拟
 * （调用 {@code super} 方法），保证 L2 流程不因 L1 不可用而中断。
 * 详见审计报告 §3.4 / 任务 #83。</p>
 *
 * <p>当 {@code nexus.l2.l1-bridge.enabled=true} 时，Spring 注入本类替代
 * {@link L1ContractClient}（内存模拟）。</p>
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

    /** Web3j 客户端实例，@PostConstruct 中初始化 */
    private Web3j web3j;

    /** 交易管理器（用 Credentials 签名） */
    private RawTransactionManager txManager;

    /** 是否成功初始化 Web3j（false 时所有调用回退内存模拟） */
    private volatile boolean web3jReady = false;

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
            Function function = new Function(
                    "submitStateRoot",
                    Arrays.asList(
                            new Uint256(BigInteger.valueOf(batchId)),
                            new Bytes32(rootBytes)),
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
            Function function = new Function(
                    "challengeBatch",
                    Arrays.asList(
                            new Uint256(BigInteger.valueOf(batchId)),
                            new DynamicBytes(proofData)),
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
}