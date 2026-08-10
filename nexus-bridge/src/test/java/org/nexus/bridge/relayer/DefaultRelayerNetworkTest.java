package org.nexus.bridge.relayer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultRelayerNetwork} 单元测试：验证中继请求生命周期、
 * relayer 加权选取与证明验证。
 */
class DefaultRelayerNetworkTest {

    private DefaultRelayerNetwork network;

    @BeforeEach
    void setUp() {
        network = new DefaultRelayerNetwork();
        network.registerRelayer(new Relayer(
                "relayer-1", "0xaaa", new BigDecimal("1000"), 90.0, RelayerStatus.ACTIVE));
        network.registerRelayer(new Relayer(
                "relayer-2", "0xbbb", new BigDecimal("500"), 80.0, RelayerStatus.ACTIVE));
        network.registerRelayer(new Relayer(
                "relayer-3", "0xccc", new BigDecimal("2000"), 95.0, RelayerStatus.INACTIVE));
    }

    @Test
    void submitRelayRequest_assignsActiveRelayer() {
        RelayRequest request = new RelayRequest();
        request.setSourceChain("chain-a");
        request.setTargetChain("chain-b");
        request.setSourceTxHash("0xtx123");
        request.setAmount(new BigDecimal("100"));

        String requestId = network.submitRelayRequest(request);

        assertNotNull(requestId);
        assertTrue(requestId.startsWith("RELAY-"));
        assertEquals(RelayRequestStatus.RELAYING, request.getStatus());
        assertNotNull(request.getAssignedRelayerId());
        // INACTIVE relayer 不应被分配
        assertNotEquals("relayer-3", request.getAssignedRelayerId());
    }

    @Test
    void submitRelayRequest_sameChainRejected() {
        RelayRequest request = new RelayRequest();
        request.setSourceChain("chain-a");
        request.setTargetChain("chain-a");
        request.setSourceTxHash("0xtx");
        request.setAmount(BigDecimal.ONE);

        assertThrows(IllegalArgumentException.class, () -> network.submitRelayRequest(request));
    }

    @Test
    void submitRelayRequest_nonPositiveAmountRejected() {
        RelayRequest request = new RelayRequest();
        request.setSourceChain("chain-a");
        request.setTargetChain("chain-b");
        request.setSourceTxHash("0xtx");
        request.setAmount(BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class, () -> network.submitRelayRequest(request));
    }

    @Test
    void submitRelayRequest_noActiveRelayerThrows() {
        DefaultRelayerNetwork empty = new DefaultRelayerNetwork();
        RelayRequest request = new RelayRequest();
        request.setSourceChain("chain-a");
        request.setTargetChain("chain-b");
        request.setSourceTxHash("0xtx");
        request.setAmount(BigDecimal.ONE);

        assertThrows(IllegalStateException.class, () -> empty.submitRelayRequest(request));
    }

    @Test
    void selectRelayer_picksOnlyActive() {
        for (int i = 0; i < 20; i++) {
            Relayer selected = network.selectRelayer();
            assertNotNull(selected);
            assertEquals(RelayerStatus.ACTIVE, selected.getStatus());
        }
    }

    @Test
    void getRelayerStatus_returnsRegistered() {
        Relayer r = network.getRelayerStatus("relayer-1");
        assertNotNull(r);
        assertEquals("0xaaa", r.getAddress());
        assertNull(network.getRelayerStatus("unknown"));
    }

    @Test
    void verifyRelayProof_validRequestPasses() {
        RelayRequest request = new RelayRequest();
        request.setSourceChain("chain-a");
        request.setTargetChain("chain-b");
        request.setSourceTxHash("0xtx123");
        request.setAmount(new BigDecimal("100"));
        String requestId = network.submitRelayRequest(request);

        // 构造证明：与存储请求一致
        RelayRequest proof = new RelayRequest();
        proof.setRequestId(requestId);
        proof.setSourceTxHash("0xtx123");
        proof.setAmount(new BigDecimal("100"));

        assertTrue(network.verifyRelayProof(proof));
    }

    @Test
    void verifyRelayProof_tamperedAmountFails() {
        RelayRequest request = new RelayRequest();
        request.setSourceChain("chain-a");
        request.setTargetChain("chain-b");
        request.setSourceTxHash("0xtx123");
        request.setAmount(new BigDecimal("100"));
        String requestId = network.submitRelayRequest(request);

        RelayRequest proof = new RelayRequest();
        proof.setRequestId(requestId);
        proof.setSourceTxHash("0xtx123");
        proof.setAmount(new BigDecimal("999")); // 篡改金额

        assertFalse(network.verifyRelayProof(proof));
    }

    @Test
    void completeRelayRequest_setsCompleted() {
        RelayRequest request = new RelayRequest();
        request.setSourceChain("chain-a");
        request.setTargetChain("chain-b");
        request.setSourceTxHash("0xtx");
        request.setAmount(BigDecimal.ONE);
        String requestId = network.submitRelayRequest(request);

        RelayRequest completed = network.completeRelayRequest(requestId);

        assertNotNull(completed);
        assertEquals(RelayRequestStatus.COMPLETED, completed.getStatus());
    }
}
