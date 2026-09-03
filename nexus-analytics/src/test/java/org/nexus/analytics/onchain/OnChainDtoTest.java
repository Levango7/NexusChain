package org.nexus.analytics.onchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link FundFlowTrace} 与 {@link AddressCluster} DTO 测试。
 *
 * <p>覆盖 Lombok 生成的 getter/setter/equals/hashCode/toString/builder。
 */
class OnChainDtoTest {

    @Test
    void fundFlowTrace_builderAndGetters_shouldWork() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-02T00:00:00Z");
        FundFlowTrace trace = FundFlowTrace.builder()
                .fromAddress("A").toAddress("B")
                .path(List.of("A", "B"))
                .txHashes(List.of("tx1"))
                .amount(BigInteger.valueOf(100))
                .startTimestamp(start)
                .endTimestamp(end)
                .hops(1)
                .build();

        assertEquals("A", trace.getFromAddress());
        assertEquals("B", trace.getToAddress());
        assertEquals(List.of("A", "B"), trace.getPath());
        assertEquals(List.of("tx1"), trace.getTxHashes());
        assertEquals(BigInteger.valueOf(100), trace.getAmount());
        assertEquals(start, trace.getStartTimestamp());
        assertEquals(end, trace.getEndTimestamp());
        assertEquals(1, trace.getHops());
    }

    @Test
    void fundFlowTrace_setters_shouldWork() {
        FundFlowTrace trace = new FundFlowTrace();
        trace.setFromAddress("A");
        trace.setToAddress("B");
        trace.setPath(List.of("A", "B"));
        trace.setTxHashes(List.of("h1"));
        trace.setAmount(BigInteger.TEN);
        trace.setHops(1);

        assertEquals("A", trace.getFromAddress());
        assertEquals("B", trace.getToAddress());
        assertEquals(BigInteger.TEN, trace.getAmount());
    }

    @Test
    void fundFlowTrace_equalsAndHashCode_shouldFollowContract() {
        FundFlowTrace t1 = FundFlowTrace.builder().fromAddress("A").toAddress("B").hops(1).build();
        FundFlowTrace t2 = FundFlowTrace.builder().fromAddress("A").toAddress("B").hops(1).build();
        FundFlowTrace t3 = FundFlowTrace.builder().fromAddress("X").toAddress("Y").hops(2).build();

        assertEquals(t1, t2);
        assertEquals(t1.hashCode(), t2.hashCode());
        assertNotEquals(t1, t3);
        assertNotNull(t1.toString());
    }

    @Test
    void fundFlowTrace_noArgsConstructor_shouldCreateEmpty() {
        FundFlowTrace trace = new FundFlowTrace();
        assertNotNull(trace);
        assertEquals(null, trace.getFromAddress());
    }

    @Test
    void addressCluster_builderAndGetters_shouldWork() {
        AddressCluster cluster = AddressCluster.builder()
                .clusterId("C1")
                .addresses(List.of("A", "B"))
                .label("EXCHANGE")
                .confidence(0.9)
                .txCount(10L)
                .totalVolume(1000L)
                .build();

        assertEquals("C1", cluster.getClusterId());
        assertEquals(List.of("A", "B"), cluster.getAddresses());
        assertEquals("EXCHANGE", cluster.getLabel());
        assertEquals(0.9, cluster.getConfidence(), 0.001);
        assertEquals(10L, cluster.getTxCount());
        assertEquals(1000L, cluster.getTotalVolume());
    }

    @Test
    void addressCluster_setters_shouldWork() {
        AddressCluster cluster = new AddressCluster();
        cluster.setClusterId("C2");
        cluster.setLabel("WALLET");
        cluster.setConfidence(0.5);
        cluster.setTxCount(5L);
        cluster.setTotalVolume(500L);

        assertEquals("C2", cluster.getClusterId());
        assertEquals("WALLET", cluster.getLabel());
        assertEquals(0.5, cluster.getConfidence(), 0.001);
    }

    @Test
    void addressCluster_equalsAndHashCode_shouldFollowContract() {
        AddressCluster c1 = AddressCluster.builder().clusterId("C1").label("L").build();
        AddressCluster c2 = AddressCluster.builder().clusterId("C1").label("L").build();
        AddressCluster c3 = AddressCluster.builder().clusterId("C2").label("L").build();

        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
        assertNotEquals(c1, c3);
        assertNotNull(c1.toString());
    }

    @Test
    void onChainTransaction_equalsAndHashCode_shouldFollowContract() {
        OnChainTransaction t1 = OnChainTransaction.builder().txHash("h1").fromAddress("A").build();
        OnChainTransaction t2 = OnChainTransaction.builder().txHash("h1").fromAddress("A").build();
        OnChainTransaction t3 = OnChainTransaction.builder().txHash("h2").fromAddress("B").build();

        assertEquals(t1, t2);
        assertEquals(t1.hashCode(), t2.hashCode());
        assertNotEquals(t1, t3);
        assertNotNull(t1.toString());
    }

    @Test
    void onChainTransaction_allStatusEnumValues_shouldBeAccessible() {
        assertEquals(OnChainTransaction.Status.PENDING, OnChainTransaction.Status.valueOf("PENDING"));
        assertEquals(OnChainTransaction.Status.SUCCESS, OnChainTransaction.Status.valueOf("SUCCESS"));
        assertEquals(OnChainTransaction.Status.FAILED, OnChainTransaction.Status.valueOf("FAILED"));
        assertEquals(3, OnChainTransaction.Status.values().length);
    }

    // === Path B 扩展：routingLatencyMs / costBps 链路埋点字段 ===

    @Test
    void onChainTransaction_builder_latencyAndCost_shouldWork() {
        OnChainTransaction tx = OnChainTransaction.builder()
                .txHash("h1")
                .routingLatencyMs(42L)
                .costBps(5)
                .build();

        assertEquals("h1", tx.getTxHash());
        assertEquals(42L, tx.getRoutingLatencyMs());
        assertEquals(5, tx.getCostBps());
    }

    @Test
    void onChainTransaction_setters_latencyAndCost_shouldWork() {
        OnChainTransaction tx = new OnChainTransaction();
        tx.setRoutingLatencyMs(7L);
        tx.setCostBps(12);

        assertEquals(7L, tx.getRoutingLatencyMs());
        assertEquals(12, tx.getCostBps());
    }

    @Test
    void onChainTransaction_equalsAndHashCode_includesLatencyAndCost() {
        OnChainTransaction t1 = OnChainTransaction.builder().txHash("h1")
                .routingLatencyMs(42L).costBps(5).build();
        OnChainTransaction t2 = OnChainTransaction.builder().txHash("h1")
                .routingLatencyMs(42L).costBps(5).build();
        OnChainTransaction t3 = OnChainTransaction.builder().txHash("h1")
                .routingLatencyMs(99L).costBps(5).build();

        assertEquals(t1, t2);
        assertEquals(t1.hashCode(), t2.hashCode());
        assertNotEquals(t1, t3);
    }

    @Test
    void onChainTransaction_jsonSerialization_shouldEmitJsonPropertyNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OnChainTransaction tx = OnChainTransaction.builder()
                .txHash("h1").routingLatencyMs(42L).costBps(5).build();

        String json = mapper.writeValueAsString(tx);

        // 埋点字段按 @JsonProperty 输出：routingLatencyMs（camelCase），costBps → cost_bps
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"routingLatencyMs\":42"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"cost_bps\":5"));
    }

    @Test
    void onChainTransaction_jsonDeserialization_shouldRestoreLatencyAndCost() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OnChainTransaction tx = mapper.readValue(
                "{\"txHash\":\"h1\",\"routingLatencyMs\":42,\"cost_bps\":5}",
                OnChainTransaction.class);

        assertEquals("h1", tx.getTxHash());
        assertEquals(42L, tx.getRoutingLatencyMs());
        assertEquals(5, tx.getCostBps());
    }
}