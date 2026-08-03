package org.nexus.sdk;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NexusChain SDK: RPC client, transaction builder, wallet.
 */
class SdkUnitTest {

    @Nested
    @DisplayName("TransactionBuilder")
    class TransactionBuilderTests {

        @Test
        @DisplayName("Build transfer transaction with correct fields")
        void buildTransfer() {
            TransactionBuilder builder = new TransactionBuilder();
            byte[] raw = builder.transfer("1SenderAddr000000000000000000000000000", "1ReceiverAddr00000000000000000000000000", 10000L, 1L, 1L).build();
            assertNotNull(raw);
            assertTrue(raw.length > 0, "Serialized transaction should not be empty");
        }

        @Test
        @DisplayName("Reject negative amount")
        void rejectNegativeAmount() {
            TransactionBuilder builder = new TransactionBuilder();
            assertThrows(IllegalArgumentException.class, () ->
                builder.transfer("1Sender000", "1Receiver000", -100L, 1L, 1L));
        }

        @Test
        @DisplayName("Reject zero nonce")
        void rejectZeroNonce() {
            TransactionBuilder builder = new TransactionBuilder();
            assertThrows(IllegalArgumentException.class, () ->
                builder.transfer("1Sender000", "1Receiver000", 100L, 0L, 1L));
        }
    }

    @Nested
    @DisplayName("Wallet")
    class WalletTests {

        @Test
        @DisplayName("Generate wallet with valid address")
        void generateWallet() {
            Wallet wallet = Wallet.generate();
            assertNotNull(wallet.getAddress());
            assertTrue(wallet.getAddress().startsWith("1"), "Address should start with '1'");
            assertTrue(wallet.getAddress().length() >= 30, "Address should be at least 30 chars");
        }

        @Test
        @DisplayName("Sign and verify message")
        void signAndVerify() {
            Wallet wallet = Wallet.generate();
            byte[] message = "Hello NexusChain".getBytes();
            byte[] signature = wallet.sign(message);
            assertNotNull(signature);
            assertTrue(signature.length == 64, "Ed25519 signature should be 64 bytes");
            assertTrue(wallet.verify(message, signature), "Signature should verify");
        }

        @Test
        @DisplayName("Reject tampered message")
        void rejectTampered() {
            Wallet wallet = Wallet.generate();
            byte[] message = "Original".getBytes();
            byte[] signature = wallet.sign(message);
            byte[] tampered = "Tampered".getBytes();
            assertFalse(wallet.verify(tampered, signature), "Tampered message should fail verification");
        }

        @Test
        @DisplayName("Import wallet from private key")
        void importFromKey() {
            Wallet original = Wallet.generate();
            Wallet imported = Wallet.fromPrivateKey(original.getPrivateKey());
            assertEquals(original.getAddress(), imported.getAddress(), "Imported wallet should have same address");
        }
    }

    @Nested
    @DisplayName("RpcClient")
    class RpcClientTests {

        @Test
        @DisplayName("Construct client with valid URL")
        void constructClient() {
            RpcClient client = new RpcClient("http://localhost:8080");
            assertNotNull(client);
        }

        @Test
        @DisplayName("Reject invalid URL")
        void rejectInvalidUrl() {
            assertThrows(IllegalArgumentException.class, () -> new RpcClient("not-a-url"));
        }

        @Test
        @DisplayName("Reject null URL")
        void rejectNullUrl() {
            assertThrows(IllegalArgumentException.class, () -> new RpcClient(null));
        }
    }
}