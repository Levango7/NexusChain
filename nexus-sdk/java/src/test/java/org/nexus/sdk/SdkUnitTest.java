package org.nexus.sdk;

import java.math.BigInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NexusChain SDK.
 * Synchronized with current API signatures (2026-08-05).
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
        @DisplayName("call() throws UnsupportedOperationException (not yet implemented)")
        void callThrowsUnsupported() {
            RpcClient client = new RpcClient("http://localhost:8080", 30000, null);
            assertThrows(UnsupportedOperationException.class, () ->
                client.call("nexus_blockNumber"));
        }

        @Test
        @DisplayName("getBlockNumber() throws UnsupportedOperationException")
        void blockNumberThrowsUnsupported() {
            RpcClient client = new RpcClient("http://localhost:8080", 30000, null);
            assertThrows(UnsupportedOperationException.class, client::getBlockNumber);
        }

        @Test
        @DisplayName("getChainId() throws UnsupportedOperationException")
        void chainIdThrowsUnsupported() {
            RpcClient client = new RpcClient("http://localhost:8080", 30000, null);
            assertThrows(UnsupportedOperationException.class, client::getChainId);
        }
    }

    // -- TransactionBuilder tests ----------------------------------------

    @Nested
    @DisplayName("TransactionBuilder")
    class TransactionBuilderTests {

        private final RpcClient rpc = new RpcClient("http://localhost:8080", 30000, null);

        @Test
        @DisplayName("buildTransfer() throws UnsupportedOperationException (stub)")
        void buildTransferThrowsUnsupported() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "testnet");
            assertThrows(UnsupportedOperationException.class, () ->
                builder.buildTransfer("1Sender000", "1Receiver000", BigInteger.valueOf(10000), "NEX"));
        }

        @Test
        @DisplayName("Constructor stores RPC client and network")
        void constructorStoresFields() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "mainnet");
            assertNotNull(builder);
        }

        @Test
        @DisplayName("sign() throws UnsupportedOperationException")
        void signThrowsUnsupported() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "mainnet");
            TransactionBuilder.Transaction tx = new TransactionBuilder.Transaction();
            assertThrows(UnsupportedOperationException.class, () ->
                builder.sign(tx, "0xdeadbeef"));
        }

        @Test
        @DisplayName("getGasPrice() throws UnsupportedOperationException")
        void gasPriceThrowsUnsupported() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "mainnet");
            assertThrows(UnsupportedOperationException.class, builder::getGasPrice);
        }

        @Test
        @DisplayName("estimateGas() throws UnsupportedOperationException")
        void estimateGasThrowsUnsupported() {
            TransactionBuilder builder = new TransactionBuilder(rpc, "mainnet");
            TransactionBuilder.Transaction tx = new TransactionBuilder.Transaction();
            assertThrows(UnsupportedOperationException.class, () -> builder.estimateGas(tx));
        }
    }

    // -- Wallet tests ---------------------------------------------------

    @Nested
    @DisplayName("Wallet")
    class WalletTests {

        private final RpcClient rpc = new RpcClient("http://localhost:8080", 30000, null);

        @Test
        @DisplayName("create() throws UnsupportedOperationException (stub)")
        void createThrowsUnsupported() {
            Wallet wallet = new Wallet(rpc, "testnet");
            assertThrows(UnsupportedOperationException.class, wallet::create);
        }

        @Test
        @DisplayName("fromPrivateKey() throws UnsupportedOperationException")
        void fromPrivateKeyThrowsUnsupported() {
            Wallet wallet = new Wallet(rpc, "testnet");
            assertThrows(UnsupportedOperationException.class, () ->
                wallet.fromPrivateKey("0xdeadbeef0000000000000000000000000000000000000000000000000000000000"));
        }

        @Test
        @DisplayName("fromMnemonic() throws UnsupportedOperationException")
        void fromMnemonicThrowsUnsupported() {
            Wallet wallet = new Wallet(rpc, "testnet");
            assertThrows(UnsupportedOperationException.class, () ->
                wallet.fromMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "m/44'/60'/0'/0/0"));
        }

        @Test
        @DisplayName("getBalance() throws UnsupportedOperationException")
        void getBalanceThrowsUnsupported() {
            Wallet wallet = new Wallet(rpc, "testnet");
            assertThrows(UnsupportedOperationException.class, () ->
                wallet.getBalance("1TestAddress0000000000000000000000000"));
        }

        @Test
        @DisplayName("validateAddress() throws UnsupportedOperationException")
        void validateAddressThrowsUnsupported() {
            Wallet wallet = new Wallet(rpc, "testnet");
            assertThrows(UnsupportedOperationException.class, () ->
                wallet.validateAddress("1TestAddress"));
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
            RpcClient.RpcRequest req = new RpcClient.RpcRequest("nexus_getBlockByHash", params, 1);
            assertEquals("nexus_getBlockByHash", req.getMethod());
            assertArrayEquals(params, req.getParams());
            assertEquals(1, req.getId());
        }
    }
}