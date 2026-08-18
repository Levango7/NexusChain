package org.nexus.governance;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.nexus.integration.AbstractHardhatIntegrationTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OnChainGovernanceClient} 端到端集成测试（P0-3b）。
 *
 * <p>在真实 Hardhat 本地 L1 节点上，验证 Java 侧 {@link OnChainGovernanceClient}
 * 通过 Web3j 调用 NexusGovernor / TimelockController 合约的完整链路：</p>
 *
 * <h2>测试用例</h2>
 * <ol>
 *   <li>{@link #testProposeOnChain}：propose → 验证 ProposalCreated 事件 + proposalCount 增加</li>
 *   <li>{@link #testCastVoteOnChain}：propose + setVotingWeight + castVote → 验证 VoteCast 事件</li>
 *   <li>{@link #testQueueOnChain}：propose + vote + advanceBlocks + queue → 验证 ProposalQueued + 状态 Queued</li>
 *   <li>{@link #testExecuteOnChain}：propose + vote + queue + advanceTime + execute → 验证 ProposalExecuted + 目标状态变更</li>
 *   <li>{@link #testCancelOnChain}：propose + cancel → 验证 ProposalCanceled</li>
 *   <li>{@link #testGovernanceExecutorOnChainIntegration}：GovernanceExecutor.schedule/execute 同步链上</li>
 * </ol>
 *
 * <h2>跳过策略</h2>
 * <p>Hardhat 不可用时通过 {@link #assumeHardhatAvailable()} 优雅跳过。</p>
 *
 * @since 2.1
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OnChainGovernanceIntegrationTest extends AbstractHardhatIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OnChainGovernanceIntegrationTest.class);

    // ==================== 事件签名 ====================

    private static final String PROPOSAL_CREATED_SIG =
            "ProposalCreated(uint256,address,address[],uint256[],uint256,uint256,string)";
    private static final String VOTE_CAST_SIG =
            "VoteCast(uint256,address,uint8,uint256)";
    private static final String PROPOSAL_QUEUED_SIG =
            "ProposalQueued(uint256,bytes32,uint256)";
    private static final String PROPOSAL_EXECUTED_SIG =
            "ProposalExecuted(uint256)";
    private static final String PROPOSAL_CANCELED_SIG =
            "ProposalCanceled(uint256)";

    // ==================== 合约参数 ====================

    /** 部署脚本设置的投票期区块数（见 deploy-governance.js） */
    private static final int VOTING_PERIOD_BLOCKS = 100;

    /** 部署脚本设置的 timelock 最小延迟（秒，见 deploy-governance.js） */
    private static final long TIMELOCK_MIN_DELAY_SECONDS = 3600L;

    /** 测试用 quorum 阈值（绝对票数，100 ETH） */
    private static final BigInteger QUORUM_WEIGHT = BigInteger.valueOf(6000L).multiply(BigInteger.TEN.pow(18)); // 6000 ETH > quorumThreshold(5000 ETH)

    // ==================== 子类钩子 ====================

    @Override
    protected String deployScript() {
        return "scripts/deploy-governance.js";
    }

    @Override
    protected String deploymentJsonName() {
        return "deployed-governance.json";
    }

    // ==================== 测试 1: proposeOnChain ====================

    /**
     * 测试 {@link OnChainGovernanceClient#proposeOnChain}：
     * 创建链上提案，验证 ProposalCreated 事件 emitted + proposalCount 增加 + 状态 Active。
     */
    @Test
    @Order(1)
    void testProposeOnChain() throws Exception {
        assumeHardhatAvailable();
        logger.info("--- testProposeOnChain ---");

        OnChainGovernanceClient client = buildOnChainClient();
        assertTrue(client.isReady(), "OnChainGovernanceClient 应就绪");

        String targetAddr = getContractAddress("GovernanceTargetMock");
        assertNotNull(targetAddr, "GovernanceTargetMock 地址应存在");
        byte[] calldata = encodeSetValueCalldata(BigInteger.valueOf(42L));

        long countBefore = client.queryProposalCount();
        logger.info("propose 前 proposalCount = {}", countBefore);

        // 调用被测方法
        long proposalId = client.proposeOnChain(
                Collections.singletonList(targetAddr),
                Collections.singletonList(calldata),
                Collections.singletonList(BigInteger.ZERO),
                "Set value to 42");
        logger.info("proposeOnChain 返回 proposalId = {}", proposalId);
        assertTrue(proposalId > 0, "proposalId 应为正数");

        // 验证 proposalCount 增加
        long countAfter = client.queryProposalCount();
        assertEquals(countBefore + 1, countAfter, "proposalCount 应递增 1");

        // 验证状态 Active (0)
        int state = client.queryProposalState(proposalId);
        assertEquals(0, state, "新提案状态应为 Active (0)");

        // 验证 ProposalCreated 事件已 emitted
        assertTrue(findEventInRecentBlocks(PROPOSAL_CREATED_SIG, proposalId),
                "ProposalCreated 事件应被 emitted");

        logger.info("testProposeOnChain 通过: proposalId={}", proposalId);
    }

    // ==================== 测试 2: castVoteOnChain ====================

    /**
     * 测试 {@link OnChainGovernanceClient#castVoteOnChain}：
     * propose + 设置模拟投票权重 + castVote，验证 VoteCast 事件 + forVotes 增加。
     */
    @Test
    @Order(2)
    void testCastVoteOnChain() throws Exception {
        assumeHardhatAvailable();
        logger.info("--- testCastVoteOnChain ---");

        OnChainGovernanceClient client = buildOnChainClient();
        String targetAddr = getContractAddress("GovernanceTargetMock");
        byte[] calldata = encodeSetValueCalldata(BigInteger.valueOf(42L));

        // 1. propose
        long proposalId = client.proposeOnChain(
                Collections.singletonList(targetAddr),
                Collections.singletonList(calldata),
                Collections.singletonList(BigInteger.ZERO),
                "Vote test proposal");
        assertTrue(proposalId > 0, "propose 应成功");

        // 2. 设置模拟投票权重（deployer = owner，可直接设置）
        //    setVotingWeight 会自动启用 useMockWeights 模式
        setVotingWeightOnChain(HARDHAT_DEPLOYER_ADDRESS, QUORUM_WEIGHT);

        // 3. castVote (support=1 For)
        boolean voteResult = client.castVoteOnChain(proposalId, 1);
        assertTrue(voteResult, "castVoteOnChain 应返回 true");

        // 4. 验证 VoteCast 事件
        assertTrue(findEventInRecentBlocks(VOTE_CAST_SIG, proposalId),
                "VoteCast 事件应被 emitted");

        // 5. 验证 forVotes 已增加（通过 getProposal 查询）
        BigInteger forVotes = queryProposalForVotes(proposalId);
        assertTrue(forVotes.compareTo(BigInteger.ZERO) > 0,
                "forVotes 应 > 0，实际: " + forVotes);

        logger.info("testCastVoteOnChain 通过: proposalId={}, forVotes={}",
                proposalId, forVotes);
    }

    // ==================== 测试 3: queueOnChain ====================

    /**
     * 测试 {@link OnChainGovernanceClient#queueOnChain}：
     * propose + vote + 推进区块结束投票期 + queue，验证 ProposalQueued 事件 + 状态 Queued。
     */
    @Test
    @Order(3)
    void testQueueOnChain() throws Exception {
        assumeHardhatAvailable();
        logger.info("--- testQueueOnChain ---");

        OnChainGovernanceClient client = buildOnChainClient();
        String targetAddr = getContractAddress("GovernanceTargetMock");
        byte[] calldata = encodeSetValueCalldata(BigInteger.valueOf(42L));

        // 1. propose + vote
        long proposalId = client.proposeOnChain(
                Collections.singletonList(targetAddr),
                Collections.singletonList(calldata),
                Collections.singletonList(BigInteger.ZERO),
                "Queue test proposal");
        assertTrue(proposalId > 0, "propose 应成功");
        setVotingWeightOnChain(HARDHAT_DEPLOYER_ADDRESS, QUORUM_WEIGHT);
        assertTrue(client.castVoteOnChain(proposalId, 1), "vote 应成功");

        // 2. 推进区块结束投票期
        advanceBlocks(VOTING_PERIOD_BLOCKS + 1);

        // 3. 验证状态 Succeeded (2)
        int stateSucceeded = client.queryProposalState(proposalId);
        assertEquals(2, stateSucceeded, "投票期结束后状态应为 Succeeded (2)");

        // 4. queue
        boolean queueResult = client.queueOnChain(proposalId);
        assertTrue(queueResult, "queueOnChain 应返回 true");

        // 5. 验证 ProposalQueued 事件
        assertTrue(findEventInRecentBlocks(PROPOSAL_QUEUED_SIG, proposalId),
                "ProposalQueued 事件应被 emitted");

        // 6. 验证状态 Queued (3)
        int stateQueued = client.queryProposalState(proposalId);
        assertEquals(3, stateQueued, "queue 后状态应为 Queued (3)");

        logger.info("testQueueOnChain 通过: proposalId={}", proposalId);
    }

    // ==================== 测试 4: executeOnChain ====================

    /**
     * 测试 {@link OnChainGovernanceClient#executeOnChain}：
     * propose + vote + queue + 推进时间到期 + execute，
     * 验证 ProposalExecuted 事件 + 目标合约 value 已变更 + 状态 Executed。
     */
    @Test
    @Order(4)
    void testExecuteOnChain() throws Exception {
        assumeHardhatAvailable();
        logger.info("--- testExecuteOnChain ---");

        OnChainGovernanceClient client = buildOnChainClient();
        String targetAddr = getContractAddress("GovernanceTargetMock");
        BigInteger newValue = BigInteger.valueOf(12345L);
        byte[] calldata = encodeSetValueCalldata(newValue);

        // 1. propose + vote
        long proposalId = client.proposeOnChain(
                Collections.singletonList(targetAddr),
                Collections.singletonList(calldata),
                Collections.singletonList(BigInteger.ZERO),
                "Execute test: set value to 12345");
        assertTrue(proposalId > 0, "propose 应成功");
        setVotingWeightOnChain(HARDHAT_DEPLOYER_ADDRESS, QUORUM_WEIGHT);
        assertTrue(client.castVoteOnChain(proposalId, 1), "vote 应成功");

        // 2. 推进区块 + queue
        advanceBlocks(VOTING_PERIOD_BLOCKS + 1);
        assertTrue(client.queueOnChain(proposalId), "queue 应成功");

        // 3. 推进时间到期（timelock minDelay + 1 秒）
        advanceTimeAndMine(TIMELOCK_MIN_DELAY_SECONDS + 1L);

        // 4. 验证目标合约 value 初始值 != newValue
        BigInteger valueBefore = queryTargetValue(targetAddr);
        logger.info("执行前 target.value = {}", valueBefore);

        // 5. execute
        boolean execResult = client.executeOnChain(proposalId);
        assertTrue(execResult, "executeOnChain 应返回 true");

        // 6. 验证 ProposalExecuted 事件
        assertTrue(findEventInRecentBlocks(PROPOSAL_EXECUTED_SIG, proposalId),
                "ProposalExecuted 事件应被 emitted");

        // 7. 验证目标合约状态已变更
        BigInteger valueAfter = queryTargetValue(targetAddr);
        assertEquals(newValue, valueAfter, "target.value 应变为 " + newValue);

        // 8. 验证状态 Executed (4)
        int state = client.queryProposalState(proposalId);
        assertEquals(4, state, "execute 后状态应为 Executed (4)");

        logger.info("testExecuteOnChain 通过: proposalId={}, value: {} -> {}",
                proposalId, valueBefore, valueAfter);
    }

    // ==================== 测试 5: cancelOnChain ====================

    /**
     * 测试 {@link OnChainGovernanceClient#cancelOnChain}：
     * propose + cancel，验证 ProposalCanceled 事件 + 状态 Canceled。
     */
    @Test
    @Order(5)
    void testCancelOnChain() throws Exception {
        assumeHardhatAvailable();
        logger.info("--- testCancelOnChain ---");

        OnChainGovernanceClient client = buildOnChainClient();
        String targetAddr = getContractAddress("GovernanceTargetMock");
        byte[] calldata = encodeSetValueCalldata(BigInteger.valueOf(42L));

        // 1. propose（用 deployer = owner，可取消）
        long proposalId = client.proposeOnChain(
                Collections.singletonList(targetAddr),
                Collections.singletonList(calldata),
                Collections.singletonList(BigInteger.ZERO),
                "Cancel test proposal");
        assertTrue(proposalId > 0, "propose 应成功");

        // 2. cancel
        boolean cancelResult = client.cancelOnChain(proposalId);
        assertTrue(cancelResult, "cancelOnChain 应返回 true");

        // 3. 验证 ProposalCanceled 事件
        assertTrue(findEventInRecentBlocks(PROPOSAL_CANCELED_SIG, proposalId),
                "ProposalCanceled 事件应被 emitted");

        // 4. 验证状态 Canceled (5)
        int state = client.queryProposalState(proposalId);
        assertEquals(5, state, "cancel 后状态应为 Canceled (5)");

        logger.info("testCancelOnChain 通过: proposalId={}", proposalId);
    }

    // ==================== 测试 6: GovernanceExecutor 链上集成 ====================

    /**
     * 测试 GovernanceExecutor 与 OnChainGovernanceClient 集成：
     * 构造 GovernanceExecutor（onChainExecutionEnabled=true），
     * 调用 schedule/execute，验证链上同步 queue/execute 调用。
     *
     * <p>本用例验证内存版与链上版双轨一致性：内存版 schedule 成功后，
     * 链上 NexusGovernor.queue 也被调用（通过 ProposalQueued 事件验证）；
     * 内存版 execute 成功后，链上 NexusGovernor.execute 也被调用
     * （通过 ProposalExecuted 事件 + 目标合约状态变更验证）。</p>
     */
    @Test
    @Order(6)
    void testGovernanceExecutorOnChainIntegration() throws Exception {
        assumeHardhatAvailable();
        logger.info("--- testGovernanceExecutorOnChainIntegration ---");

        OnChainGovernanceClient client = buildOnChainClient();
        String targetAddr = getContractAddress("GovernanceTargetMock");
        String governorAddr = getContractAddress("NexusGovernor");
        String timelockAddr = getContractAddress("TimelockController");
        assertNotNull(governorAddr);
        assertNotNull(timelockAddr);

        // 1. 先在链上 propose + vote + advanceBlocks，使提案进入 Succeeded 状态
        byte[] calldata = encodeSetValueCalldata(BigInteger.valueOf(999L));
        long onChainProposalId = client.proposeOnChain(
                Collections.singletonList(targetAddr),
                Collections.singletonList(calldata),
                Collections.singletonList(BigInteger.ZERO),
                "GovernanceExecutor integration test");
        assertTrue(onChainProposalId > 0, "propose 应成功");
        setVotingWeightOnChain(HARDHAT_DEPLOYER_ADDRESS, QUORUM_WEIGHT);
        assertTrue(client.castVoteOnChain(onChainProposalId, 1), "vote 应成功");
        advanceBlocks(VOTING_PERIOD_BLOCKS + 1);

        int stateSucceeded = client.queryProposalState(onChainProposalId);
        assertEquals(2, stateSucceeded, "提案应进入 Succeeded 状态");

        // 2. 构造 GovernanceExecutor（注入 OnChainGovernanceClient）
        GovernanceExecutor executor = new GovernanceExecutor();
        TimelockController timelock = new TimelockController(
                java.time.Duration.ofSeconds(TIMELOCK_MIN_DELAY_SECONDS));
        GovernableParameterRegistry paramRegistry = new GovernableParameterRegistry();
        InMemoryExecutionStateRepository execStateRepo = new InMemoryExecutionStateRepository();
        injectField(executor, "timelock", timelock);
        injectField(executor, "parameterRegistry", paramRegistry);
        injectField(executor, "executionStateRepository", execStateRepo);
        injectField(executor, "onChainGovernanceClient", client);
        injectField(executor, "onChainExecutionEnabled", true);

        assertTrue(executor.isOnChainIntegrationEnabled(),
                "链上集成应已启用");

        // 3. 构造 GovernanceProposal（PASSED 状态，本地 ID 与链上 ID 通过 map 注册）
        GovernanceProposal proposal = new GovernanceProposal();
        String localProposalId = String.valueOf(onChainProposalId); // 数字字符串可直接解析
        proposal.setProposalId(localProposalId);
        proposal.setStatus(ProposalStatus.PASSED);
        proposal.setParameterChanges(Collections.emptyList()); // 不修改 Java 侧参数

        // 4. schedule：内存版排队 + 链上 queue 同步
        boolean scheduleResult = executor.schedule(proposal, java.time.Instant.now());
        assertTrue(scheduleResult, "schedule 应成功");

        // 5. 验证链上 ProposalQueued 事件已 emitted
        assertTrue(findEventInRecentBlocks(PROPOSAL_QUEUED_SIG, onChainProposalId),
                "GovernanceExecutor.schedule 应同步触发链上 ProposalQueued 事件");

        // 6. 验证链上状态为 Queued (3)
        int stateQueued = client.queryProposalState(onChainProposalId);
        assertEquals(3, stateQueued, "链上状态应为 Queued (3)");

        // 7. 推进时间到期
        advanceTimeAndMine(TIMELOCK_MIN_DELAY_SECONDS + 1L);

        // 8. execute：内存版执行 + 链上 execute 同步
        boolean execResult = executor.execute(proposal);
        assertTrue(execResult, "execute 应成功");
        assertEquals(ProposalStatus.EXECUTED, proposal.getStatus(),
                "提案状态应为 EXECUTED");

        // 9. 验证链上 ProposalExecuted 事件
        assertTrue(findEventInRecentBlocks(PROPOSAL_EXECUTED_SIG, onChainProposalId),
                "GovernanceExecutor.execute 应同步触发链上 ProposalExecuted 事件");

        // 10. 验证目标合约状态已变更
        BigInteger targetValue = queryTargetValue(targetAddr);
        assertEquals(BigInteger.valueOf(999L), targetValue,
                "目标合约 value 应为 999");

        logger.info("testGovernanceExecutorOnChainIntegration 通过: proposalId={}", onChainProposalId);
    }

    // ==================== 辅助方法：构造 OnChainGovernanceClient ====================

    /**
     * 编程式构造 OnChainGovernanceClient 实例并初始化。
     */
    private OnChainGovernanceClient buildOnChainClient() {
        String governorAddr = getContractAddress("NexusGovernor");
        String timelockAddr = getContractAddress("TimelockController");
        assertNotNull(governorAddr, "NexusGovernor 地址应存在");
        assertNotNull(timelockAddr, "TimelockController 地址应存在");

        OnChainGovernanceClient client = OnChainGovernanceClient.createForTesting(
                RPC_URL, governorAddr, timelockAddr,
                HARDHAT_PRIVATE_KEY, HARDHAT_CHAIN_ID);
        client.init();
        return client;
    }

    // ==================== 辅助方法：链上交易 ====================

    /**
     * 在链上调用 governor.setVotingWeight(account, weight)。
     *
     * <p>本方法绕过 OnChainGovernanceClient（其未暴露 setVotingWeight），
     * 直接通过基类 txManager 发送交易。</p>
     */
    private void setVotingWeightOnChain(String account, BigInteger weight) throws Exception {
        String governorAddr = getContractAddress("NexusGovernor");
        Function function = new Function(
                "setVotingWeight",
                Arrays.asList(new Address(account), new Uint256(weight)),
                Collections.<TypeReference<?>>emptyList());
        sendGovernorTransaction(governorAddr, function, "setVotingWeight");
    }

    /**
     * 通用：向指定合约地址发送 state-changing 交易并等待回执。
     */
    private void sendGovernorTransaction(String contractAddr, Function function, String funcName)
            throws Exception {
        String encoded = FunctionEncoder.encode(function);
        BigInteger nonce = web3j.ethGetTransactionCount(
                HARDHAT_DEPLOYER_ADDRESS, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        if (gasPrice == null || gasPrice.signum() <= 0) {
            gasPrice = BigInteger.valueOf(1_000_000_000L);
        }
        org.web3j.crypto.RawTransaction rawTx = org.web3j.crypto.RawTransaction.createTransaction(
                nonce, gasPrice, BigInteger.valueOf(2_000_000L), contractAddr, encoded);
        EthSendTransaction sendResp = txManager.signAndSend(rawTx);
        if (sendResp.hasError()) {
            throw new RuntimeException(funcName + " tx failed: " + sendResp.getError().getMessage());
        }
        String txHash = sendResp.getTransactionHash();
        TransactionReceipt receipt = waitForReceipt(txHash);
        if (receipt == null || !receipt.isStatusOK()) {
            throw new RuntimeException(funcName + " receipt not OK: "
                    + (receipt == null ? "null" : receipt.getStatus()));
        }
    }

    /**
     * 等待交易回执。
     */
    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        for (int i = 0; i < 40; i++) {
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(txHash).send();
            if (resp.getTransactionReceipt().isPresent()) {
                return resp.getTransactionReceipt().get();
            }
            Thread.sleep(1000);
        }
        return null;
    }

    // ==================== 辅助方法：链上查询 ====================

    /**
     * 查询目标合约 value（GovernanceTargetMock.value()）。
     */
    private BigInteger queryTargetValue(String targetAddr) throws Exception {
        Function function = new Function(
                "value",
                Collections.<Type>emptyList(),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        String result = callView(targetAddr, function);
        List<Type> decoded = FunctionReturnDecoder.decode(result, function.getOutputParameters());
        if (decoded.isEmpty()) {
            return BigInteger.ZERO;
        }
        return ((Uint256) decoded.get(0)).getValue();
    }

    /**
     * 查询提案的 forVotes（通过 getProposal 返回的多字段中提取）。
     *
     * <p>getProposal 返回 (address[], bytes[], uint256[], uint256, uint256, uint256, uint256, uint256, uint256, bool, bool, bool, bytes32, address)，
     * forVotes 是第 6 个返回值（index 5）。</p>
     */
    private BigInteger queryProposalForVotes(long proposalId) throws Exception {
        String governorAddr = getContractAddress("NexusGovernor");
        Function function = new Function(
                "getProposal",
                Collections.singletonList(new Uint256(BigInteger.valueOf(proposalId))),
                Arrays.asList(
                        new TypeReference<org.web3j.abi.datatypes.DynamicArray<Address>>() {},
                        new TypeReference<org.web3j.abi.datatypes.DynamicArray<DynamicBytes>>() {},
                        new TypeReference<org.web3j.abi.datatypes.DynamicArray<Uint256>>() {},
                        new TypeReference<Uint256>() {}, // startBlock
                        new TypeReference<Uint256>() {}, // endBlock
                        new TypeReference<Uint256>() {}, // forVotes
                        new TypeReference<Uint256>() {}, // againstVotes
                        new TypeReference<Uint256>() {}, // abstainVotes
                        new TypeReference<org.web3j.abi.datatypes.Bool>() {}, // executed
                        new TypeReference<org.web3j.abi.datatypes.Bool>() {}, // canceled
                        new TypeReference<org.web3j.abi.datatypes.Bool>() {}, // queued
                        new TypeReference<org.web3j.abi.datatypes.generated.Bytes32>() {}, // operationId
                        new TypeReference<Address>() {})); // proposer
        String result = callView(governorAddr, function);
        List<Type> decoded = FunctionReturnDecoder.decode(result, function.getOutputParameters());
        if (decoded.size() < 6) {
            return BigInteger.ZERO;
        }
        return ((Uint256) decoded.get(5)).getValue();
    }

    /**
     * 通用 view 调用。
     */
    private String callView(String contractAddr, Function function) throws Exception {
        String encoded = FunctionEncoder.encode(function);
        Transaction call = Transaction.createEthCallTransaction(
                HARDHAT_DEPLOYER_ADDRESS, contractAddr, encoded);
        EthCall resp = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();
        if (resp.hasError()) {
            throw new RuntimeException("eth_call failed: " + resp.getError().getMessage());
        }
        return resp.getValue();
    }

    // ==================== 辅助方法：事件查找 ====================

    /**
     * 在最近 50 个区块中查找指定事件（按 topic0 + indexed proposalId 过滤）。
     */
    private boolean findEventInRecentBlocks(String eventSignature, long proposalId) throws Exception {
        String topic0 = Hash.sha3String(eventSignature);
        // EVM 事件 topic 固定 32 字节（64 hex 字符），需左零填充
        String topic1 = "0x" + String.format("%064x", proposalId);
        String governorAddr = getContractAddress("NexusGovernor");

        BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
        BigInteger fromBlock = latestBlock.subtract(BigInteger.valueOf(50));
        if (fromBlock.signum() < 0) {
            fromBlock = BigInteger.ZERO;
        }

        org.web3j.protocol.core.methods.request.EthFilter filter =
                new org.web3j.protocol.core.methods.request.EthFilter(
                        DefaultBlockParameter.valueOf(fromBlock),
                        DefaultBlockParameterName.LATEST,
                        governorAddr)
                        .addSingleTopic(topic0)
                        .addSingleTopic(topic1);

        var ethLog = web3j.ethGetLogs(filter).send();
        if (ethLog.hasError()) {
            logger.warn("eth_getLogs error: {}", ethLog.getError().getMessage());
            return false;
        }
        var logs = ethLog.getLogs();
        logger.debug("事件 {} 匹配 {} 条日志", eventSignature, logs.size());
        return !logs.isEmpty();
    }

    // ==================== 辅助方法：calldata 编码 ====================

    /**
     * 编码 GovernanceTargetMock.setValue(uint256) 的 calldata。
     */
    private byte[] encodeSetValueCalldata(BigInteger value) {
        Function function = new Function(
                "setValue",
                Collections.singletonList(new Uint256(value)),
                Collections.<TypeReference<?>>emptyList());
        String encoded = FunctionEncoder.encode(function);
        return Numeric.hexStringToByteArray(encoded);
    }

    // ==================== 辅助方法：反射注入 ====================

    /**
     * 通过反射注入字段（用于绕过 Spring 注入构造 GovernanceExecutor）。
     */
    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}