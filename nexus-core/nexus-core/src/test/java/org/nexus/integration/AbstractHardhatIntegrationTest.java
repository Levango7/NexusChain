package org.nexus.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hardhat 端到端集成测试基类（P0-3b）。
 *
 * <p>封装 Hardhat 本地节点的启动/连接、合约部署、Web3j 客户端初始化与
 * 优雅跳过逻辑，供 {@code OnChainGovernanceIntegrationTest} 与
 * {@code GovernanceExecutorOnChainIntegrationTest} 复用。</p>
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li><b>@BeforeAll</b>：定位 l1-test 目录 → 检查 npx → 安装依赖 →
 *       启动 Hardhat 节点（或连接已有 8545 节点）→ 部署合约 →
 *       读取部署产物 JSON → 初始化 Web3j</li>
 *   <li>子类测试方法通过 {@link #assumeHardhatAvailable()} 优雅跳过</li>
 *   <li><b>@AfterAll</b>：关闭 Web3j + 停止 Hardhat 子进程</li>
 * </ol>
 *
 * <h2>跳过策略</h2>
 * <p>任意准备步骤失败时，{@link #hardhatAvailable} 置 false，
 * 子类调用 {@link #assumeHardhatAvailable()} 触发 {@link assumeTrue} 跳过。
 * 消息为 "Hardhat not available"。</p>
 *
 * <h2>子类需实现</h2>
 * <ul>
 *   <li>{@link #deployScript()}：返回部署脚本相对路径（如 {@code scripts/deploy-governance.js}）</li>
 *   <li>{@link #deploymentJsonName()}：返回部署产物 JSON 文件名（如 {@code deployed-governance.json}）</li>
 * </ul>
 *
 * <h2>辅助方法</h2>
 * <ul>
 *   <li>{@link #evmMine()}：推进一个区块</li>
 *   <li>{@link #evmIncreaseTime(long)}：推进链上时间（秒）</li>
 *   <li>{@link #getContractAddress(String)}：按名称查询部署合约地址</li>
 *   <li>{@link #evmSnapshot()}/{@link #evmRevert(long)}：快照/回滚（隔离用例副作用）</li>
 * </ul>
 *
 * @since 2.1
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractHardhatIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(AbstractHardhatIntegrationTest.class);

    // ==================== 常量 ====================

    /** Hardhat 本地节点 RPC 端点 */
    protected static final String RPC_URL = "http://127.0.0.1:8545";

    /** Hardhat 默认链 ID */
    protected static final long HARDHAT_CHAIN_ID = 31337L;

    /**
     * Hardhat 标准测试账户 #0 的私钥（公开、固定，仅用于测试）。
     * <p>对应地址：{@code 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266}</p>
     * <p>由 Hardhat 默认 mnemonic "test test test test test test test test test test test junk"
     * 派生路径 m/44'/60'/0'/0/0 生成。</p>
     */
    protected static final String HARDHAT_PRIVATE_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    /** Hardhat 标准测试账户 #0 的地址 */
    protected static final String HARDHAT_DEPLOYER_ADDRESS =
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    /** RPC 连接超时（毫秒） */
    private static final long RPC_TIMEOUT_MS = 15_000L;

    /** RPC 轮询间隔（毫秒） */
    private static final long RPC_POLL_INTERVAL_MS = 500L;

    /** Hardhat 节点启动等待时间（毫秒） */
    private static final long NODE_STARTUP_WAIT_MS = 5_000L;

    /** l1-test 资源目录名 */
    private static final String L1_TEST_DIR_NAME = "l1-test";

    // ==================== 共享状态 ====================

    /** Hardhat 节点子进程（本测试启动时非 null，复用已有节点时为 null） */
    protected Process hardhatProcess;

    /** Web3j 客户端（直接连接，用于链上验证） */
    protected Web3j web3j;

    /** 交易管理器（直接签名发送） */
    protected RawTransactionManager txManager;

    /** 部署者 Credentials */
    protected Credentials credentials;

    /** Hardhat 是否可用（不可用时跳过所有测试） */
    protected boolean hardhatAvailable = false;

    /** 部署合约地址表（name -> 0x 地址） */
    protected final Map<String, String> contractAddresses = new LinkedHashMap<>();

    /** l1-test 目录绝对路径 */
    protected Path l1TestDir;

    /** 是否复用已有节点（未启动子进程） */
    private boolean reuseExistingNode = false;

    // ==================== 子类钩子 ====================

    /**
     * 返回部署脚本相对 l1-test 目录的路径（如 {@code scripts/deploy-governance.js}）。
     */
    protected abstract String deployScript();

    /**
     * 返回部署产物 JSON 文件名（如 {@code deployed-governance.json}）。
     */
    protected abstract String deploymentJsonName();

    // ==================== @BeforeAll ====================

    /**
     * 启动 Hardhat 节点、部署合约、初始化 Web3j 客户端。
     *
     * <p>任一步骤失败时标记 {@link #hardhatAvailable}=false，
     * 子类通过 {@link #assumeHardhatAvailable()} 跳过测试。</p>
     */
    @BeforeAll
    protected void setUpHardhatEnvironment() throws Exception {
        logger.info("=== {} : 启动 Hardhat 环境 ===", getClass().getSimpleName());

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

        // 4. 启动 Hardhat 节点（或复用已有 8545 节点）
        if (!ensureHardhatNodeRunning()) {
            logger.warn("Hardhat 节点不可用，跳过测试");
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

        // 6. 部署合约
        if (!deployContract()) {
            logger.warn("合约部署失败，跳过测试");
            stopHardhatNode();
            assumeTrue(false, "Hardhat not available: contract deployment failed");
            return;
        }

        // 7. 初始化 Web3j 客户端
        initWeb3jClient();

        hardhatAvailable = true;
        logger.info("=== Hardhat 环境就绪，部署合约: {} ===", contractAddresses.keySet());
    }

    // ==================== @AfterAll ====================

    /**
     * 关闭 Web3j 客户端与 Hardhat 节点子进程。
     */
    @AfterAll
    protected void tearDownHardhatEnvironment() {
        logger.info("=== {} : 清理 Hardhat 环境 ===", getClass().getSimpleName());
        if (web3j != null) {
            try {
                web3j.shutdown();
            } catch (Exception e) {
                logger.debug("web3j shutdown error: {}", e.getMessage());
            }
        }
        stopHardhatNode();
    }

    // ==================== 子类调用的跳过辅助 ====================

    /**
     * 子类在每个 @Test 方法首行调用，Hardhat 不可用时优雅跳过。
     */
    protected void assumeHardhatAvailable() {
        assumeTrue(hardhatAvailable, "Hardhat not available");
    }

    // ==================== Hardhat RPC 辅助方法 ====================

    /**
     * 推进一个区块（{@code evm_mine}）。
     *
     * @return true 表示成功
     */
    protected boolean evmMine() {
        try {
            sendRawJsonRpc("evm_mine", new Object[]{});
            return true;
        } catch (Exception e) {
            logger.warn("evm_mine failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 推进链上时间（秒）。
     *
     * <p>对应 Hardhat {@code evm_increaseTime}。注意：仅修改链上时钟，
     * 不自动出块；如需立即生效，调用后应再 {@link #evmMine()}。</p>
     *
     * @param seconds 推进秒数
     * @return true 表示成功
     */
    protected boolean evmIncreaseTime(long seconds) {
        try {
            sendRawJsonRpc("evm_increaseTime", new Object[]{seconds});
            return true;
        } catch (Exception e) {
            logger.warn("evm_increaseTime failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 推进时间并出块（常用组合）。
     *
     * @param seconds 推进秒数
     */
    protected void advanceTimeAndMine(long seconds) {
        evmIncreaseTime(seconds);
        evmMine();
    }

    /**
     * 推进 n 个区块。
     *
     * @param n 区块数
     */
    protected void advanceBlocks(int n) {
        for (int i = 0; i < n; i++) {
            evmMine();
        }
    }

    /**
     * 创建 EVM 快照（用于隔离测试副作用）。
     *
     * @return 快照 ID；失败返回 -1
     */
    protected long evmSnapshot() {
        try {
            Object result = sendRawJsonRpc("evm_snapshot", new Object[]{});
            if (result instanceof Number) {
                return ((Number) result).longValue();
            }
            return Long.parseLong(result.toString());
        } catch (Exception e) {
            logger.warn("evm_snapshot failed: {}", e.getMessage());
            return -1L;
        }
    }

    /**
     * 回滚到指定快照。
     *
     * @param snapshotId 快照 ID
     * @return true 表示成功
     */
    protected boolean evmRevert(long snapshotId) {
        try {
            sendRawJsonRpc("evm_revert", new Object[]{snapshotId});
            return true;
        } catch (Exception e) {
            logger.warn("evm_revert failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 按名称查询部署合约地址。
     *
     * @param name 合约名称（部署产物 JSON 中 contracts 字段的 key）
     * @return 0x 前缀的地址；不存在返回 null
     */
    protected String getContractAddress(String name) {
        return contractAddresses.get(name);
    }

    /**
     * 查询当前区块号。
     *
     * @return 区块号；查询失败返回 -1
     */
    protected long getCurrentBlockNumber() {
        try {
            EthBlockNumber resp = web3j.ethBlockNumber().send();
            return resp.getBlockNumber().longValueExact();
        } catch (Exception e) {
            return -1L;
        }
    }

    // ==================== 内部：环境准备 ====================

    /**
     * 定位 l1-test 资源目录。
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
     * 确保 Hardhat 节点正在运行。
     *
     * <p>策略：先尝试连接 8545 端口，若已可用则复用（不启动子进程）；
     * 否则启动 {@code npx hardhat node} 子进程。</p>
     */
    private boolean ensureHardhatNodeRunning() {
        // 先尝试连接已有节点
        if (isRpcReachable()) {
            logger.info("检测到 8545 端口已有 Hardhat 节点，复用之");
            reuseExistingNode = true;
            return true;
        }

        // 启动新节点
        logger.info("启动 Hardhat 节点: npx hardhat node");
        try {
            hardhatProcess = buildProcess("npx", "hardhat", "node")
                    .directory(l1TestDir.toFile())
                    .redirectErrorStream(true)
                    .start();

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

            Thread.sleep(NODE_STARTUP_WAIT_MS);
            if (!hardhatProcess.isAlive()) {
                logger.warn("Hardhat 节点进程已退出");
                return false;
            }
            reuseExistingNode = false;
            return true;
        } catch (Exception e) {
            logger.warn("启动 Hardhat 节点异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查 RPC 端点是否可达。
     */
    private boolean isRpcReachable() {
        try {
            Web3j testWeb3j = Web3j.build(new HttpService(RPC_URL));
            testWeb3j.ethBlockNumber().send();
            testWeb3j.shutdown();
            return true;
        } catch (Exception e) {
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
            if (isRpcReachable()) {
                logger.info("Hardhat RPC 已就绪");
                return true;
            }
            try {
                Thread.sleep(RPC_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 部署合约（运行子类指定的部署脚本）。
     *
     * <p>部署前先调用 {@code hardhat_reset} 重置链状态，
     * 避免前一轮测试消耗的余额/状态影响本轮测试。
     * 对新启动的 Hardhat 节点无副作用。</p>
     */
    private boolean deployContract() {
        // 先重置链状态（复用已有节点时清理前轮状态；新节点无副作用）
        logger.info("尝试 hardhat_reset 重置链状态...");
        try {
            sendRawJsonRpc("hardhat_reset", new Object[]{});
            logger.info("hardhat_reset 成功");
        } catch (Exception e) {
            logger.warn("hardhat_reset 失败（可能非 Hardhat 节点）: {}", e.getMessage());
            // 不影响后续流程，继续尝试部署
        }

        String script = deployScript();
        logger.info("部署合约: npx hardhat run {} --network localhost", script);
        try {
            Process p = buildProcess("npx", "hardhat", "run", script, "--network", "localhost")
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

            // 读取部署产物 JSON
            Path deployedFile = l1TestDir.resolve(deploymentJsonName());
            if (!Files.exists(deployedFile)) {
                logger.warn("部署产物 {} 不存在", deploymentJsonName());
                return false;
            }

            String json = Files.readString(deployedFile, StandardCharsets.UTF_8);
            parseDeployedJson(json);
            if (contractAddresses.isEmpty()) {
                logger.warn("未从 {} 解析到任何合约地址", deploymentJsonName());
                return false;
            }

            logger.info("合约已部署: {}", contractAddresses);
            return true;
        } catch (Exception e) {
            logger.warn("部署合约异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析部署产物 JSON，提取 contracts 字段下所有合约地址。
     *
     * <p>支持两种格式：</p>
     * <ul>
     *   <li>governance: {@code {"contracts": {"NexusGovernor": "0x...", ...}}}</li>
     *   <li>bridge: {@code {"address": "0x..."}}（旧格式，按 "Bridge" 注册）</li>
     * </ul>
     */
    private void parseDeployedJson(String json) {
        contractAddresses.clear();

        // 优先解析 "contracts" 对象
        String contractsBlock = extractJsonObject(json, "contracts");
        if (contractsBlock != null) {
            // 在 contracts 块中提取所有 "name": "0x..." 字段
            int i = 0;
            while (i < contractsBlock.length()) {
                int quoteStart = contractsBlock.indexOf('"', i);
                if (quoteStart < 0) break;
                int quoteEnd = contractsBlock.indexOf('"', quoteStart + 1);
                if (quoteEnd < 0) break;
                String name = contractsBlock.substring(quoteStart + 1, quoteEnd);

                int colon = contractsBlock.indexOf(':', quoteEnd);
                if (colon < 0) break;
                int valueStart = colon + 1;
                while (valueStart < contractsBlock.length()
                        && (contractsBlock.charAt(valueStart) == ' ' || contractsBlock.charAt(valueStart) == '\t')) {
                    valueStart++;
                }
                if (valueStart >= contractsBlock.length() || contractsBlock.charAt(valueStart) != '"') {
                    i = valueStart;
                    continue;
                }
                int valueEnd = contractsBlock.indexOf('"', valueStart + 1);
                if (valueEnd < 0) break;
                String value = contractsBlock.substring(valueStart + 1, valueEnd);
                if (value.startsWith("0x")) {
                    contractAddresses.put(name, value);
                }
                i = valueEnd + 1;
            }
        }

        // 兼容旧格式：{"address": "0x..."}
        if (contractAddresses.isEmpty()) {
            String addr = extractJsonField(json, "address");
            if (addr != null && addr.startsWith("0x")) {
                contractAddresses.put("Bridge", addr);
            }
        }
    }

    /**
     * 初始化 Web3j 客户端。
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

    // ==================== 内部：进程与 JSON 工具 ====================

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
     * 停止 Hardhat 节点子进程（复用已有节点时不停止）。
     */
    private void stopHardhatNode() {
        if (reuseExistingNode) {
            logger.info("复用已有 Hardhat 节点，不停止");
            return;
        }
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
     * 通过 HttpURLConnection 直接发送 JSON-RPC 请求（web3j 未直接封装的 evm_* 方法）。
     */
    private Object sendRawJsonRpc(String method, Object[] params) throws IOException {
        // 构造 JSON-RPC 请求
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"method\":\"").append(method).append("\",\"params\":[");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            Object p = params[i];
            if (p instanceof String) {
                sb.append('"').append(p).append('"');
            } else {
                sb.append(p);
            }
        }
        sb.append("],\"id\":1}");
        String requestBody = sb.toString();

        java.net.URL url = new java.net.URL(RPC_URL);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        String response;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder resp = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                resp.append(line);
            }
            response = resp.toString();
        }
        conn.disconnect();

        // 解析 result 字段
        String resultKey = "\"result\":";
        int idx = response.indexOf(resultKey);
        if (idx < 0) {
            throw new IOException("No result in response: " + response);
        }
        int start = idx + resultKey.length();
        while (start < response.length() && response.charAt(start) == ' ') start++;
        if (start >= response.length()) return null;
        char c = response.charAt(start);
        if (c == '"') {
            int end = response.indexOf('"', start + 1);
            return response.substring(start + 1, end);
        } else if (c == 'n') {
            return null; // null
        } else {
            int end = start;
            while (end < response.length() && response.charAt(end) != ',' && response.charAt(end) != '}') {
                end++;
            }
            String numStr = response.substring(start, end).trim();
            try {
                return Long.parseLong(numStr);
            } catch (NumberFormatException e) {
                return numStr;
            }
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
        while (start < json.length() && json.charAt(start) == ' ') {
            start++;
        }
        if (start >= json.length()) {
            return null;
        }
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) {
                return null;
            }
            return json.substring(start + 1, end);
        } else {
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }

    /**
     * 从 JSON 字符串中提取嵌套对象的内容（大括号之间的字符串）。
     */
    private static String extractJsonObject(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        while (start < json.length() && json.charAt(start) != '{') {
            start++;
        }
        if (start >= json.length()) {
            return null;
        }
        int depth = 0;
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) break;
            }
            end++;
        }
        if (end >= json.length()) {
            return null;
        }
        return json.substring(start + 1, end);
    }
}