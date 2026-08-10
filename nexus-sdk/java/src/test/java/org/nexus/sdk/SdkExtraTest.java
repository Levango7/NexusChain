package org.nexus.sdk;

import org.junit.jupiter.api.Test;
import org.nexus.sdk.bridge.BridgeClient;
import org.nexus.sdk.channel.PaymentChannelClient;
import org.nexus.sdk.stablecoin.StableCoinClient;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RpcClient / TransactionBuilder / Wallet / BridgeClient /
 * PaymentChannelClient / StableCoinClient 补充测试。
 *
 * p.覆盖构造器变体、输入校验、不触网方法与网络不可达异常路径。
 */
class SdkExtraTest {

    private final RpcClient rpc = new RpcClient("http://localhost:9999", 500, null);
    private final RpcClient rpcWithKey = new RpcClient("http://localhost:9999", 500, "sk-test");

    // === RpcClient ===

    @Test
    void rpcClient_singleArgConstructor_shouldUseDefaults() {
        RpcClient c = new RpcClient("http://localhost:9999");
        assertNotNull(c);
    }

    @Test
    void rpcClient_batchCall_unreachable_shouldThrowRpcException() {
        RpcClient.RpcRequest[] reqs = {
                new RpcClient.RpcRequest("m1", new Object[]{"a"}, 1)
        };
        assertThrows(RpcClient.RpcException.class, () -> rpc.batchCall(reqs));
    }

    @Test
    void rpcClient_getBlockByHash_unreachable_shouldThrow() {
        assertThrows(RpcClient.RpcException.class, () -> rpc.getBlockByHash("0xhash"));
    }

    @Test
    void rpcClient_getBlockByNumber_unreachable_shouldThrow() {
        assertThrows(RpcClient.RpcException.class, () -> rpc.getBlockByNumber(1L));
    }

    @Test
    void rpcClient_close_shouldNotThrow() {
        assertDoesNotThrow(() -> rpc.close());
    }

    @Test
    void rpcClient_rpcRequest_getters_shouldReturnFields() {
        Object[] params = {"a", 1};
        RpcClient.RpcRequest req = new RpcClient.RpcRequest("method", params, 42L);

        assertEquals("method", req.getMethod());
        assertEquals(params, req.getParams());
        assertEquals(42L, req.getId());
    }

    @Test
    void rpcClient_rpcException_messageOnlyConstructor() {
        RpcClient.RpcException ex = new RpcClient.RpcException("test message");
        assertEquals("test message", ex.getMessage());
    }

    @Test
    void rpcClient_rpcException_withCause() {
        Throwable cause = new RuntimeException("cause");
        RpcClient.RpcException ex = new RpcClient.RpcException("msg", cause);
        assertEquals("msg", ex.getMessage());
        assertEquals(A(cause), ex.getCause());
    }

    private static Throwable A(Throwable t) { return t; }

    // === TransactionBuilder ===

    @Test
    void transactionBuilder_buildTransfer_nullFrom_shouldThrow() {
        TransactionBuilder b = new TransactionBuilder(rpc, "testnet");
        assertThrows(IllegalArgumentException.class, () ->
                b.buildTransfer(null, "to", BigInteger.ONE, "NEX"));
    }

    @Test
    void transactionBuilder_buildTransfer_nullTo_shouldThrow() {
        TransactionBuilder b = new TransactionBuilder(rpc, "testnet");
        assertThrows(IllegalArgumentException.class, () ->
                b.buildTransfer("from", null, BigInteger.ONE, "NEX"));
    }

    @Test
    void transactionBuilder_buildContractCall_shouldSetFields() {
        TransactionBuilder b = new TransactionBuilder(rpc, "testnet");
        TransactionBuilder.Transaction tx = b.buildContractCall(
                "from", "0xcontract", "0xdata", BigInteger.valueOf(100));

        assertEquals("from", tx.getFrom());
        assertEquals("0xcontract", tx.getTo());
        assertEquals("0xdata", tx.getData());
        assertEquals(BigInteger.valueOf(100), tx.getValue());
    }

    @Test
    void transactionBuilder_broadcast_unreachable_shouldThrow() {
        TransactionBuilder b = new TransactionBuilder(rpc, "testnet");
        assertThrows(RpcClient.RpcException.class, () -> b.broadcast("signedtx"));
    }

    @Test
    void transactionBuilder_getTransactionReceipt_unreachable_shouldThrow() {
        TransactionBuilder b = new TransactionBuilder(rpc, "testnet");
        assertThrows(RpcClient.RpcException.class, () -> b.getTransactionReceipt("0xhash"));
    }

    // === Wallet ===

    @Test
    void wallet_fromPrivateKey_invalidKey_shouldThrow() {
        Wallet w = new Wallet(rpc, "testnet");
        assertThrows(IllegalArgumentException.class, () -> w.fromPrivateKey("invalid"));
    }

    @Test
    void wallet_fromPrivateKey_validKey_shouldReturnWalletInfo() {
        Wallet w = new Wallet(rpc, "testnet");
        String validPrikey = "0000000000000000000000000000000000000000000000000000000000000001";
        Wallet.WalletInfo info = w.fromPrivateKey(validPrikey);

        assertNotNull(info);
        assertEquals(validPrikey, info.getPrivateKey());
    }

    @Test
    void wallet_getTokenBalance_unreachable_shouldThrow() {
        Wallet w = new Wallet(rpc, "testnet");
        assertThrows(RpcClient.RpcException.class, () ->
                w.getTokenBalance("addr", "token"));
    }

    @Test
    void wallet_getNetwork_shouldReturnConfigured() {
        Wallet w = new Wallet(rpc, "mainnet");
        assertEquals("mainnet", w.getNetwork());
    }

    @Test
    void wallet_validateAddress_emptyString_shouldReturnFalse() {
        Wallet w = new Wallet(rpc, "testnet");
        assertEquals(false, w.validateAddress(""));
    }

    // === BridgeClient ===

    @Test
    void bridgeClient_customContractConstructor_shouldWork() {
        BridgeClient c = new BridgeClient(rpc, "0xcustom");
        assertNotNull(c);
    }

    @Test
    void bridgeClient_fullConstructor_shouldWork() {
        TransactionBuilder tb = new TransactionBuilder(rpc, "mainnet");
        BridgeClient c = new BridgeClient(rpc, "0xcustom", tb);
        assertNotNull(c);
    }

    @Test
    void bridgeClient_fullConstructor_nullContract_shouldUseDefault() {
        TransactionBuilder tb = new TransactionBuilder(rpc, "mainnet");
        BridgeClient c = new BridgeClient(rpc, null, tb);
        assertNotNull(c);
    }

    @Test
    void bridgeClient_getBridgeStatus_unreachable_shouldThrow() {
        BridgeClient c = new BridgeClient(rpc);
        assertThrows(RpcClient.RpcException.class, () -> c.getBridgeStatus("0xtx"));
    }

    @Test
    void bridgeClient_getBridgeStatus_nullHash_shouldThrow() {
        BridgeClient c = new BridgeClient(rpc);
        assertThrows(IllegalArgumentException.class, () -> c.getBridgeStatus(null));
    }

    @Test
    void bridgeClient_lock_nullToken_shouldThrow() {
        BridgeClient c = new BridgeClient(rpc);
        assertThrows(IllegalArgumentException.class, () ->
                c.lock("from", null, BigInteger.ONE, "ethereum", "0xtarget"));
    }

    @Test
    void bridgeClient_lock_nullTargetAddress_shouldThrow() {
        BridgeClient c = new BridgeClient(rpc);
        assertThrows(IllegalArgumentException.class, () ->
                c.lock("from", "NEX", BigInteger.ONE, "ethereum", null));
    }

    @Test
    void bridgeClient_unlock_nullToken_shouldThrow() {
        BridgeClient c = new BridgeClient(rpc);
        assertThrows(IllegalArgumentException.class, () ->
                c.unlock("to", null, BigInteger.ONE, "ethereum", "proof"));
    }

    @Test
    void bridgeClient_unlockC_getBridgeFee_nullToken_shouldThrow() {
        BridgeClient c = new BridgeClient(rpc);
        assertThrows(IllegalArgumentException.class, () -> c.getBridgeFee(null, "ethereum"));
    }

    @Test
    void bridgeClient_getBridgeFee_nullChain_shouldThrow() {
        BridgeClient c = new BridgeClient(rpc);
        assertThrows(IllegalArgumentException.class, () -> c.getBridgeFee("NEX", null));
    }

    // === PaymentChannelClient ===

    @Test
    void paymentChannelClient_customContractConstructor_shouldWork() {
        PaymentChannelClient c = new PaymentChannelClient(rpc, "0xcustom");
        assertNotNull(c);
    }

    @Test
    void paymentChannelClient_nullContract_shouldUseDefault() {
        PaymentChannelClient c = new PaymentChannelClient(rpc, null);
        assertNotNull(c);
    }

    @Test
    void paymentChannelClient_openChannel_nullRecipient_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(IllegalArgumentException.class, () ->
                c.openChannel("sender", null, BigInteger.ONE));
    }

    @Test
    void paymentChannelClient_openChannel_negativeDeposit_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(IllegalArgumentException.class, () ->
                c.openChannel("sender", "recipient", BigInteger.valueOf(-1)));
    }

    @Test
    void paymentChannelClient_closeChannel_emptyId_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(IllegalArgumentException.class, () -> c.closeChannel(""));
    }

    @Test
    void paymentChannelClient_updateChannelState_nullProof_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(IllegalArgumentException.class, () ->
                c.updateChannelState("ch-1", null));
    }

    @Test
    void paymentChannelClient_updateChannelState_negativeBalance_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        PaymentChannelClient.BalanceProof proof = new PaymentChannelClient.BalanceProof(
                "ch-1", BigInteger.valueOf(-1), 1, "sig");
        assertThrows(IllegalArgumentException.class, () ->
                c.updateChannelState("ch-1", proof));
    }

    @Test
    void paymentChannelClient_updateChannelState_nullBalance_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        PaymentChannelClient.BalanceProof proof = new PaymentChannelClient.BalanceProof(
                "ch-1", null, 1, "sig");
        assertThrows(IllegalArgumentException.class, () ->
                c.updateChannelState("ch-1", proof));
    }

    @Test
    void paymentChannelClient_updateChannelState_emptySignature_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        PaymentChannelClient.BalanceProof proof = new PaymentChannelClient.BalanceProof(
                "ch-1", BigInteger.ZERO, 1, "");
        assertThrows(IllegalArgumentException.class, () ->
                c.updateChannelState("ch-1", proof));
    }

    @Test
    void paymentChannelClient_getChannelInfo_emptyId_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(IllegalArgumentException.class, () -> c.getChannelInfo(""));
    }

    @Test
    void paymentChannelClient_getChannelInfo_unreachable_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(RpcClient.RpcException.class, () -> c.getChannelInfo("ch-1"));
    }

    @Test
    void paymentChannelClient_challengeChannel_emptyId_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(IllegalArgumentException.class, () -> c.challengeChannel(""));
    }

    @Test
    void paymentChannelClient_challengeChannel_unreachable_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(RpcClient.RpcException.class, () -> c.challengeChannel("ch-1"));
    }

    @Test
    void paymentChannelClient_openChannel_unreachable_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(RpcClient.RpcException.class, () ->
                c.openChannel("sender", "recipient", BigInteger.ONE));
    }

    @Test
    void paymentChannelClient_closeChannel_unreachable_shouldThrow() {
        PaymentChannelClient c = new PaymentChannelClient(rpc);
        assertThrows(RpcClient.RpcException.class, () -> c.closeChannel("ch-1"));
    }

    // === StableCoinClient ===

    @Test
    void stableCoinClient_customContractConstructor_shouldWork() {
        StableCoinClient c = new StableCoinClient(rpc, "0xcustom");
        assertNotNull(c);
    }

    @Test
    void stableCoinClient_nullContract_shouldUseDefault() {
        StableCoinClient c = new StableCoinClient(rpc, null);
        assertNotNull(c);
    }

    @Test
    void stableCoinClient_mint_nullMinter_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(IllegalArgumentException.class, () ->
                c.mint(null, BigInteger.ONE, BigInteger.ONE));
    }

    @Test
    void stableCoinClient_mint_negativeCollateral_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(IllegalArgumentException.class, () ->
                c.mint("minter", BigInteger.ONE, BigInteger.valueOf(-1)));
    }

    @Test
    void stableCoinClient_burn_emptyBurner_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(IllegalArgumentException.class, () -> c.burn("", BigInteger.ONE));
    }

    @Test
    void stableCoinClient_transfer_nullTo_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(IllegalArgumentException.class, () ->
                c.transfer("from", null, BigInteger.ONE));
    }

    @Test
    void stableCoinClient_getCollateralRatio_emptyAddress_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(IllegalArgumentException.class, () -> c.getCollateralRatio(""));
    }

    @Test
    void stableCoinClient_getCollateralRatio_unreachable_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(RpcClient.RpcException.class, () -> c.getCollateralRatio("addr"));
    }

    @Test
    void stableCoinClient_getPrice_unreachable_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(RpcClient.RpcException.class, c::getPrice);
    }

    @Test
    void stableCoinClient_getTotalSupply_unreachable_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(RpcClient.RpcException.class, c::getTotalSupply);
    }

    @Test
    void stableCoinClient_mint_unreachable_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(RpcClient.RpcException.class, () ->
                c.mint("minter", BigInteger.ONE, BigInteger.ONE));
    }

    @Test
    void stableCoinClient_burn_unreachable_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(RpcClient.RpcException.class, () -> c.burn("burner", BigInteger.ONE));
    }

    @Test
    void stableCoinClient_transfer_unreachable_shouldThrow() {
        StableCoinClient c = new StableCoinClient(rpc);
        assertThrows(RpcClient.RpcException.class, () ->
                c.transfer("from", "to", BigInteger.ONE));
    }
}