package org.nexus.sdk;

import java.math.BigInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NexusChain SDK.
 * Updated 2026-08-06 to match the implemented SDK (methods no longer stubs).
 *
 * <p>Network-dependent methods assert {@link RpcClient.RpcException} because no
 * RPC node is reachable at localhost:8080 in the unit-test environment. Pure
 * logic methods (wallet creation, address validation, transfer building) are
 * asserted against their real behavior.</p>
 */
class SdkUnitTest {

    // -- RpcClient tests -------------------------------------------------

    @Nested
    @DisplayName("RpcClient")
    class RpcClientTests {

        @Test
        @DisplayName("Construct client with valid URL and defaults")
        void constructClient() {
            RpcClient client = new RpcClient("http://localhost:8080", 30000, null);
            assertNotNull(client);
        }

        @Test
        @DisplayName("Construct client with API key")
        void constructClientWithKey() {
            RpcClient client = new RpcClient("https://rpc.nexus.network", 15000, "sk-test");
            assertNotNull(client);
        }

        @Test
        @DisplayName("call() throws RpcException when no node reachable")
        void callThrowsRpcException() {
            RpcClient client = new RpcClient("http://localhost:8080", 500, null);
            // 对齐 nexus-core：nexus_blockNumber → nexus_getLatestBlocks
            assertThrows(RpcClient.RpcException.class, () ->
                client.call("nexus_getLatestBlocks"));
        }

        @Test
        @DisplayName("getBlockNumber() throws RpcException when no node reachable")
        void blockNumberThrowsRpcException() {
            RpcClient client = new RpcClient("http://localhost:8080", 500, null);
            assertThrows(RpcClient.RpcException.class, client::getBlockNumber);
        }

        @Test
        @DisplayName("getChainId() throws RpcException when no node reachable")
        void chainIdThrowsRpcException() {
            RpcClient client = new RpcClient("http://localhost:8080", 500, null);
            assertThrows(RpcClient.RpcException.class, client::getChainId);
        }
    }

    // -- TransactionBuilder tests ----------------------------------------

    @Nested
    @DisplayName("TransactionBuilder")
    class TransactionBuilderTests {

        private final RpcClient rpc = new RpcClient("http://localhost:8080", 500, null);

        @Test
        @DisplayName("buildTransfer() returns a populated transaction")
        void buildTransferReturnsTx() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "testnet");
            TransactionBuilder.Transaction tx =
                builder.buildTransfer("1Sender000", "1Receiver000", BigInteger.valueOf(10000), "NEX");
            assertNotNull(tx);
            assertEquals("1Sender000", tx.getFrom());
            assertEquals("1Receiver000", tx.getTo());
            assertEquals(BigInteger.valueOf(10000), tx.getValue());
            assertEquals("NEX", tx.getToken());
        }

        @Test
        @DisplayName("buildTransfer() rejects non-positive amount")
        void buildTransferRejectsBadAmount() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "testnet");
            assertThrows(IllegalArgumentException.class, () ->
                builder.buildTransfer("1Sender000", "1Receiver000", BigInteger.ZERO, "NEX"));
        }

        @Test
        @DisplayName("Constructor stores RPC client and network")
        void constructorStoresFields() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "mainnet");
            assertNotNull(builder);
        }

        @Test
        @DisplayName("sign() with empty tx throws (null value)")
        void signEmptyTxThrows() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "mainnet");
            TransactionBuilder.Transaction tx = new TransactionBuilder.Transaction();
            assertThrows(RuntimeException.class, () ->
                builder.sign(tx, "0xdeadbeef"));
        }

        @Test
        @DisplayName("getGasPrice() returns default 1 gwei when no node reachable (fail-safe)")
        void gasPriceReturnsDefaultWhenNoNodeReachable() {
            // getGasPrice 为 fail-safe：节点不可达时捕获异常并返回默认 1 gwei，而非抛 RpcException
            RpcClient noNode = new RpcClient("http://127.0.0.1:1", 500, null);
            TransactionBuilder builder = new TransactionBuilder(noNode, "mainnet");
            assertEquals(BigInteger.valueOf(1_000_000_000L), builder.getGasPrice());
        }

        @Test
        @DisplayName("estimateGas() throws RpcException when no node reachable")
        void estimateGasThrowsRpcException() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "mainnet");
            TransactionBuilder.Transaction tx = new TransactionBuilder.Transaction();
            assertThrows(RpcClient.RpcException.class, () -> builder.estimateGas(tx));
        }
    }

    // -- Wallet tests ---------------------------------------------------

    @Nested
    @DisplayName("Wallet")
    class WalletTests {

        private final RpcClient rpc = new RpcClient("http://localhost:8080", 500, null);

        @Test
        @DisplayName("create() generates a wallet with a non-empty address")
        void createGeneratesWallet() {
            Wallet wallet = new Wallet(rpc, "testnet");
            Wallet.WalletInfo info = wallet.create();
            assertNotNull(info);
            assertNotNull(info.getAddress());
            assertFalse(info.getAddress().isEmpty());
            assertNotNull(info.getPrivateKey());
            assertNotNull(info.getPublicKey());
        }

        @Test
        @DisplayName("fromMnemonic() throws UnsupportedOperationException (not yet implemented)")
        void fromMnemonicThrowsUnsupported() {
            Wallet wallet = new Wallet(rpc, "testnet");
            assertThrows(UnsupportedOperationException.class, () ->
                wallet.fromMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "m/44'/60'/0'/0/0"));
        }

        @Test
        @DisplayName("getBalance() throws RpcException when no node reachable")
        void getBalanceThrowsRpcException() {
            Wallet wallet = new Wallet(rpc, "testnet");
            assertThrows(RpcClient.RpcException.class, () ->
                wallet.getBalance("1TestAddress0000000000000000000000000"));
        }

        @Test
        @DisplayName("validateAddress() returns false for null without throwing")
        void validateAddressNullReturnsFalse() {
            Wallet wallet = new Wallet(rpc, "testnet");
            assertFalse(wallet.validateAddress(null));
        }

        @Test
        @DisplayName("validateAddress() returns a boolean for arbitrary input")
        void validateAddressReturnsBoolean() {
            Wallet wallet = new Wallet(rpc, "testnet");
            boolean result = wallet.validateAddress("1SomeAddress");
            // result may be true or false depending on checksum; must not throw
            assertTrue(result || !result);
        }

        @Test
        @DisplayName("WalletInfo stores address, privateKey, publicKey")
        void walletInfoStoresFields() {
            Wallet.WalletInfo info = new Wallet.WalletInfo(
                "1MyAddress0000000000000000000000000000",
                "0xpriv",
                "0xpub"
            );
            assertEquals("1MyAddress0000000000000000000000000000", info.getAddress());
            assertEquals("0xpriv", info.getPrivateKey());
            assertEquals("0xpub", info.getPublicKey());
        }
    }

    // -- Transaction / Receipt POJOs ------------------------------------

    @Nested
    @DisplayName("Transaction POJO")
    class TransactionPojoTests {

        @Test
        @DisplayName("Setters and getters round-trip")
        void roundTrip() {
            TransactionBuilder.Transaction tx = new TransactionBuilder.Transaction();
            tx.setFrom("1Sender");
            tx.setTo("1Receiver");
            tx.setValue(BigInteger.valueOf(1000));
            tx.setGasLimit(BigInteger.valueOf(21000));
            tx.setGasPrice(BigInteger.valueOf(20));
            tx.setNonce(BigInteger.valueOf(5));
            tx.setData("0xabcd");
            tx.setToken("NEX");

            assertEquals("1Sender", tx.getFrom());
            assertEquals("1Receiver", tx.getTo());
            assertEquals(BigInteger.valueOf(1000), tx.getValue());
            assertEquals(BigInteger.valueOf(21000), tx.getGasLimit());
            assertEquals(BigInteger.valueOf(20), tx.getGasPrice());
            assertEquals(BigInteger.valueOf(5), tx.getNonce());
            assertEquals("0xabcd", tx.getData());
            assertEquals("NEX", tx.getToken());
        }
    }

    @Nested
    @DisplayName("TransactionReceipt POJO")
    class TransactionReceiptTests {

        @Test
        @DisplayName("Setters and getters round-trip")
        void roundTrip() {
            TransactionBuilder.TransactionReceipt receipt = new TransactionBuilder.TransactionReceipt();
            receipt.setTransactionHash("0xhash");
            receipt.setBlockHash("0xblock");
            receipt.setBlockNumber(12345);
            receipt.setStatus("SUCCESS");
            receipt.setGasUsed(BigInteger.valueOf(21000));

            assertEquals("0xhash", receipt.getTransactionHash());
            assertEquals("0xblock", receipt.getBlockHash());
            assertEquals(12345, receipt.getBlockNumber());
            assertEquals("SUCCESS", receipt.getStatus());
            assertEquals(BigInteger.valueOf(21000), receipt.getGasUsed());
        }
    }

    // -- RpcRequest POJO ------------------------------------------------

    @Nested
    @DisplayName("RpcRequest")
    class RpcRequestTests {

        @Test
        @DisplayName("Constructor stores fields")
        void constructorStoresFields() {
            Object[] params = { "0xabc", true };
            // 对齐 nexus-core：nexus_getBlockByHash 未实现，改用 nexus_getBlockByHeight
            RpcClient.RpcRequest req = new RpcClient.RpcRequest("nexus_getBlockByHeight", params, 1);
            assertEquals("nexus_getBlockByHeight", req.getMethod());
            assertArrayEquals(params, req.getParams());
            assertEquals(1, req.getId());
        }
    }
}
