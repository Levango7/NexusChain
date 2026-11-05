/*
 * 共识回归测试（P0 #522 修复配套）。
 *
 * 历史对照——同一个区块构造、同一套断言口径：
 *
 * | 版本 | 含 0x04–0x08 交易的区块 | 发送方余额 / nonce | coinbase 入账 |
 * | --- | --- | --- | --- |
 * | 修复前（master ≤ 2026-11-06，见 PR #520 复现测试） | 通过完整规则链与状态根校验 | 不变（手续费从未扣款） | 增加 gasPrice × GAS_TABLE[type] |
 * | 修复后（本 PR） | 在 CoinbaseRule 手续费守恒 / TransactionCheck 类型门禁 / MerkleRule 重放 default 处被拒 | 不变 | 不入账 |
 *
 * PR #520 原本断言"必须成功且余额不变"——那是一条绿着的增发证明。本文件把它反转为
 * "必须被拒"，历史证据保留在本注释与 CHANGELOG 中，master 上不再存在断言增发成功的测试。
 *
 * 被拒的三道独立关卡：
 * 1. {@code TransactionCheck#TransactionFormatCheck} 类型白名单（0x00–0x03 / 0x09–0x0f）；
 * 2. {@code CoinbaseRule} 手续费守恒断言（计入 coinbase 的手续费必须真的扣过款）；
 * 3. {@code MerkleRule} 状态重放 switch 的 {@code default} 显式拒绝（不再静默 no-op）。
 */
package org.nexus.core.validate;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.command.Configuration;
import org.nexus.command.IncubatorAddress;
import org.nexus.command.TransactionCheck;
import org.nexus.consensus.pow.EconomicModel;
import org.nexus.consensus.pow.PackageMiner;
import org.nexus.consensus.pow.Proposer;
import org.nexus.consensus.pow.ProposersFactory;
import org.nexus.consensus.pow.TargetState;
import org.nexus.core.Block;
import org.nexus.core.WhitelistTransaction;
import org.nexus.core.account.Account;
import org.nexus.core.account.AccountDB;
import org.nexus.core.account.Transaction;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.db.AccountState;
import org.nexus.db.StateDB;
import org.nexus.db.TargetStateFactory;
import org.nexus.encoding.BigEndian;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.pool.PeningTransPool;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MultiSigAssetAtomicMintRejectionTest {

    /** 被 mock 的共识奖励（最小单位）。 */
    private static final long REWARD = EconomicModel.INITIAL_SUPPLY;
    /** 探针交易的 gasPrice；fee = gasPrice * GAS_TABLE[type]。 */
    private static final long GAS_PRICE = 1000L;

    private static final BigInteger MAX_TARGET = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    // ---------------------------------------------------------------- 1. 完整规则链逐个拒绝

    @Test
    void type0x04_multisigMultisig_rejected() {
        assertRejectedByCompositeAndReplay(4);
    }

    @Test
    void type0x05_multisigNormal_rejected() {
        assertRejectedByCompositeAndReplay(5);
    }

    @Test
    void type0x06_normalMultisig_rejected() {
        assertRejectedByCompositeAndReplay(6);
    }

    @Test
    void type0x07_assetDefine_rejected() {
        assertRejectedByCompositeAndReplay(7);
    }

    @Test
    void type0x08_atomicExchange_rejected() {
        assertRejectedByCompositeAndReplay(8);
    }

    /**
     * 构造 [coinbase(奖励+手续费), tx(type)] 区块，要求完整规则链与状态重放都拒绝。
     *
     * <p>修复前这两处都放行：composite 返回 SUCCESS，MerkleRule 的 replay switch
     * 对 0x04–0x08 没有 case 也没有 default，静默 no-op 后状态根校验通过。</p>
     */
    private void assertRejectedByCompositeAndReplay(int type) {
        assertEquals(50000L, Transaction.GAS_TABLE[type],
                "前提：GAS_TABLE[" + type + "] 必须是 50000");
        long fee = GAS_PRICE * Transaction.GAS_TABLE[type];
        String label = "type 0x0" + type;

        Fixture fx = fixture(type, fee);

        Result compositeResult = fx.composite.validateBlock(fx.block);
        assertFalse(compositeResult.isSuccess(),
                label + ": 修复后必须被完整规则链拒绝，实际通过");
        System.out.println("[REJECTED composite] " + label + " -> " + compositeResult.getMessage());

        Result merkleResult = fx.merkleRule.validateBlock(fx.block);
        assertFalse(merkleResult.isSuccess(),
                label + ": 修复后必须被 MerkleRule 状态重放拒绝，实际通过");
        System.out.println("[REJECTED merkle] " + label + " -> " + merkleResult.getMessage());

        // 被拒 = 没有任何状态变更；这是"修复前/修复后都不动发送方余额"的对照点。
        assertEquals(fx.senderBalanceBefore, fx.senderAccount.getBalance(),
                label + ": 发送方余额必须不变");
        assertEquals(fx.senderNonceBefore, fx.senderAccount.getNonce(),
                label + ": 发送方 nonce 必须不变");
    }

    // ---------------------------------------------------------------- 2. 类型门禁消息

    @Test
    void formatGateRejects0x04() {
        assertFormatGateRejects(4);
    }

    @Test
    void formatGateRejects0x05() {
        assertFormatGateRejects(5);
    }

    @Test
    void formatGateRejects0x06() {
        assertFormatGateRejects(6);
    }

    @Test
    void formatGateRejects0x07() {
        assertFormatGateRejects(7);
    }

    @Test
    void formatGateRejects0x08() {
        assertFormatGateRejects(8);
    }

    /**
     * 直接打 {@link TransactionCheck#TransactionFormatCheck}：白名单之外的交易必须
     * 返回 5000，且消息点名"该类型未启用"（含十六进制类型号）。
     *
     * <p>这是变异验证的精确锚点：把白名单改回 {@code 15 >= type >= 0} 时，这 5 个
     * 用例必须变红。</p>
     */
    private void assertFormatGateRejects(int type) {
        TransactionCheck transactionCheck = new TransactionCheck();
        Configuration configuration = mock(Configuration.class);
        when(configuration.getMin_procedurefee()).thenReturn(1L);
        ReflectionTestUtils.setField(transactionCheck, "configuration", configuration);

        Transaction tx = signedTx(type, Ed25519.generateKeyPair(), new byte[Transaction.PUBLIC_KEY_HASH_SIZE], 0L);
        APIResult result = transactionCheck.TransactionFormatCheck(tx.toRPCBytes());

        assertEquals(5000, result.getCode(), "type 0x0" + type + " 必须被类型门禁拒绝");
        assertTrue(result.getMessage().contains("not enabled"),
                "拒绝消息必须点名该类型未启用，实际: " + result.getMessage());
        assertTrue(result.getMessage().contains("0x0" + type),
                "拒绝消息必须包含类型号，实际: " + result.getMessage());
    }

    // ---------------------------------------------------------------- 3. MerkleRule 未知类型

    /**
     * 修复前：replay switch 无 default，0x04 交易被静默跳过，状态根校验照常通过。
     * 修复后：必须返回错误而不是 no-op。
     */
    @Test
    void merkleReplayRejectsUnknownTypeInsteadOfNoOp() {
        Ed25519KeyPair senderKp = Ed25519.generateKeyPair();
        byte[] senderPub = senderKp.getPublicKey().getEncoded();
        byte[] senderHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(senderPub));
        byte[] minerHash = RipemdUtility.ripemd160(
                SHA3Utility.keccak256(Ed25519.generateKeyPair().getPublicKey().getEncoded()));

        Account totalAccount = new Account(0, IncubatorAddress.resultpubhash(), 0, 0, 0, 0, 0);
        Account senderAccount = new Account(0, senderHash, 7L, 1000L * EconomicModel.NEX, 0, 0, 0);
        Account minerAccount = new Account(0, minerHash, 0, 0, 0, 0, 0);

        Transaction unknown = signedTx(0x04, senderKp, minerHash, 0L);
        List<Transaction> body = new ArrayList<>();
        body.add(unknown);

        Block block = blockWith(body, 1L);
        block.hashMerkleRoot = Block.calculateMerkleRoot(body);

        MerkleRule merkleRule = merkleRule(totalAccount, senderAccount, minerAccount);
        Result result = merkleRule.validateBlock(block);

        assertFalse(result.isSuccess(), "MerkleRule 必须拒绝未知类型，实际通过（静默 no-op 回归）");
        assertTrue(result.getMessage().contains("state replay"),
                "错误必须来自状态重放的显式拒绝，实际: " + result.getMessage());
        assertTrue(result.getMessage().contains("type not enabled"),
                "错误必须点名类型未启用，实际: " + result.getMessage());
    }

    // ---------------------------------------------------------------- 4. CoinbaseRule 守恒

    /**
     * 修复前：CoinbaseRule 只累加 body[1..] 的 getFee()，从不确认这笔手续费真的从
     * 发送方扣过款——0x04–0x08 没有扣款分支，coinbase 却照常入账。
     * 修复后：手续费守恒断言必须在"无扣款类型"上拒绝。
     */
    @Test
    void coinbaseRejectsFeeThatIsNotDebited() {
        long fee = GAS_PRICE * Transaction.GAS_TABLE[4];
        Fixture fx = fixture(4, fee);

        CoinbaseRule coinbaseRule = coinbaseRule(fx.stateDB, REWARD);
        Result result = coinbaseRule.validateBlock(fx.block);

        assertFalse(result.isSuccess(), "coinbase 认领了未扣款的手续费，必须被拒");
        assertTrue(result.getMessage().contains("fee conservation violated"),
                "错误必须点名手续费守恒，实际: " + result.getMessage());
    }

    /**
     * 对照：白名单内的类型（0x03 存证）确实扣手续费，coinbase 认领等额手续费应当通过，
     * 证明守恒断言没有误伤既有合法区块。
     */
    @Test
    void coinbaseAcceptsEnabledTypeWithMatchingFee() {
        int type = Transaction.Type.DEPOSIT.ordinal();
        long fee = GAS_PRICE * Transaction.GAS_TABLE[type];
        Fixture fx = fixture(type, fee);

        CoinbaseRule coinbaseRule = coinbaseRule(fx.stateDB, REWARD);
        Result result = coinbaseRule.validateBlock(fx.block);

        assertTrue(result.isSuccess(),
                "0x03 会真实扣手续费，coinbase 等额认领不应被拒，实际: " + result.getMessage());
    }

    // ---------------------------------------------------------------- 5. 佐证（不改变判定）

    /**
     * 佐证"不可检测"：状态根只承诺账户 ID + 高度，不承诺余额，所以余额增发在状态根
     * 层面不可见。这也是本次必须在类型门禁 / 重放 / coinbase 三处收紧的原因。
     */
    @Test
    void stateRootDoesNotCommitBalances() {
        Ed25519KeyPair minerKp = Ed25519.generateKeyPair();
        byte[] minerHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(minerKp.getPublicKey().getEncoded()));

        String rootZero = Hex.encodeHexString(
                Block.calculateMerkleState(List.of(new Account(1L, minerHash, 0, 0, 0, 0, 0))));
        String rootFunded = Hex.encodeHexString(
                Block.calculateMerkleState(List.of(new Account(1L, minerHash, 0, REWARD, 0, 0, 0))));

        assertEquals(rootZero, rootFunded, "状态根不承诺余额，余额变化在根上不可见");
    }

    /**
     * 佐证 APIResult 的 code 语义：{@code ResultSupport.Code} 是未初始化的 int（0），
     * 成功码是 2000、失败码是 5000。{@code PayloadCheck} 缺 {@code default} 时返回 0，
     * 既不是"成功"也不是"失败"——只是"未设置"。
     *
     * <p>本 PR 不改 {@code AccountRule} 的 {@code == 5000} 判定：上游类型白名单已让
     * 该路径不可达，把 0 当失败属于额外的共识改动，收益不足以抵消风险。</p>
     */
    @Test
    void payloadCheckDefaultCodeIsUnsetNeitherSuccessNorFailure() {
        Ed25519KeyPair senderKp = Ed25519.generateKeyPair();
        byte[] senderPub = senderKp.getPublicKey().getEncoded();
        byte[] senderHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(senderPub));

        Transaction tx = signedTx(4, senderKp, senderHash, 0L);

        TransactionCheck transactionCheck = new TransactionCheck();
        Configuration configuration = mock(Configuration.class);
        when(configuration.getMin_procedurefee()).thenReturn(1L);
        ReflectionTestUtils.setField(transactionCheck, "configuration", configuration);

        Account account = new Account(0, senderHash, 0L, 1000L, 0, 0, 0);
        APIResult result = transactionCheck.TransactionVerify(tx, account, null);

        assertEquals(0, result.getCode(), "PayloadCheck 无 default 时必须落到 ResultSupport 的默认 code 0");
        assertNotEquals(APIResult.SUCCESS, result.getCode(), "0 不是成功码（成功码是 2000）");
        assertNotEquals(APIResult.FAIL, result.getCode(), "0 也不是失败码（失败码是 5000）");
    }

    // ---------------------------------------------------------------- fixtures / helpers

    /** 一次区块构造所需的全部上下文。 */
    private static final class Fixture {
        Block block;
        StateDB stateDB;
        CompositeBlockRule composite;
        MerkleRule merkleRule;
        Account senderAccount;
        long senderBalanceBefore;
        long senderNonceBefore;
    }

    private Fixture fixture(int type, long fee) {
        Ed25519KeyPair senderKp = Ed25519.generateKeyPair();
        Ed25519KeyPair minerKp = Ed25519.generateKeyPair();
        byte[] senderPub = senderKp.getPublicKey().getEncoded();
        byte[] minerHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(minerKp.getPublicKey().getEncoded()));
        byte[] senderHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(senderPub));

        long senderBalanceBefore = 1000L * EconomicModel.NEX;
        long senderNonceBefore = 7L;
        Account senderAccount = new Account(0, senderHash, senderNonceBefore, senderBalanceBefore, 0, 0, 0);
        Account minerAccount = new Account(0, minerHash, 0, 0, 0, 0, 0);
        Account totalAccount = new Account(0, IncubatorAddress.resultpubhash(), 0, 0, 0, 0, 0);

        Transaction tx = signedTx(type, senderKp, minerHash, 0L);
        Transaction coinbase = new Transaction(
                1, Transaction.Type.COINBASE.ordinal(), 1L,
                new byte[Transaction.PUBLIC_KEY_SIZE], 0L,
                REWARD + fee, new byte[0], minerHash, new byte[Transaction.SIGNATURE_SIZE]);

        List<Transaction> body = new ArrayList<>();
        body.add(coinbase);
        body.add(tx);

        Block block = blockWith(body, 1L);
        block.hashMerkleRoot = Block.calculateMerkleRoot(body);
        // 声明修复前会形成的后状态根（miner = 奖励+手续费）。修复后被拒，根不再重要，
        // 但保留它以便证明"状态根层面的校验本身是放行的"。
        block.hashMerkleState = Block.calculateMerkleState(
                List.of(new Account(1L, minerHash, 0, REWARD + fee, 0, 0, 0)));

        StateDB stateDB = stateDB(senderHash, senderAccount, minerHash, minerAccount);

        Fixture fx = new Fixture();
        fx.block = block;
        fx.stateDB = stateDB;
        fx.composite = compositeBlockRule(stateDB);
        fx.merkleRule = merkleRule(totalAccount, senderAccount, minerAccount);
        fx.senderAccount = senderAccount;
        fx.senderBalanceBefore = senderBalanceBefore;
        fx.senderNonceBefore = senderNonceBefore;
        return fx;
    }

    private static StateDB stateDB(byte[] senderHash, Account senderAccount,
                                   byte[] minerHash, Account minerAccount) {
        StateDB stateDB = mock(StateDB.class);
        when(stateDB.getBestBlock()).thenReturn(emptyBlock(0L));
        when(stateDB.getBlock(any(byte[].class))).thenReturn(emptyBlock(0L));

        TargetStateFactory targetStateFactory = mock(TargetStateFactory.class);
        TargetState targetState = mock(TargetState.class);
        when(targetState.getTarget()).thenReturn(MAX_TARGET);
        when(targetStateFactory.getInstance(any(Block.class))).thenReturn(targetState);
        when(stateDB.getTargetStateFactory()).thenReturn(targetStateFactory);

        ProposersFactory proposersFactory = mock(ProposersFactory.class);
        when(proposersFactory.getProposer(any(Block.class), anyLong()))
                .thenReturn(Optional.of(new Proposer(Hex.encodeHexString(minerHash), 0L, Long.MAX_VALUE)));
        when(stateDB.getProposersFactory()).thenReturn(proposersFactory);

        Map<String, AccountState> accounts = new HashMap<>();
        accounts.put(Hex.encodeHexString(senderHash),
                new AccountState(senderAccount, new HashMap<>(), new HashMap<>()));
        accounts.put(Hex.encodeHexString(minerHash),
                new AccountState(minerAccount, new HashMap<>(), new HashMap<>()));
        when(stateDB.getAccounts(any(byte[].class), any(List.class))).thenReturn(accounts);
        return stateDB;
    }

    private static Transaction signedTx(int type, Ed25519KeyPair senderKp, byte[] to, long amount) {
        byte[] senderPub = senderKp.getPublicKey().getEncoded();
        Transaction tx = new Transaction(
                1, type, 8L, senderPub,
                GAS_PRICE, amount,
                "probe".getBytes(StandardCharsets.UTF_8),
                to, new byte[Transaction.SIGNATURE_SIZE]);
        byte[] signature = new Ed25519PrivateKey(senderKp.getPrivateKey().getEncoded())
                .sign(tx.getRawForSign());
        ReflectionTestUtils.setField(tx, "signature", signature);
        return tx;
    }

    private static CoinbaseRule coinbaseRule(StateDB stateDB, long reward) {
        EconomicModel economicModel = mock(EconomicModel.class);
        when(economicModel.getConsensusRewardAtHeight(anyLong())).thenReturn(reward);
        CoinbaseRule coinbaseRule = new CoinbaseRule();
        ReflectionTestUtils.setField(coinbaseRule, "stateDB", stateDB);
        ReflectionTestUtils.setField(coinbaseRule, "economicModel", economicModel);
        return coinbaseRule;
    }

    private static CompositeBlockRule compositeBlockRule(StateDB stateDB) {
        Block genesis = emptyBlock(0L);
        genesis.nVersion = 1L;

        BasicRule basicRule = new BasicRule(genesis, "test");
        ReflectionTestUtils.setField(basicRule, "stateDB", stateDB);
        ReflectionTestUtils.setField(basicRule, "orphanHeightsRange", 100);
        ReflectionTestUtils.setField(basicRule, "blockInterval", 60);
        ReflectionTestUtils.setField(basicRule, "consensusMode", "dpos");

        EconomicModel economicModel = mock(EconomicModel.class);
        when(economicModel.getConsensusRewardAtHeight(1L)).thenReturn(REWARD);
        CoinbaseRule coinbaseRule = new CoinbaseRule();
        ReflectionTestUtils.setField(coinbaseRule, "stateDB", stateDB);
        ReflectionTestUtils.setField(coinbaseRule, "economicModel", economicModel);

        ConsensusRule consensusRule = new ConsensusRule(stateDB);

        AccountRule accountRule = new AccountRule("test");
        TransactionCheck transactionCheck = new TransactionCheck();
        Configuration configuration = mock(Configuration.class);
        when(configuration.getMin_procedurefee()).thenReturn(1L);
        ReflectionTestUtils.setField(transactionCheck, "configuration", configuration);
        WhitelistTransaction whitelist = mock(WhitelistTransaction.class);
        when(whitelist.IsUnchecked(anyString())).thenReturn(false);
        PeningTransPool pool = mock(PeningTransPool.class);
        ReflectionTestUtils.setField(accountRule, "stateDB", stateDB);
        ReflectionTestUtils.setField(accountRule, "transactionCheck", transactionCheck);
        ReflectionTestUtils.setField(accountRule, "whitelistTransaction", whitelist);
        ReflectionTestUtils.setField(accountRule, "peningTransPool", pool);
        ReflectionTestUtils.setField(accountRule, "packageMiner", new PackageMiner());

        CompositeBlockRule composite = new CompositeBlockRule();
        composite.addRule(basicRule);
        composite.addRule(new AddressRule());
        composite.addRule(coinbaseRule);
        composite.addRule(consensusRule);
        composite.addRule(new SignatureRule());
        composite.addRule(accountRule);
        // 与 Spring 装配一致的支付扩展交易规则集。
        composite.addTransactionRule(new PaymentChannelRule());
        composite.addTransactionRule(new BatchTransferRule());
        composite.addTransactionRule(new StableCoinRule());
        composite.addTransactionRule(new BridgeRule());
        return composite;
    }

    private static MerkleRule merkleRule(Account totalAccount, Account senderAccount, Account minerAccount) {
        AccountDB accountDB = mock(AccountDB.class);
        when(accountDB.selectaccount(any(byte[].class))).thenAnswer(invocation -> {
            byte[] hash = invocation.getArgument(0);
            if (Arrays.equals(hash, IncubatorAddress.resultpubhash())) {
                return totalAccount;
            }
            if (Arrays.equals(hash, senderAccount.getPubkeyHash())) {
                return senderAccount;
            }
            if (Arrays.equals(hash, minerAccount.getPubkeyHash())) {
                return minerAccount;
            }
            return null;
        });
        MerkleRule merkleRule = new MerkleRule("exchange");
        ReflectionTestUtils.setField(merkleRule, "accountDB", accountDB);
        return merkleRule;
    }

    private static Block blockWith(List<Transaction> body, long height) {
        Block block = emptyBlock(height);
        block.nVersion = 1L;
        block.nTime = System.currentTimeMillis() / 1000L;
        block.nBits = BigEndian.encodeUint256(MAX_TARGET);
        block.body = new ArrayList<>(body);
        return block;
    }

    private static Block emptyBlock(long height) {
        Block block = new Block();
        block.nVersion = 1L;
        block.nHeight = height;
        block.nTime = System.currentTimeMillis() / 1000L;
        block.hashPrevBlock = new byte[Block.HASH_SIZE];
        block.hashMerkleRoot = new byte[Block.HASH_SIZE];
        block.hashMerkleState = new byte[Block.HASH_SIZE];
        block.hashMerkleIncubate = new byte[Block.HASH_SIZE];
        block.nBits = BigEndian.encodeUint256(MAX_TARGET);
        block.nNonce = new byte[Block.HASH_SIZE];
        block.body = new ArrayList<>();
        return block;
    }
}
