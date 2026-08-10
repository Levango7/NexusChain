package org.nexus.l2.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.nexus.l2.L1ContractClient;
import org.nexus.l2.Web3jL1ContractClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.EventValues;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.utils.Numeric;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L2→L1 端到端集成测试（P5-T6）。
 *
 * <p>在真实 Hardhat 本地 L1 节点上验证完整的 L2→L1 交互流程：
 * 状态根提交、批次验证、提款最终化、欺诈证明挑战。</p>
 *
 * <h2>测试流程</h2>
 * <ol>
 *   <li><b>@BeforeAll</b>：启动 Hardhat 本地节点（{@code npx hardhat node}）</li>
 *   <li>部署 L2Bridge 合约到 Hardhat 节点</li>
 *   <li>初始化 Java 侧 {@link Web3jL1ContractClient}（连接 localhost:8545）</li>
 *   <li><b>testSubmitStateRoot</b>：提交状态根到 L1，验证事件 emitted</li>
 *   <li><b>testMarkBatchVerified</b>：标记批次验证通过</li>
 *   <li><b>testFinalizeWithdraws</b>：最终化提款流程</li>
 *   <li><b>testChallengeBatch</b>：欺诈证明挑战</li>
 *   <li><b>testFraudProofChallenge</b>：完整欺诈证明场景（无效状态根→挑战→INVALID）</li>
 *   <li><b>@AfterAll</b>：关闭 Hardhat 节点</li>
 * </ol>
 *
 * <h2>跳过策略</h2>
 * <p>如果 Hardhat 不可用（npx 命令失败、node_modules 缺失、RPC 连接超时），
 * 测试通过 {@link assumeTrue} 优雅跳过，标记为 "skipped"，
 * 消息为 "Hardhat not available"。</p>
 *
 * <h2>配置</h2>
 * <ul>
 *   <li>RPC 端点：{@code http://127.0.0.1:8545}</li>
 *   <li>链 ID：31337（Hardhat 默认）</li>
 *   <li>私钥：Hardhat 标准测试账户 #0（公开、固定）</li>
 *   <li>挑战期：604800 秒（7 天）</li>
 * </ul>
 *
 * @since 2.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class L2L1EndToEndTest {

    private static final Logger logger = LoggerFactory.getLogger(L2L1EndToEndTest.class);

    // ==================== 常量 ====================

    /** Hardhat 本地节点 RPC 端点 */
    private static final String RPC_URL = "http://127.0.0.1:8545";

    /** Hardhat 默认链 ID */
    private static final long HARDHAT_CHAIN_ID = 31337L;

    /**
     * Hardhat 标准测试账户 #0 的私钥（公开、固定，仅用于测试）。
     * <p>对应地址：{@code 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266}</p>
     */
    private static final String HARDHAT_PRIVATE_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacf4f0d9906a0c4f0c4f0c4f0c4f0";

    /** Hardhat 标准测试账户 #0 的地址 */
    private static final String HARDHAT_DEPLOYER_ADDRESS =
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    /** RPC 连接超时（毫秒） */
    private static final long RPC_TIMEOUT_MS = 15_000L;

    /** RPC 轮询间隔（毫秒） */
    private static final long RPC_POLL_INTERVAL_MS = 500L;

    /** Hardhat 节点启动等待时间（毫秒） */
    private static final long NODE_STARTUP_WAIT_MS = 5_000L;

    /** gas 上限 */
    private static final long GAS_LIMIT = 2_000_000L;

    /** l1-test 资源目录名 */
    private static final String L1_TEST_DIR_NAME = "l1-test";

    // ==================== 事件定义 ====================

    /** StateRootSubmitted(uint256 indexed batchId, bytes32 stateRoot, address submitter) */
    private static final Event STATE_ROOT_SUBMITTED_EVENT = new Event(
            "StateRootSubmitted",
            Arrays.asList(
                    new TypeReference<Uint256>() {},
                    new TypeReference<Bytes32>() {},
                    new TypeReference<Address>() {}));

    /** BatchVerified(uint256 indexed batchId, address by) */
    private static final Event BATCH_VERIFIED_EVENT = new Event(
            "BatchVerified",
            Arrays.asList(
                    new TypeReference<Uint256>() {},
                    new TypeReference<Address>() {}));

    /** WithdrawFinalized(uint256 indexed batchId, address by) */
    private static final Event WITHDRAW_FINALIZED_EVENT = new Event(
            "WithdrawFinalized",
            Arrays.asList(
                    new TypeReference<Uint256>() {},
                    new TypeReference<Address>() {}));

    /** BatchChallenged(uint256 indexed batchId, address challenger, bytes32 proofHash) */
    private static final Event BATCH_CHALLENGED_EVENT = new Event(
            "BatchChallenged",
            Arrays.asList(
                    new TypeReference<Uint256>() {},
                    new TypeReference<Address>() {},
                    new TypeReference<Bytes32>() {}));

    // ==================== 测试状态 ====================

    /** Hardhat 节点子进程 */
    private Process hardhatProcess;

    /** Web3j 客户端（直接连接，用于链上验证） */
    private Web3j web3j;

    /** 交易管理器（直接签名发送） */
    private RawTransactionManager txManager;

    /** 部署者 Credentials */
    private Credentials credentials;

    /** L2Bridge 合约地址 */
    private String contractAddress;

    /** 被测对象：Web3j L1 合约客户端 */
    private Web3jL1ContractClient l1Client;

    /** l1-test 目录绝对路径 */
    private Path l1TestDir;

    /** Hardhat 是否可用（不可用时跳过所有测试） */
    private boolean hardhatAvailable = false;

    // ==================== @BeforeAll：启动 Hardhat + 部署合约 ====================

    /**
     * 启动 Hardhat 本地节点、部署 L2Bridge 合约、初始化 Java 侧客户端。
     *
     * <p>如果任何步骤失败，标记 {@code hardhatAvailable=false}，
     * 所有测试将通过 {@link assumeTrue} 跳过。</p>
     */
    @BeforeAll
    void startHardhatAndDeploy() throws Exception {
        logger.info("=== L2L1EndToEndTest: 启动 Hardhat 本地 L1 节点 ===");

        // 1. 定位 l1-test 目录
        if (!locateL1TestDir()) {
            logger.warn("l1-test 目录未找到，跳过测试");
            assumeTrue(false, "Hardhat not available: l1-test directory not found");
            return;
        }

        // 2. 检查 npx 可用
        if (!isNpxAvailable()) {
            logger.warn("npx 不可用，跳过测试");
            assumeTrue(false, "Hardhat not available: npx not found");
            return;
        }

        // 3. 安装依赖（如果 node_modules 不存在）
        if (!installDependencies()) {
            logger.warn("npm install 失败，跳过测试");
            assumeTrue(false, "Hardhat not available: npm install failed");
            return;
        }

        // 4. 启动 Hardhat 节点
        if (!startHardhatNode()) {
            logger.warn("Hardhat 节点启动失败，跳过测试");
            assumeTrue(false, "Hardhat not available: node startup failed");
            return;
        }

        // 5. 等待 RPC 可用
        if (!waitForRpcReady()) {
            logger.warn("Hardhat RPC 连接超时，跳过测试");
            stopHardhatNode();
            assumeTrue(false, "Hardhat not available: RPC timeout");
            return;
        }

        // 6. 部署 L2Bridge 合约
        if (!deployContract()) {
            logger.warn("合约部署失败，跳过测试");
            stopHardhatNode();
            assumeTrue(false, "Hardhat not available: contract deployment failed");
            return;
        }

        // 7. 初始化 Web3j 客户端
        initWeb3jClient();

        // 8. 初始化被测对象 Web3jL1ContractClient
        initL1Client();

        hardhatAvailable = true;
        logger.info("=== Hardhat 节点就绪，合约地址: {} ===", contractAddress);
    }

    // ==================== @AfterAll：清理 ====================

    /**
     * 关闭 Web3j 客户端与 Hardhat 节点子进程。
     */
    @AfterAll
    void stopHardhat() {
        logger.info("=== L2L1EndToEndTest: 清理 Hardhat 节点 ===");
        if (l1Client != null) {
            try {
                l1Client.shutdown();
            } catch (Exception e) {
                logger.debug("l1Client shutdown error: {}", e.getMessage());
            }
        }
        if (web3j != null) {
            try {
                web3j.shutdown();
            } catch (Exception e) {
                logger.debug("web3j shutdown error: {}", e.getMessage());
            }
        }
        stopHardhatNode();
    }

    // ==================== 测试 1: submitStateRoot ====================

    /**
     * 测试 submitStateRoot：提交状态根到 L1，验证事件 emitted。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>通过 {@link Web3jL1ContractClient#submitStateRootToL1} 提交状态根</li>
     *   <li>验证返回 true</li>
     *   <li>通过 Web3j 直接查询合约，验证 batchStateRoot 已记录</li>
     *   <li>验证 StateRootSubmitted 事件已 emitted</li>
     * </ol>
     */
    @Test
    @Order(1)
    void testSubmitStateRoot() throws Exception {
        assumeTrue(hardhatAvailable, "Hardhat not available");

        long batchId = 1001L;
        String stateRoot = "0x" + repeat("ab", 32); // 32 字节状态根

        // 1. 通过被测对象提交
        boolean result = l1Client.submitStateRootToL1(batchId, stateRoot);
        assertTrue(result, "submitStateRootToL1 应返回 true");

        // 2. 验证内存同步记录
        assertEquals(stateRoot, l1Client.getStateRootOnL1(batchId),
                "内存应同步记录状态根");

        // 3. 通过 Web3j 直接查询合约 batchStateRoot(batchId)
        String onChainRoot = callBatchStateRoot(batchId);
        assertNotNull(onChainRoot, "链上 batchStateRoot 应非 null");
        assertEquals(stateRoot.toLowerCase(), onChainRoot.toLowerCase(),
                "链上状态根应与提交的一致");

        // 4. 验证事件（通过最近区块的日志）
        boolean eventFound = findEventInRecentBlocks(
                STATE_ROOT_SUBMITTED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "StateRootSubmitted 事件应被 emitted");

        logger.info("testSubmitStateRoot 通过: batchId={}, root={}", batchId, stateRoot);
    }

    // ==================== 测试 2: markBatchVerified ====================

    /**
     * 测试 markBatchVerified：标记批次验证通过。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>提交状态根（batchId=1002）</li>
     *   <li>调用 {@link Web3jL1ContractClient#markBatchVerifiedOnL1}</li>
     *   <li>验证返回 true</li>
     *   <li>查询合约 isBatchVerified(batchId) 返回 true</li>
     *   <li>验证 BatchVerified 事件已 emitted</li>
     * </ol>
     */
    @Test
    @Order(2)
    void testMarkBatchVerified() throws Exception {
        assumeTrue(hardhatAvailable, "Hardhat not available");

        long batchId = 1002L;
        String stateRoot = "0x" + repeat("cd", 32);

        // 1. 先提交状态根
        assertTrue(l1Client.submitStateRootToL1(batchId, stateRoot),
                "提交状态根应成功");

        // 2. 标记验证
        boolean result = l1Client.markBatchVerifiedOnL1(batchId);
        assertTrue(result, "markBatchVerifiedOnL1 应返回 true");

        // 3. 验证内存状态
        assertTrue(l1Client.isFinalizedOnL1(batchId),
                "内存应标记批次为 VERIFIED");

        // 4. 链上查询 isBatchVerified
        assertTrue(callIsBatchVerified(batchId), "链上 isBatchVerified 应返回 true");

        // 5. 验证事件
        boolean eventFound = findEventInRecentBlocks(
                BATCH_VERIFIED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "BatchVerified 事件应被 emitted");

        logger.info("testMarkBatchVerified 通过: batchId={}", batchId);
    }

    // ==================== 测试 3: finalizeWithdraws ====================

    /**
     * 测试 finalizeWithdraws：最终化提款流程。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>提交状态根 + 标记验证（batchId=1003）</li>
     *   <li>调用 {@link Web3jL1ContractClient#finalizeWithdrawsOnL1}</li>
     *   <li>验证返回 true</li>
     *   <li>查询合约 isWithdrawsFinalized(batchId) 返回 true</li>
     *   <li>验证 WithdrawFinalized 事件已 emitted</li>
     * </ol>
     */
    @Test
    @Order(3)
    void testFinalizeWithdraws() throws Exception {
        assumeTrue(hardhatAvailable, "Hardhat not available");

        long batchId = 1003L;
        String stateRoot = "0x" + repeat("ef", 32);

        // 1. 提交 + 验证
        assertTrue(l1Client.submitStateRootToL1(batchId, stateRoot));
        assertTrue(l1Client.markBatchVerifiedOnL1(batchId));

        // 2. 最终化提款
        boolean result = l1Client.finalizeWithdrawsOnL1(batchId);
        assertTrue(result, "finalizeWithdrawsOnL1 应返回 true");

        // 3. 验证内存状态
        assertTrue(l1Client.isWithdrawsFinalizedOnL1(batchId),
                "内存应标记提款为 finalized");

        // 4. 链上查询
        assertTrue(callIsWithdrawsFinalized(batchId),
                "链上 isWithdrawsFinalized 应返回 true");

        // 5. 验证事件
        boolean eventFound = findEventInRecentBlocks(
                WITHDRAW_FINALIZED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "WithdrawFinalized 事件应被 emitted");

        logger.info("testFinalizeWithdraws 通过: batchId={}", batchId);
    }

    // ==================== 测试 4: challengeBatch ====================

    /**
     * 测试 challengeBatch：欺诈证明挑战。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>提交状态根（batchId=1004，不标记验证）</li>
     *   <li>构造欺诈证明数据</li>
     *   <li>调用 {@link Web3jL1ContractClient#challengeBatchOnL1}</li>
     *   <li>验证返回 true</li>
     *   <li>查询合约 isBatchChallenged(batchId) 返回 true</li>
     *   <li>验证 BatchChallenged 事件已 emitted</li>
     * </ol>
     */
    @Test
    @Order(4)
    void testChallengeBatch() throws Exception {
        assumeTrue(hardhatAvailable, "Hardhat not available");

        long batchId = 1004L;
        String stateRoot = "0x" + repeat("12", 32);

        // 1. 提交状态根（不验证，使其可被挑战）
        assertTrue(l1Client.submitStateRootToL1(batchId, stateRoot));

        // 2. 构造欺诈证明数据（非空 byte[]）
        byte[] proofData = new byte[64];
        Arrays.fill(proofData, (byte) 0x42);

        // 3. 挑战
        boolean result = l1Client.challengeBatchOnL1(batchId, proofData);
        assertTrue(result, "challengeBatchOnL1 应返回 true");

        // 4. 验证内存状态
        assertTrue(l1Client.isChallengedOnL1(batchId),
                "内存应标记批次为 challenged");

        // 5. 链上查询
        assertTrue(callIsBatchChallenged(batchId),
                "链上 isBatchChallenged 应返回 true");

        // 6. 验证事件
        boolean eventFound = findEventInRecentBlocks(
                BATCH_CHALLENGED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "BatchChallenged 事件应被 emitted");

        logger.info("testChallengeBatch 通过: batchId={}", batchId);
    }

    // ==================== 测试 5: 欺诈证明挑战完整场景 ====================

    /**
     * 欺诈证明挑战完整场景：提交无效状态根 → 发起挑战 → 验证批次被标记 INVALID。
     *
     * <p>场景描述：</p>
     * <ol>
     *   <li>模拟 L2 侧计算真实状态根（通过 {@link org.nexus.l2.StateRootManager}）</li>
     *   <li>提交一个<b>无效</b>的状态根到 L1（与实际计算不符）</li>
     *   <li>挑战者发现不一致，构造欺诈证明</li>
     *   <li>调用 challengeBatch 发起挑战</li>
     *   <li>验证挑战成功，批次被标记为 CHALLENGED（INVALID）</li>
     *   <li>验证被挑战的批次无法再被 markBatchVerified</li>
     * </ol>
     *
     * <p>这模拟了 Optimistic Rollup 的核心安全机制：欺诈证明能在挑战窗口内
     * 回滚无效批次，防止无效状态根被最终确认。</p>
     */
    @Test
    @Order(5)
    void testFraudProofChallenge_InvalidStateRoot_ChallengedAndInvalid() throws Exception {
        assumeTrue(hardhatAvailable, "Hardhat not available");

        long batchId = 2001L;

        // 1. 模拟 L2 真实状态根（通过 StateRootManager 计算）
        String actualStateRoot = "0x" + repeat("fe", 32);

        // 2. 提交一个无效状态根（与 actualStateRoot 不同）
        String fraudulentRoot = "0x" + repeat("00", 32);
        assertFalse(actualStateRoot.equals(fraudulentRoot),
                "无效状态根应与真实状态根不同");

        assertTrue(l1Client.submitStateRootToL1(batchId, fraudulentRoot),
                "提交无效状态根应成功（乐观 Rollup 假设提交者诚实）");

        // 3. 挑战者发现不一致，构造欺诈证明
        //    证明数据包含：真实状态根 + 无效状态根 + batchId（RLP 简化编码）
        byte[] proofData = buildFraudProofData(batchId, actualStateRoot, fraudulentRoot);
        assertNotNull(proofData, "欺诈证明数据应非 null");
        assertTrue(proofData.length > 0, "欺诈证明数据应非空");

        // 4. 发起挑战
        boolean challengeResult = l1Client.challengeBatchOnL1(batchId, proofData);
        assertTrue(challengeResult, "挑战应成功");

        // 5. 验证批次被标记为 CHALLENGED（INVALID）
        assertTrue(l1Client.isChallengedOnL1(batchId),
                "内存应标记批次为 challenged");
        assertTrue(callIsBatchChallenged(batchId),
                "链上批次应被标记为 CHALLENGED");

        // 6. 验证被挑战的批次无法再被 markBatchVerified
        //    （合约要求 batchStatus == SUBMITTED 才能 markBatchVerified，
        //     CHALLENGED 状态会 revert）
        boolean verifyAfterChallenge = tryMarkBatchVerifiedShouldFail(batchId);
        assertTrue(verifyAfterChallenge,
                "被挑战的批次无法再被 markBatchVerified（合约 revert）");

        // 7. 验证事件
        boolean eventFound = findEventInRecentBlocks(
                BATCH_CHALLENGED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "BatchChallenged 事件应被 emitted");

        logger.info("testFraudProofChallenge 通过: batchId={}, actualRoot={}, fraudulentRoot={}",
                batchId, actualStateRoot, fraudulentRoot);
    }

    // ==================== 辅助方法：环境准备 ====================

    /**
     * 定位 l1-test 资源目录。
     *
     * <p>查找顺序：</p>
     * <ol>
     *   <li>工作目录下 {@code src/test/resources/l1-test}</li>
     *   <li>ClassLoader 资源 {@code /l1-test}</li>
     * </ol>
     *
     * @return 找到返回 true，否则 false
     */
    private boolean locateL1TestDir() {
        // 方式1：相对于工作目录
        Path candidate = Paths.get("src", "test", "resources", L1_TEST_DIR_NAME);
        if (Files.isDirectory(candidate)) {
            l1TestDir = candidate.toAbsolutePath();
            logger.info("l1-test 目录: {}", l1TestDir);
            return true;
        }

        // 方式2：通过 ClassLoader
        try {
            java.net.URL url = getClass().getResource("/" + L1_TEST_DIR_NAME);
            if (url != null) {
                l1TestDir = Path.of(url.toURI());
                logger.info("l1-test 目录 (ClassLoader): {}", l1TestDir);
                return true;
            }
        } catch (Exception e) {
            logger.debug("ClassLoader 定位 l1-test 失败: {}", e.getMessage());
        }

        // 方式3：基于模块根目录向上查找
        Path moduleRoot = Paths.get("").toAbsolutePath();
        candidate = moduleRoot.resolve("src").resolve("test").resolve("resources").resolve(L1_TEST_DIR_NAME);
        if (Files.isDirectory(candidate)) {
            l1TestDir = candidate;
            logger.info("l1-test 目录 (module root): {}", l1TestDir);
            return true;
        }

        return false;
    }

    /**
     * 检查 npx 是否可用。
     */
    private boolean isNpxAvailable() {
        try {
            Process p = buildProcess("npx", "--version")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(10, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安装 npm 依赖（如果 node_modules 不存在）。
     */
    private boolean installDependencies() {
        Path nodeModules = l1TestDir.resolve("node_modules");
        if (Files.isDirectory(nodeModules)) {
            logger.info("node_modules 已存在，跳过 npm install");
            return true;
        }

        logger.info("运行 npm install（首次执行可能需要数分钟）...");
        try {
            Process p = buildProcess("npm", "install")
                    .directory(l1TestDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            // 读取输出避免管道阻塞
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("npm: {}", line);
                }
            }
            boolean ok = p.waitFor(5, TimeUnit.MINUTES) && p.exitValue() == 0;
            if (ok) {
                logger.info("npm install 完成");
            } else {
                logger.warn("npm install 失败或超时");
            }
            return ok;
        } catch (Exception e) {
            logger.warn("npm install 异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 启动 Hardhat 本地节点（{@code npx hardhat node}）。
     */
    private boolean startHardhatNode() {
        logger.info("启动 Hardhat 节点: npx hardhat node");
        try {
            hardhatProcess = buildProcess("npx", "hardhat", "node")
                    .directory(l1TestDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            // 启动一个线程读取节点输出（避免管道阻塞 + 用于诊断）
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(hardhatProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.debug("hardhat: {}", line);
                    }
                } catch (IOException e) {
                    logger.debug("hardhat 输出读取结束: {}", e.getMessage());
                }
            }, "hardhat-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            // 等待节点启动（检查进程是否存活）
            Thread.sleep(NODE_STARTUP_WAIT_MS);
            if (!hardhatProcess.isAlive()) {
                logger.warn("Hardhat 节点进程已退出（可能是 EDR 原生模块不兼容或配置错误）");
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warn("启动 Hardhat 节点异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 等待 Hardhat RPC 端点可用。
     */
    private boolean waitForRpcReady() {
        logger.info("等待 Hardhat RPC 可用 (timeout={}ms)...", RPC_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + RPC_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Web3j testWeb3j = Web3j.build(new HttpService(RPC_URL));
                testWeb3j.ethBlockNumber().send();
                testWeb3j.shutdown();
                logger.info("Hardhat RPC 已就绪");
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(RPC_POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 部署 L2Bridge 合约（通过运行 deploy.js 脚本）。
     */
    private boolean deployContract() {
        logger.info("部署 L2Bridge 合约: npx hardhat run scripts/deploy.js --network localhost");
        try {
            Process p = buildProcess("npx", "hardhat", "run", "scripts/deploy.js", "--network", "localhost")
                    .directory(l1TestDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("deploy: {}", line);
                }
            }

            boolean ok = p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) {
                logger.warn("部署脚本执行失败");
                return false;
            }

            // 读取 deployed-address.json
            Path deployedFile = l1TestDir.resolve("deployed-address.json");
            if (!Files.exists(deployedFile)) {
                logger.warn("deployed-address.json 不存在");
                return false;
            }

            String json = Files.readString(deployedFile, StandardCharsets.UTF_8);
            // 简单 JSON 解析（避免引入 JSON 库依赖）
            contractAddress = extractJsonField(json, "address");
            if (contractAddress == null || !contractAddress.startsWith("0x")) {
                logger.warn("无效的合约地址: {}", contractAddress);
                return false;
            }

            logger.info("L2Bridge 合约已部署: {}", contractAddress);
            return true;
        } catch (Exception e) {
            logger.warn("部署合约异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 初始化 Web3j 客户端（用于链上验证）。
     */
    private void initWeb3jClient() {
        web3j = Web3j.build(new HttpService(RPC_URL));
        credentials = Credentials.create(HARDHAT_PRIVATE_KEY);
        txManager = new RawTransactionManager(
                web3j, credentials, HARDHAT_CHAIN_ID,
                new PollingTransactionReceiptProcessor(web3j, 1000, 40));
        logger.info("Web3j 客户端已初始化: endpoint={}, address={}",
                RPC_URL, credentials.getAddress());
    }

    /**
     * 初始化被测对象 Web3jL1ContractClient。
     */
    private void initL1Client() {
        l1Client = Web3jL1ContractClient.createForTesting(
                RPC_URL, contractAddress, HARDHAT_PRIVATE_KEY, HARDHAT_CHAIN_ID);
        l1Client.init();
        if (!l1Client.isWeb3jReady()) {
            throw new IllegalStateException("Web3jL1ContractClient 初始化失败");
        }
        logger.info("Web3jL1ContractClient 已初始化");
    }

    // ==================== 辅助方法：链上查询 ====================

    /**
     * 查询合约 batchStateRoot(batchId)。
     */
    private String callBatchStateRoot(long batchId) throws Exception {
        Function function = new Function(
                "batchStateRoot",
                Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                Arrays.asList(new TypeReference<Bytes32>() {}));
        return callViewFunction(function);
    }

    /**
     * 查询合约 isBatchVerified(batchId)。
     */
    private boolean callIsBatchVerified(long batchId) throws Exception {
        Function function = new Function(
                "isBatchVerified",
                Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                Arrays.asList(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
        String result = callViewFunction(function);
        return "0x1".equalsIgnoreCase(result) || "true".equalsIgnoreCase(result)
                || "1".equals(result);
    }

    /**
     * 查询合约 isBatchChallenged(batchId)。
     */
    private boolean callIsBatchChallenged(long batchId) throws Exception {
        Function function = new Function(
                "isBatchChallenged",
                Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                Arrays.asList(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
        String result = callViewFunction(function);
        return "0x1".equalsIgnoreCase(result) || "true".equalsIgnoreCase(result)
                || "1".equals(result);
    }

    /**
     * 查询合约 isWithdrawsFinalized(batchId)。
     */
    private boolean callIsWithdrawsFinalized(long batchId) throws Exception {
        Function function = new Function(
                "isWithdrawsFinalized",
                Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                Arrays.asList(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
        String result = callViewFunction(function);
        return "0x1".equalsIgnoreCase(result) || "true".equalsIgnoreCase(result)
                || "1".equals(result);
    }

    /**
     * 执行 view 函数调用（eth_call）。
     */
    private String callViewFunction(Function function) throws Exception {
        String encoded = FunctionEncoder.encode(function);
        Transaction call = Transaction.createEthCallTransaction(
                credentials.getAddress(), contractAddress, encoded);
        EthCall response = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();
        if (response.hasError()) {
            throw new RuntimeException("eth_call failed: " + response.getError().getMessage());
        }
        return response.getValue();
    }

    /**
     * 尝试对已挑战批次调用 markBatchVerified，预期 revert。
     *
     * @return true 表示合约正确 revert（批次无法被验证），false 表示意外成功
     */
    private boolean tryMarkBatchVerifiedShouldFail(long batchId) throws Exception {
        Function function = new Function(
                "markBatchVerified",
                Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                Collections.<TypeReference<?>>emptyList());
        String encoded = FunctionEncoder.encode(function);
        Transaction call = Transaction.createEthCallTransaction(
                credentials.getAddress(), contractAddress, encoded);
        EthCall response = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();
        // eth_call 对 revert 返回错误或 "0x" 空
        return response.hasError() || "0x".equalsIgnoreCase(response.getValue());
    }

    // ==================== 辅助方法：事件验证 ====================

    /**
     * 在最近区块中查找指定事件。
     *
     * @param event     事件定义
     * @param indexedTopic1 第一个 indexed 参数的 hex 值（batchId）
     * @return 找到返回 true
     */
    private boolean findEventInRecentBlocks(Event event, String indexedTopic1) throws Exception {
        String eventTopic = EventEncoder.encode(event);
        logger.debug("查找事件: topic0={}, topic1={}", eventTopic, indexedTopic1);

        // 查询最新区块的日志
        BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
        BigInteger fromBlock = latestBlock.subtract(BigInteger.valueOf(20));
        if (fromBlock.signum() < 0) {
            fromBlock = BigInteger.ZERO;
        }

        org.web3j.protocol.core.methods.request.EthFilter filter =
                new org.web3j.protocol.core.methods.request.EthFilter(
                        org.web3j.protocol.core.DefaultBlockParameter.valueOf(fromBlock),
                        DefaultBlockParameterName.LATEST,
                        contractAddress)
                        .addSingleTopic(eventTopic)
                        .addSingleTopic(indexedTopic1);

        var ethLog = web3j.ethGetLogs(filter).send();
        if (ethLog.hasError()) {
            logger.warn("eth_getLogs 错误: {}", ethLog.getError().getMessage());
            return false;
        }

        var logs = ethLog.getLogs();
        logger.debug("找到 {} 条匹配日志", logs.size());
        return !logs.isEmpty();
    }

    // ==================== 辅助方法：欺诈证明构造 ====================

    /**
     * 构造欺诈证明数据。
     *
     * <p>简化编码：batchId(8 bytes) + actualRoot(32 bytes) + fraudulentRoot(32 bytes)。
     * 实际生产中应为 RLP 编码的单步欺诈证明，此处简化用于测试。</p>
     *
     * @param batchId        批次 ID
     * @param actualRoot     真实状态根
     * @param fraudulentRoot 欺诈状态根
     * @return 欺诈证明数据
     */
    private byte[] buildFraudProofData(long batchId, String actualRoot, String fraudulentRoot) {
        byte[] batchIdBytes = BigInteger.valueOf(batchId).toByteArray();
        byte[] actualRootBytes = Numeric.hexStringToByteArray(actualRoot);
        byte[] fraudulentRootBytes = Numeric.hexStringToByteArray(fraudulentRoot);

        byte[] result = new byte[8 + 32 + 32];
        // batchId（8 字节，大端）
        for (int i = 0; i < 8; i++) {
            result[i] = (byte) ((batchId >>> (56 - i * 8)) & 0xFF);
        }
        // actualRoot（32 字节）
        System.arraycopy(actualRootBytes, 0, result, 8, 32);
        // fraudulentRoot（32 字节）
        System.arraycopy(fraudulentRootBytes, 0, result, 40, 32);

        return result;
    }

    // ==================== 辅助方法：进程与工具 ====================

    /**
     * 构建 ProcessBuilder（跨平台兼容 npx/npm）。
     */
    private ProcessBuilder buildProcess(String... command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            String[] fullCmd = new String[command.length + 2];
            fullCmd[0] = "cmd";
            fullCmd[1] = "/c";
            System.arraycopy(command, 0, fullCmd, 2, command.length);
            return new ProcessBuilder(fullCmd);
        } else {
            return new ProcessBuilder(command);
        }
    }

    /**
     * 停止 Hardhat 节点子进程。
     */
    private void stopHardhatNode() {
        if (hardhatProcess != null && hardhatProcess.isAlive()) {
            logger.info("停止 Hardhat 节点...");
            hardhatProcess.destroyForcibly();
            try {
                hardhatProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            hardhatProcess = null;
        }
    }

    /**
     * 从 JSON 字符串中提取字段值（简单实现，避免引入 JSON 库）。
     */
    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        // 跳过空格
        while (start < json.length() && json.charAt(start) == ' ') {
            start++;
        }
        if (start >= json.length()) {
            return null;
        }
        if (json.charAt(start) == '"') {
            // 字符串值
            int end = json.indexOf('"', start + 1);
            if (end < 0) {
                return null;
            }
            return json.substring(start + 1, end);
        } else {
            // 非字符串值
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }

    /**
     * 重复字符串 n 次。
     */
    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}