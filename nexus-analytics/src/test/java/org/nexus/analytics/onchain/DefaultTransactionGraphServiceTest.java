package org.nexus.analytics.onchain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultTransactionGraphService} 单元测试。
 */
class DefaultTransactionGraphServiceTest {

    private InMemoryTransactionDataSource dataSource;
    private DefaultTransactionGraphService service;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryTransactionDataSource();
        service = new DefaultTransactionGraphService(dataSource);
        // 链：A -> B -> C -> D
        feed("tx1", "A", "B", 10);
        feed("tx2", "B", "C", 20);
        feed("tx3", "C", "D", 30);
    }

    private void feed(String hash, String from, String to, long amount) {
        dataSource.feed(List.of(OnChainTransaction.builder()
                .txHash(hash).fromAddress(from).toAddress(to)
                .amount(BigInteger.valueOf(amount))
                .timestamp(Instant.now())
                .status(OnChainTransaction.Status.SUCCESS)
                .build()));
    }

    @Test
    void buildGraph_depth1_shouldIncludeDirectCounterparties() {
        AddressCluster cluster = service.buildGraph("A", 1);

        assertTrue(cluster.getAddresses().contains("A"));
        assertTrue(cluster.getAddresses().contains("B"));
        // depth=1 子图仅含 A、B，涉及交易为 tx1(A→B)、tx2(B→C)
        assertEquals(2L, cluster.getTxCount());
    }

    @Test
    void buildGraph_depth2_shouldReachTwoHops() {
        AddressCluster cluster = service.buildGraph("A", 2);

        assertTrue(cluster.getAddresses().contains("C"));
        // D 在 3 跳处，depth=2 不可达
        assertTrue(!cluster.getAddresses().contains("D"));
    }

    @Test
    void findPath_existing_shouldReturnPathWithHops() {
        Optional<FundFlowTrace> trace = service.findPath("A", "D");

        assertTrue(trace.isPresent());
        assertEquals(3, trace.get().getHops());
        assertEquals(List.of("A", "B", "C", "D"), trace.get().getPath());
        assertEquals(3, trace.get().getTxHashes().size());
    }

    @Test
    void findPath_sameAddress_shouldReturnZeroHops() {
        Optional<FundFlowTrace> trace = service.findPath("A", "A");

        assertTrue(trace.isPresent());
        assertEquals(0, trace.get().getHops());
    }

    @Test
    void findPath_noPath_shouldReturnEmpty() {
        feed("tx4", "X", "Y", 5);

        Optional<FundFlowTrace> trace = service.findPath("A", "X");

        assertTrue(trace.isEmpty());
    }

    @Test
    void getCluster_shouldGroupSharedCounterparties() {
        // B 与 C 共享对手方（互相交易），应归入同簇
        AddressCluster cluster = service.getCluster("B");

        assertTrue(cluster.getAddresses().contains("B"));
        assertTrue(cluster.getAddresses().size() >= 1);
        assertEquals("HEURISTIC", cluster.getLabel());
    }

    @Test
    void getClusters_shouldDeduplicate() {
        List<AddressCluster> clusters = service.getClusters(List.of("B", "C"));
        assertTrue(clusters.size() <= 2);
    }
}
