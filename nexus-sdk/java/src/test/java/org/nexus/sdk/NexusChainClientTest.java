package org.nexus.sdk;

import org.junit.jupiter.api.Test;
import org.nexus.sdk.bridge.BridgeClient;
import org.nexus.sdk.channel.PaymentChannelClient;
import org.nexus.sdk.stablecoin.StableCoinClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * NexusChainClient 与 Builder 单元测试。
 */
class NexusChainClientTest {

    @Test
    void builder_defaults_shouldUseMainnetAndDefaultRpcUrl() {
        NexusChainClient client = new NexusChainClient.Builder().build();

        assertEquals("mainnet", client.getNetwork());
        assertEquals("https://rpc.nexus.network", client.getRpcUrl());
    }

    @Test
    void builder_customSettings_shouldApplyAll() {
        NexusChainClient client = new NexusChainClient.Builder()
                .network("testnet")
                .rpcUrl("http://localhost:8080")
                .timeout(5000)
                .apiKey("sk-test")
                .build();

        assertEquals("testnet", client.getNetwork());
        assertEquals("http://localhost:8080", client.getRpcUrl());
    }

    @Test
    void client_shouldExposeAllSubClients() {
        NexusChainClient client = new NexusChainClient.Builder()
                .rpcUrl("http://localhost:8080")
                .timeout(500)
                .build();

        assertNotNull(client.wallet());
        assertNotNull(client.transactionBuilder());
        assertNotNull(client.rpcClient());
        assertNotNull(client.paymentChannel());
        assertNotNull(client.stableCoin());
        assertNotNull(client.bridge());
    }

    @Test
    void client_subClients_shouldBeSameInstanceOnMultipleCalls() {
        NexusChainClient client = new NexusChainClient.Builder()
                .rpcUrl("http://localhost:8080")
                .timeout(500)
                .build();

        // 子客户端应为单例（构造时创建）
        assertEquals(client.wallet(), client.wallet());
        assertEquals(client.rpcClient(), client.rpcClient());
    }

    @Test
    void builder_chainedCalls_shouldReturnBuilder() {
        NexusChainClient.Builder builder = new NexusChainClient.Builder();
        assertEquals(builder, builder.network("testnet"));
        assertEquals(builder, builder.rpcUrl("http://x"));
        assertEquals(builder, builder.timeout(1000));
        assertEquals(builder, builder.apiKey("key"));
    }
}