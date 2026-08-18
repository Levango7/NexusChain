package org.nexus.l2.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.nexus.integration.AbstractHardhatIntegrationTest;
import org.nexus.l2.Web3jL1ContractClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicStruct;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2→L1 端到端集成测试（P0-3c）。
 *
 * <p>在真实 Hardhat 本地 L1 节点上验证完整的 L2→L1 交互流程：
 * 状态根提交、批次验证、提款最终化、欺诈证明挑战，
 * 以及生产级 {@code submitWithdrawals + finalizeWithdrawsWithProof} 的 Merkle proof 验证。</p>
 *
 * <h2>测试流程</h2>
 * <ol>
 *   <li><b>@BeforeAll</b>（基类 {@link AbstractHardhatIntegrationTest}）：
 *       启动 Hardhat 节点 → 运行 {@code scripts/deploy-bridge.js} 部署
 *       L2Bridge + MockERC20 + BridgeSource + BridgeTarget + ERC20Mock → 初始化 Web3j</li>
 *   <li><b>testSubmitStateRoot</b>：提交状态根到 L1，验证事件 emitted</li>
 *   <li><b>testMarkBatchVerified</b>：标记批次验证通过</li>
 *   <li><b>testFinalizeWithdraws</b>：最终化提款流程（向后兼容版本）</li>
 *   <li><b>testChallengeBatch</b>：欺诈证明挑战</li>
 *   <li><b>testFraudProofChallenge</b>：完整欺诈证明场景（无效状态根→挑战→INVALID）</li>
 *   <li><b>testSubmitWithdrawalsAndFinalizeWithProof</b>（P0-3c 新增）：
 *       submitWithdrawals → 等待挑战期 → markBatchVerified →
 *       finalizeWithdrawsWithProof（Merkle proof 验证 + ERC20 实际转账）</li>
 *   <li><b>@AfterAll</b>：关闭 l1Client + 基类清理 Hardhat 节点</li>
 * </ol>
 *
 * <h2>跳过策略</h2>
 * <p>Hardhat 不可用时通过 {@link #assumeHardhatAvailable()} 优雅跳过。</p>
 *
 * <h2>部署合约</h2>
 * <p>使用 {@code scripts/deploy-bridge.js}，部署产物写入 {@code deployed-bridge.json}，
 * 包含以下合约地址（通过 {@link #getContractAddress(String)} 查询）：</p>
 * <ul>
 *   <li>{@code L2Bridge} — L2↔L1 桥合约（挑战期 60 秒）</li>
 *   <li>{@code MockERC20} — 测试用 ERC20 代币</li>
 *   <li>{@code BridgeSource} / {@code BridgeTarget} — 跨链桥源/目标合约</li>
 *   <li>{@code ERC20Mock} — 另一个测试代币</li>
 * </ul>
 *
 * @since 2.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class L2L1EndToEndTest extends AbstractHardhatIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(L2L1EndToEndTest.class);

    // ==================== 常量 ====================

    /** gas 上限 */
    private static final long GAS_LIMIT = 2_000_000L;

    /** 默认 gas price（wei） */
    private static final BigInteger DEFAULT_GAS_PRICE = BigInteger.valueOf(1_000_000_000L);

    /** L2Bridge 挑战期（秒，与 deploy-bridge.js 中设置一致） */
    private static final long CHALLENGE_PERIOD_SECONDS = 60L;

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

    /** L2Bridge 合约地址（从部署产物读取） */
    private String contractAddress;

    /** 被测对象：Web3j L1 合约客户端 */
    private Web3jL1ContractClient l1Client;

    // ==================== 子类钩子 ====================

    @Override
    protected String deployScript() {
        return "scripts/deploy-bridge.js";
    }

    @Override
    protected String deploymentJsonName() {
        return "deployed-bridge.json";
    }

    // ==================== @AfterAll：清理 l1Client ====================

    /**
     * 关闭 l1Client（基类的 @AfterAll 会关闭 web3j 与 Hardhat 节点）。
     *
     * <p>JUnit5 执行顺序：子类 @AfterAll 先于基类 @AfterAll。</p>
     */
    @AfterAll
    protected void tearDownL1Client() {
        if (l1Client != null) {
            try {
                l1Client.shutdown();
            } catch (Exception e) {
                logger.debug("l1Client shutdown error: {}", e.getMessage());
            }
            l1Client = null;
        }
    }

    // ==================== 延迟初始化 l1Client ====================

    /**
     * 延迟初始化 {@link #l1Client}（基类 @BeforeAll 完成后调用）。
     *
     * <p>从 {@link #getContractAddress(String)} 获取 L2Bridge 地址，
     * 构造 {@link Web3jL1ContractClient}。</p>
     */
    private void ensureL1Client() {
        if (l1Client != null) {
            return;
        }
        contractAddress = getContractAddress("L2Bridge");
        if (contractAddress == null) {
            throw new IllegalStateException("部署产物中未找到 L2Bridge 合约地址");
        }
        l1Client = Web3jL1ContractClient.createForTesting(
                RPC_URL, contractAddress, HARDHAT_PRIVATE_KEY, HARDHAT_CHAIN_ID);
        l1Client.init();
        if (!l1Client.isWeb3jReady()) {
            throw new IllegalStateException("Web3jL1ContractClient 初始化失败");
        }
        logger.info("l1Client 已初始化: L2Bridge={}", contractAddress);
    }

    // ==================== 测试 1: submitStateRoot ====================

    /**
     * 测试 submitStateRoot：提交状态根到 L1，验证事件 emitted。
     */
    @Test
    @Order(1)
    void testSubmitStateRoot() throws Exception {
        assumeHardhatAvailable();
        ensureL1Client();

        long batchId = 1001L;
        String stateRoot = "0x" + repeat("ab", 32);

        boolean result = l1Client.submitStateRootToL1(batchId, stateRoot);
        assertTrue(result, "submitStateRootToL1 应返回 true");

        assertEquals(stateRoot, l1Client.getStateRootOnL1(batchId),
                "内存应同步记录状态根");

        String onChainRoot = callBatchStateRoot(batchId);
        assertNotNull(onChainRoot, "链上 batchStateRoot 应非 null");
        assertEquals(stateRoot.toLowerCase(), onChainRoot.toLowerCase(),
                "链上状态根应与提交的一致");

        boolean eventFound = findEventInRecentBlocks(
                STATE_ROOT_SUBMITTED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "StateRootSubmitted 事件应被 emitted");

        logger.info("testSubmitStateRoot 通过: batchId={}, root={}", batchId, stateRoot);
    }

    // ==================== 测试 2: markBatchVerified ====================

    /**
     * 测试 markBatchVerified：标记批次验证通过。
     */
    @Test
    @Order(2)
    void testMarkBatchVerified() throws Exception {
        assumeHardhatAvailable();
        ensureL1Client();

        long batchId = 1002L;
        String stateRoot = "0x" + repeat("cd", 32);

        assertTrue(l1Client.submitStateRootToL1(batchId, stateRoot),
                "提交状态根应成功");

        // 推进挑战期
        advanceTimeAndMine(CHALLENGE_PERIOD_SECONDS + 1L);

        boolean result = l1Client.markBatchVerifiedOnL1(batchId);
        assertTrue(result, "markBatchVerifiedOnL1 应返回 true");

        assertTrue(l1Client.isFinalizedOnL1(batchId),
                "内存应标记批次为 VERIFIED");

        assertTrue(callIsBatchVerified(batchId), "链上 isBatchVerified 应返回 true");

        boolean eventFound = findEventInRecentBlocks(
                BATCH_VERIFIED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "BatchVerified 事件应被 emitted");

        logger.info("testMarkBatchVerified 通过: batchId={}", batchId);
    }

    // ==================== 测试 3: finalizeWithdraws ====================

    /**
     * 测试 finalizeWithdraws：最终化提款流程（向后兼容版本）。
     */
    @Test
    @Order(3)
    void testFinalizeWithdraws() throws Exception {
        assumeHardhatAvailable();
        ensureL1Client();

        long batchId = 1003L;
        String stateRoot = "0x" + repeat("ef", 32);

        assertTrue(l1Client.submitStateRootToL1(batchId, stateRoot));
        advanceTimeAndMine(CHALLENGE_PERIOD_SECONDS + 1L);
        assertTrue(l1Client.markBatchVerifiedOnL1(batchId));

        boolean result = l1Client.finalizeWithdrawsOnL1(batchId);
        assertTrue(result, "finalizeWithdrawsOnL1 应返回 true");

        assertTrue(l1Client.isWithdrawsFinalizedOnL1(batchId),
                "内存应标记提款为 finalized");

        assertTrue(callIsWithdrawsFinalized(batchId),
                "链上 isWithdrawsFinalized 应返回 true");

        boolean eventFound = findEventInRecentBlocks(
                WITHDRAW_FINALIZED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "WithdrawFinalized 事件应被 emitted");

        logger.info("testFinalizeWithdraws 通过: batchId={}", batchId);
    }

    // ==================== 测试 4: challengeBatch ====================

    /**
     * 测试 challengeBatch：欺诈证明挑战。
     */
    @Test
    @Order(4)
    void testChallengeBatch() throws Exception {
        assumeHardhatAvailable();
        ensureL1Client();

        long batchId = 1004L;
        String stateRoot = "0x" + repeat("12", 32);

        assertTrue(l1Client.submitStateRootToL1(batchId, stateRoot));

        byte[] proofData = new byte[64];
        Arrays.fill(proofData, (byte) 0x42);

        boolean result = l1Client.challengeBatchOnL1(batchId, proofData);
        assertTrue(result, "challengeBatchOnL1 应返回 true");

        assertTrue(l1Client.isChallengedOnL1(batchId),
                "内存应标记批次为 challenged");

        assertTrue(callIsBatchChallenged(batchId),
                "链上 isBatchChallenged 应返回 true");

        boolean eventFound = findEventInRecentBlocks(
                BATCH_CHALLENGED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "BatchChallenged 事件应被 emitted");

        logger.info("testChallengeBatch 通过: batchId={}", batchId);
    }

    // ==================== 测试 5: 欺诈证明挑战完整场景 ====================

    /**
     * 欺诈证明挑战完整场景：提交无效状态根 → 发起挑战 → 验证批次被标记 INVALID。
     */
    @Test
    @Order(5)
    void testFraudProofChallenge_InvalidStateRoot_ChallengedAndInvalid() throws Exception {
        assumeHardhatAvailable();
        ensureL1Client();

        long batchId = 2001L;

        String actualStateRoot = "0x" + repeat("fe", 32);
        String fraudulentRoot = "0x" + repeat("00", 32);
        assertFalse(actualStateRoot.equals(fraudulentRoot),
                "无效状态根应与真实状态根不同");

        assertTrue(l1Client.submitStateRootToL1(batchId, fraudulentRoot),
                "提交无效状态根应成功");

        byte[] proofData = buildFraudProofData(batchId, actualStateRoot, fraudulentRoot);
        assertNotNull(proofData, "欺诈证明数据应非 null");
        assertTrue(proofData.length > 0, "欺诈证明数据应非空");

        boolean challengeResult = l1Client.challengeBatchOnL1(batchId, proofData);
        assertTrue(challengeResult, "挑战应成功");

        assertTrue(l1Client.isChallengedOnL1(batchId),
                "内存应标记批次为 challenged");
        assertTrue(callIsBatchChallenged(batchId),
                "链上批次应被标记为 CHALLENGED");

        boolean verifyAfterChallenge = tryMarkBatchVerifiedShouldFail(batchId);
        assertTrue(verifyAfterChallenge,
                "被挑战的批次无法再被 markBatchVerified（合约 revert）");

        boolean eventFound = findEventInRecentBlocks(
                BATCH_CHALLENGED_EVENT,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(batchId)));
        assertTrue(eventFound, "BatchChallenged 事件应被 emitted");

        logger.info("testFraudProofChallenge 通过: batchId={}", batchId);
    }

    // ==================== 测试 6: submitWithdrawals + finalizeWithdrawsWithProof（P0-3c 新增） ====================

    /**
     * 测试 submitWithdrawals + finalizeWithdrawsWithProof 完整流程（P0-3c 核心）。
     *
     * <p>验证生产级 L2→L1 提款流程，包含 Merkle proof 验证与 ERC20 实际转账：</p>
     * <ol>
     *   <li>部署 MockERC20 + L2Bridge（由 deploy-bridge.js 完成）</li>
     *   <li>设置授权 Sequencer（owner 调用 setAuthorizedSequencer）</li>
     *   <li>mint ERC20 到 L2Bridge 合约地址（供提款转出）</li>
     *   <li>构造 3 笔提款（不同 recipient），构建 Merkle 树</li>
     *   <li>Sequencer 提交状态根 submitStateRoot</li>
     *   <li>Sequencer 提交提款根 submitWithdrawals</li>
     *   <li>等待挑战期结束（evm_increaseTime + evm_mine）</li>
     *   <li>markBatchVerified 标记批次验证通过</li>
     *   <li>对每笔提款调用 finalizeWithdrawsWithProof（提供 Merkle proof）</li>
     *   <li>验证 ERC20 已转移到各 recipient</li>
     *   <li>验证 withdrawalFinalized[batchId][index] == true</li>
     * </ol>
     *
     * <p>本测试验证 {@link MerkleProofBuilder} 生成的 proof 能被 Solidity
     * {@code MerkleLib.verifyMerkleProof} 验证通过，确保 Java 侧与链上
     * Merkle 树构建完全一致。</p>
     */
    @Test
    @Order(6)
    void testSubmitWithdrawalsAndFinalizeWithProof() throws Exception {
        assumeHardhatAvailable();
        ensureL1Client();
        logger.info("--- testSubmitWithdrawalsAndFinalizeWithProof ---");

        // ---------- 1. 获取合约地址 ----------
        String l2BridgeAddr = getContractAddress("L2Bridge");
        String mockErc20Addr = getContractAddress("MockERC20");
        assertNotNull(l2BridgeAddr, "L2Bridge 地址应存在");
        assertNotNull(mockErc20Addr, "MockERC20 地址应存在");
        logger.info("L2Bridge={}, MockERC20={}", l2BridgeAddr, mockErc20Addr);

        // ---------- 2. 创建 MockERC20 wrapper ----------
        MockERC20 token = new MockERC20(mockErc20Addr, web3j, txManager, credentials);

        // ---------- 3. 设置授权 Sequencer ----------
        //    L2Bridge 构造时 authorizedSequencer = address(0)（向后兼容模式）
        //    submitWithdrawals 要求 onlySequencer，故需先设置
        //    设置为 deployer 自己（HARDHAT_DEPLOYER_ADDRESS）
        logger.info("设置授权 Sequencer: {}", HARDHAT_DEPLOYER_ADDRESS);
        sendL2BridgeTransaction(buildFunction(
                "setAuthorizedSequencer",
                Collections.singletonList(new Address(HARDHAT_DEPLOYER_ADDRESS)),
                Collections.<TypeReference<?>>emptyList()), "setAuthorizedSequencer");

        // ---------- 4. 构造 3 笔提款 ----------
        //    使用 Hardhat 预置账户 #1/#2/#3 作为 recipient
        //    Hardhat mnemonic: test test test test test test test test test test test junk
        //    #1: 0x70997970C51812dc3A010C7d01b50e0d17dc79C8
        //    #2: 0x3C44CdDdB6a900fa2B585dd7c0FFfCa9Ab84b18
        //    #3: 0x90F79bf6EB2c6f8fded18e0F8a09bA40D6B9c6f
        String recipient1 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
        String recipient2 = "0x3C44CdDdB6a900fa2B585dd7c0FFfCa9Ab84b18";
        String recipient3 = "0x90F79bf6EB2c6f8fded18e0F8a09bA40D6B9c6f6";

        BigInteger amount1 = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18)); // 100 MWT
        BigInteger amount2 = BigInteger.valueOf(200).multiply(BigInteger.TEN.pow(18)); // 200 MWT
        BigInteger amount3 = BigInteger.valueOf(300).multiply(BigInteger.TEN.pow(18)); // 300 MWT
        BigInteger totalAmount = amount1.add(amount2).add(amount3);

        // ---------- 5. mint ERC20 到 L2Bridge 合约地址 ----------
        logger.info("mint {} MWT 到 L2Bridge", totalAmount.divide(BigInteger.TEN.pow(18)));
        token.mint(l2BridgeAddr, totalAmount);

        // 验证 L2Bridge 持有足够余额
        BigInteger bridgeBalance = token.balanceOf(l2BridgeAddr);
        assertEquals(totalAmount, bridgeBalance, "L2Bridge 应持有 mint 的全部代币");

        // ---------- 6. 构建 Merkle 树 ----------
        List<MerkleProofBuilder.WithdrawalLeaf> withdrawals = Arrays.asList(
                new MerkleProofBuilder.WithdrawalLeaf(mockErc20Addr, recipient1, amount1),
                new MerkleProofBuilder.WithdrawalLeaf(mockErc20Addr, recipient2, amount2),
                new MerkleProofBuilder.WithdrawalLeaf(mockErc20Addr, recipient3, amount3)
        );
        MerkleProofBuilder merkleTree = MerkleProofBuilder.build(withdrawals);
        byte[] withdrawalRoot = merkleTree.getRoot();
        String withdrawalRootHex = Numeric.toHexString(withdrawalRoot);

        // 本地验证所有 proof 自洽
        assertTrue(merkleTree.verifyAllProofs(), "所有 Merkle proof 应自洽");

        logger.info("Merkle 树构建完成: 3 笔提款, root={}", withdrawalRootHex);

        // ---------- 7. Sequencer 提交状态根 ----------
        long batchId = 3001L;
        String stateRoot = "0x" + repeat("99", 32);
        logger.info("提交状态根: batchId={}, stateRoot={}", batchId, stateRoot);
        sendL2BridgeTransaction(buildFunction(
                "submitStateRoot",
                Arrays.asList(new Bytes32(Numeric.hexStringToByteArray(stateRoot)),
                        new Uint256(BigInteger.valueOf(batchId))),
                Collections.<TypeReference<?>>emptyList()), "submitStateRoot");

        // ---------- 8. Sequencer 提交提款根 ----------
        //    submitWithdrawals(uint256 batchId, Withdrawal[] withdrawals, bytes32 withdrawalRoot)
        //    Withdrawal struct: (address token, address recipient, uint256 amount)
        logger.info("提交提款根: batchId={}, root={}", batchId, withdrawalRootHex);
        List<DynamicStruct> withdrawalStructs = new ArrayList<>();
        for (MerkleProofBuilder.WithdrawalLeaf w : withdrawals) {
            withdrawalStructs.add(new WithdrawalAbi(w.token, w.recipient, w.amount));
        }
        sendL2BridgeTransaction(buildFunction(
                "submitWithdrawals",
                Arrays.asList(
                        new Uint256(BigInteger.valueOf(batchId)),
                        new DynamicArray<>(DynamicStruct.class, withdrawalStructs),
                        new Bytes32(withdrawalRoot)),
                Collections.<TypeReference<?>>emptyList()), "submitWithdrawals");

        // 验证链上提款根已记录
        String onChainRoot = callWithdrawalRoot(batchId);
        assertEquals(withdrawalRootHex.toLowerCase(), onChainRoot.toLowerCase(),
                "链上提款根应与提交的一致");

        // ---------- 9. 等待挑战期结束 ----------
        logger.info("等待挑战期: {} 秒", CHALLENGE_PERIOD_SECONDS);
        advanceTimeAndMine(CHALLENGE_PERIOD_SECONDS + 1L);

        // ---------- 10. markBatchVerified ----------
        logger.info("标记批次验证: batchId={}", batchId);
        sendL2BridgeTransaction(buildFunction(
                "markBatchVerified",
                Collections.singletonList(new Uint256(BigInteger.valueOf(batchId))),
                Collections.<TypeReference<?>>emptyList()), "markBatchVerified");
        assertTrue(callIsBatchVerified(batchId), "批次应已 VERIFIED");

        // ---------- 11. 对每笔提款调用 finalizeWithdrawsWithProof ----------
        String[] recipients = {recipient1, recipient2, recipient3};
        BigInteger[] amounts = {amount1, amount2, amount3};

        for (int i = 0; i < withdrawals.size(); i++) {
            MerkleProofBuilder.Proof proof = merkleTree.getProof(i);
            logger.info("最终化提款 #{}/{}: recipient={}, amount={}",
                    i + 1, withdrawals.size(), recipients[i], amounts[i]);

            // 构造 proof 参数：bytes32[] proof + bool[] isRight
            List<Bytes32> proofBytes32 = new ArrayList<>();
            for (byte[] sibling : proof.siblings) {
                proofBytes32.add(new Bytes32(sibling));
            }
            List<Bool> isRightList = new ArrayList<>();
            for (Boolean b : proof.isRight) {
                isRightList.add(new Bool(b));
            }

            // finalizeWithdrawsWithProof(
            //   uint256 batchId, uint256 index, address token, address recipient, uint256 amount,
            //   bytes32[] proof, bool[] isRight)
            sendL2BridgeTransaction(buildFunction(
                    "finalizeWithdrawsWithProof",
                    Arrays.asList(
                            new Uint256(BigInteger.valueOf(batchId)),
                            new Uint256(BigInteger.valueOf(i)),
                            new Address(mockErc20Addr),
                            new Address(recipients[i]),
                            new Uint256(amounts[i]),
                            new DynamicArray<>(Bytes32.class, proofBytes32),
                            new DynamicArray<>(Bool.class, isRightList)),
                    Collections.<TypeReference<?>>emptyList()), "finalizeWithdrawsWithProof");

            // ---------- 12. 验证 ERC20 已转移 ----------
            BigInteger recipientBalance = token.balanceOf(recipients[i]);
            assertEquals(amounts[i], recipientBalance,
                    "recipient[" + i + "] 应收到提款金额");

            // ---------- 13. 验证 withdrawalFinalized[batchId][index] ----------
            assertTrue(callIsWithdrawalFinalized(batchId, i),
                    "withdrawalFinalized[" + batchId + "][" + i + "] 应为 true");
        }

        // ---------- 最终验证：L2Bridge 余额应为 0 ----------
        BigInteger remainingBridgeBalance = token.balanceOf(l2BridgeAddr);
        assertEquals(BigInteger.ZERO, remainingBridgeBalance,
                "所有提款完成后 L2Bridge 余额应为 0");

        logger.info("testSubmitWithdrawalsAndFinalizeWithProof 通过: batchId={}, 3 笔提款全部最终化",
                batchId);
    }

    // ==================== 辅助方法：L2Bridge 交易发送 ====================

    /**
     * 构造 Function 对象的辅助方法。
     */
    private Function buildFunction(String name, List<Type> inputs, List<TypeReference<?>> outputs) {
        return new Function(name, inputs, outputs);
    }

    /**
     * 向 L2Bridge 合约发送 state-changing 交易并等待回执。
     *
     * @param function 函数编码
     * @param funcName 函数名（用于错误消息）
     * @throws RuntimeException 如果交易失败或回执状态非 OK
     */
    private void sendL2BridgeTransaction(Function function, String funcName) throws Exception {
        String encoded = FunctionEncoder.encode(function);
        BigInteger nonce = web3j.ethGetTransactionCount(
                HARDHAT_DEPLOYER_ADDRESS, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        if (gasPrice == null || gasPrice.signum() <= 0) {
            gasPrice = DEFAULT_GAS_PRICE;
        }
        org.web3j.crypto.RawTransaction rawTx = org.web3j.crypto.RawTransaction.createTransaction(
                nonce, gasPrice, BigInteger.valueOf(GAS_LIMIT), contractAddress, encoded);
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
     * 等待交易回执（轮询，最多 40 秒）。
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
                Arrays.asList(new TypeReference<Bool>() {}));
        return decodeBoolResult(callViewFunction(function));
    }

    /**
     * 查询合约 isBatchChallenged(batchId)。
     */
    private boolean callIsBatchChallenged(long batchId) throws Exception {
        Function function = new Function(
                "isBatchChallenged",
                Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                Arrays.asList(new TypeReference<Bool>() {}));
        return decodeBoolResult(callViewFunction(function));
    }

    /**
     * 查询合约 isWithdrawsFinalized(batchId)。
     */
    private boolean callIsWithdrawsFinalized(long batchId) throws Exception {
        Function function = new Function(
                "isWithdrawsFinalized",
                Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                Arrays.asList(new TypeReference<Bool>() {}));
        return decodeBoolResult(callViewFunction(function));
    }

    /**
     * 解码 eth_call 返回的 32 字节 ABI 编码 bool。
     *
     * <p>eth_call 对 bool 返回值编码为 32 字节（0x000...001 表示 true，
     * 0x000...000 表示 false）。本方法提取最后 1 个 hex 字符判断真假。</p>
     *
     * @param result eth_call 返回的 hex 字符串（如 "0x0000...0001"）
     * @return true 表示链上返回 true
     */
    private static boolean decodeBoolResult(String result) {
        if (result == null || result.length() < 3) {
            return false;
        }
        // 去掉 0x 前缀，取最后一位 hex 字符
        String hex = result.startsWith("0x") ? result.substring(2) : result;
        if (hex.isEmpty()) {
            return false;
        }
        char lastChar = hex.charAt(hex.length() - 1);
        return lastChar == '1' || lastChar == '3' || lastChar == '5'
                || lastChar == '7' || lastChar == '9' || lastChar == 'b'
                || lastChar == 'B' || lastChar == 'd' || lastChar == 'D'
                || lastChar == 'f' || lastChar == 'F';
    }

    /**
     * 查询合约 getWithdrawalRoot(batchId)。
     */
    private String callWithdrawalRoot(long batchId) throws Exception {
        Function function = new Function(
                "getWithdrawalRoot",
                Arrays.asList(new Uint256(BigInteger.valueOf(batchId))),
                Arrays.asList(new TypeReference<Bytes32>() {}));
        return callViewFunction(function);
    }

    /**
     * 查询合约 isWithdrawalFinalized(batchId, index)。
     */
    private boolean callIsWithdrawalFinalized(long batchId, int index) throws Exception {
        Function function = new Function(
                "isWithdrawalFinalized",
                Arrays.asList(
                        new Uint256(BigInteger.valueOf(batchId)),
                        new Uint256(BigInteger.valueOf(index))),
                Arrays.asList(new TypeReference<Bool>() {}));
        return decodeBoolResult(callViewFunction(function));
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
        return response.hasError() || "0x".equalsIgnoreCase(response.getValue());
    }

    // ==================== 辅助方法：事件验证 ====================

    /**
     * 在最近区块中查找指定事件。
     */
    private boolean findEventInRecentBlocks(Event event, String indexedTopic1) throws Exception {
        String eventTopic = EventEncoder.encode(event);
        // indexed topic 需要 padding 到 32 字节（64 hex 字符），与链上 log topic 格式一致
        String paddedTopic1 = padTopicTo32Bytes(indexedTopic1);
        logger.debug("查找事件: topic0={}, topic1={} (padded={})", eventTopic, indexedTopic1, paddedTopic1);

        BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
        BigInteger fromBlock = latestBlock.subtract(BigInteger.valueOf(50));
        if (fromBlock.signum() < 0) {
            fromBlock = BigInteger.ZERO;
        }

        org.web3j.protocol.core.methods.request.EthFilter filter =
                new org.web3j.protocol.core.methods.request.EthFilter(
                        org.web3j.protocol.core.DefaultBlockParameter.valueOf(fromBlock),
                        DefaultBlockParameterName.LATEST,
                        contractAddress)
                        .addSingleTopic(eventTopic)
                        .addSingleTopic(paddedTopic1);

        var ethLog = web3j.ethGetLogs(filter).send();
        if (ethLog.hasError()) {
            logger.warn("eth_getLogs 错误: {}", ethLog.getError().getMessage());
            return false;
        }

        var logs = ethLog.getLogs();
        logger.debug("找到 {} 条匹配日志", logs.size());
        return !logs.isEmpty();
    }

    /**
     * 将 hex 字符串 padding 到 32 字节（64 hex 字符 + 0x 前缀）。
     *
     * <p>eth_getLogs 的 topic 是 32 字节定长，batchId 作为 uint256 indexed topic
     * 需要左侧零填充到 64 hex 字符。例如 "0x3e9" → "0x000...03e9"。</p>
     *
     * @param topic hex 字符串（0x 前缀）
     * @return 32 字节 padding 后的 hex 字符串
     */
    private static String padTopicTo32Bytes(String topic) {
        if (topic == null) {
            return null;
        }
        String hex = topic.startsWith("0x") ? topic.substring(2) : topic;
        // 左侧零填充到 64 hex 字符（32 字节）
        while (hex.length() < 64) {
            hex = "0" + hex;
        }
        // 如果超过 64 字符，取最后 64 字符（uint256 溢出截断）
        if (hex.length() > 64) {
            hex = hex.substring(hex.length() - 64);
        }
        return "0x" + hex;
    }

    // ==================== 辅助方法：欺诈证明构造 ====================

    /**
     * 构造欺诈证明数据。
     */
    private byte[] buildFraudProofData(long batchId, String actualRoot, String fraudulentRoot) {
        byte[] actualRootBytes = Numeric.hexStringToByteArray(actualRoot);
        byte[] fraudulentRootBytes = Numeric.hexStringToByteArray(fraudulentRoot);

        byte[] result = new byte[8 + 32 + 32];
        for (int i = 0; i < 8; i++) {
            result[i] = (byte) ((batchId >>> (56 - i * 8)) & 0xFF);
        }
        System.arraycopy(actualRootBytes, 0, result, 8, 32);
        System.arraycopy(fraudulentRootBytes, 0, result, 40, 32);

        return result;
    }

    // ==================== 辅助方法：工具 ====================

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

    // ==================== 内部：Withdrawal ABI Struct ====================

    /**
     * Solidity {@code L2Bridge.Withdrawal} struct 的 web3j 编码适配。
     *
     * <p>struct Withdrawal { address token; address recipient; uint256 amount; }</p>
     */
    public static class WithdrawalAbi extends DynamicStruct {
        public WithdrawalAbi(String token, String recipient, BigInteger amount) {
            super(new Address(token), new Address(recipient), new Uint256(amount));
        }
    }
}
