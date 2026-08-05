package org.nexus.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.bridge.BridgeClient;
import org.nexus.sdk.channel.PaymentChannelClient;
import org.nexus.sdk.stablecoin.StableCoinClient;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：BridgeClient / PaymentChannelClient / StableCoinClient 的
 * 输入校验、不触网方法与 POJO 构造。
 *
 * <p>写操作与 RPC 查询依赖真实节点，不在单元测试覆盖范围内（由集成测试覆盖）。
 * 此处验证客户端在发起 RPC 前的参数校验与纯逻辑。</p>
 */
class SdkClientsUnitTest {

    private final RpcClient rpc = new RpcClient("http://localhost:9999", 1000, null);

    // -- BridgeClient ------------------------------------------------------

    @Nested
    @DisplayName("BridgeClient")
    class BridgeClientTests {

        @Test
        @DisplayName("Constructor requires rpcClient")
        void constructorRequiresRpc() {
            assertThrows(IllegalArgumentException.class, () -> new BridgeClient(null));
        }

        @Test
        @DisplayName("getSupportedChains returns ethereum/bsc/polygon")
        void supportedChains() {
            BridgeClient client = new BridgeClient(rpc);
            String[] chains = client.getSupportedChains();
            assertEquals(3, chains.length);
            assertArrayEquals(new String[]{"ethereum", "bsc", "polygon"}, chains);
        }

        @Test
        @DisplayName("getBridgeFee returns positive fee for supported chain")
        void bridgeFeeSupported() {
            BridgeClient client = new BridgeClient(rpc);
            BigInteger fee = client.getBridgeFee("NEX", "ethereum");
            assertTrue(fee.signum() > 0);
        }

        @Test
        @DisplayName("getBridgeFee rejects unsupported chain")
        void bridgeFeeUnsupportedChain() {
            BridgeClient client = new BridgeClient(rpc);
            assertThrows(IllegalArgumentException.class, () -> client.getBridgeFee("NEX", "solana"));
        }

        @Test
        @DisplayName("lock validates inputs before RPC")
        void lockValidation() {
            BridgeClient client = new BridgeClient(rpc);
            assertThrows(IllegalArgumentException.class,
                    () -> client.lock(null, "NEX", BigInteger.ONE, "ethereum", "0xtarget"));
            assertThrows(IllegalArgumentException.class,
                    () -> client.lock("0xfrom", "NEX", BigInteger.ZERO, "ethereum", "0xtarget"));
            assertThrows(IllegalArgumentException.class,
                    () -> client.lock("0xfrom", "NEX", BigInteger.ONE, "solana", "0xtarget"));
        }

        @Test
        @DisplayName("unlock validates inputs before RPC")
        void unlockValidation() {
            BridgeClient client = new BridgeClient(rpc);
            assertThrows(IllegalArgumentException.class,
                    () -> client.unlock(null, "NEX", BigInteger.ONE, "ethereum", "proof"));
            assertThrows(IllegalArgumentException.class,
                    () -> client.unlock("0xto", "NEX", BigInteger.ONE, "ethereum", null));
        }

        @Test
        @DisplayName("BridgeStatus stores fields")
        void bridgeStatusPojo() {
            BridgeClient.BridgeStatus s = new BridgeClient.BridgeStatus(
                    "0xtx", "confirmed", "ethereum", "nexus", 12L);
            assertEquals("0xtx", s.getTxHash());
            assertEquals("confirmed", s.getStatus());
            assertEquals("ethereum", s.getSourceChain());
            assertEquals("nexus", s.getTargetChain());
            assertEquals(12L, s.getConfirmations());
        }
    }

    // -- PaymentChannelClient ---------------------------------------------

    @Nested
    @DisplayName("PaymentChannelClient")
    class PaymentChannelClientTests {

        @Test
        @DisplayName("Constructor requires rpcClient")
        void constructorRequiresRpc() {
            assertThrows(IllegalArgumentException.class, () -> new PaymentChannelClient(null));
        }

        @Test
        @DisplayName("openChannel validates inputs")
        void openChannelValidation() {
            PaymentChannelClient client = new PaymentChannelClient(rpc);
            assertThrows(IllegalArgumentException.class,
                    () -> client.openChannel(null, "0xrecipient", BigInteger.ONE));
            assertThrows(IllegalArgumentException.class,
                    () -> client.openChannel("0xsender", "0xrecipient", BigInteger.ZERO));
        }

        @Test
        @DisplayName("closeChannel requires channelId")
        void closeChannelValidation() {
            PaymentChannelClient client = new PaymentChannelClient(rpc);
            assertThrows(IllegalArgumentException.class, () -> client.closeChannel(null));
        }

        @Test
        @DisplayName("BalanceProof stores fields")
        void balanceProofPojo() {
            PaymentChannelClient.BalanceProof p = new PaymentChannelClient.BalanceProof(
                    "ch-1", BigInteger.valueOf(500), 7L, "0xsig");
            assertEquals("ch-1", p.getChannelId());
            assertEquals(BigInteger.valueOf(500), p.getBalance());
            assertEquals(7L, p.getNonce());
            assertEquals("0xsig", p.getSignature());
        }

        @Test
        @DisplayName("ChannelInfo stores fields")
        void channelInfoPojo() {
            PaymentChannelClient.ChannelInfo info = new PaymentChannelClient.ChannelInfo(
                    "ch-1", "0xsender", "0xrecipient", BigInteger.valueOf(1000), "OPEN", 100L);
            assertEquals("ch-1", info.getChannelId());
            assertEquals("0xsender", info.getSender());
            assertEquals("0xrecipient", info.getRecipient());
            assertEquals(BigInteger.valueOf(1000), info.getDeposit());
            assertEquals("OPEN", info.getStatus());
            assertEquals(100L, info.getOpenBlock());
        }
    }

    // -- StableCoinClient -------------------------------------------------

    @Nested
    @DisplayName("StableCoinClient")
    class StableCoinClientTests {

        @Test
        @DisplayName("Constructor requires rpcClient")
        void constructorRequiresRpc() {
            assertThrows(IllegalArgumentException.class, () -> new StableCoinClient(null));
        }

        @Test
        @DisplayName("mint validates inputs")
        void mintValidation() {
            StableCoinClient client = new StableCoinClient(rpc);
            assertThrows(IllegalArgumentException.class,
                    () -> client.mint(null, BigInteger.ONE, BigInteger.ONE));
            assertThrows(IllegalArgumentException.class,
                    () -> client.mint("0xminter", BigInteger.ZERO, BigInteger.ONE));
            assertThrows(IllegalArgumentException.class,
                    () -> client.mint("0xminter", BigInteger.ONE, BigInteger.ZERO));
        }

        @Test
        @DisplayName("burn validates inputs")
        void burnValidation() {
            StableCoinClient client = new StableCoinClient(rpc);
            assertThrows(IllegalArgumentException.class, () -> client.burn(null, BigInteger.ONE));
            assertThrows(IllegalArgumentException.class, () -> client.burn("0xburner", BigInteger.ZERO));
        }

        @Test
        @DisplayName("transfer validates inputs")
        void transferValidation() {
            StableCoinClient client = new StableCoinClient(rpc);
            assertThrows(IllegalArgumentException.class,
                    () -> client.transfer(null, "0xto", BigInteger.ONE));
            assertThrows(IllegalArgumentException.class,
                    () -> client.transfer("0xfrom", "0xto", BigInteger.ZERO));
        }

        @Test
        @DisplayName("getCollateralRatio requires address")
        void collateralRatioValidation() {
            StableCoinClient client = new StableCoinClient(rpc);
            assertThrows(IllegalArgumentException.class, () -> client.getCollateralRatio(null));
        }
    }
}
