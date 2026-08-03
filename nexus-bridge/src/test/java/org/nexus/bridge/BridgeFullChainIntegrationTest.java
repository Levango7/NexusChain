package org.nexus.bridge;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bridge full-chain integration test: lock → mint → burn → unlock.
 * Tests the complete cross-chain asset lifecycle via the actual REST API contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BridgeFullChainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String lockTxId;
    private static String burnTxId;

    private static final String LOCK_BODY =
            "{\"sourceChainId\":\"nexus\",\"targetChainId\":\"ethereum\","
                    + "\"amount\":500000,\"userAddress\":\"0xUser\","
                    + "\"targetAddress\":\"0xRecipient\",\"sourceTxHash\":\"0xLockHash\"}";

    private static final String TWO_SIGS =
            "\"signatures\":{\"validator-1\":\"sig1\",\"validator-2\":\"sig2\"}";

    @Test
    @Order(1)
    @DisplayName("Lock assets on source chain")
    void lockAssets() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/bridge/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOCK_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.operationType").value("BRIDGE_LOCK"))
                .andExpect(jsonPath("$.sourceChainId").value("nexus"))
                .andExpect(jsonPath("$.amount").value(500000))
                .andReturn();
        lockTxId = JsonPath.read(res.getResponse().getContentAsString(), "$.txId");
        assertNotNull(lockTxId, "txId should be returned");
    }

    @Test
    @Order(2)
    @DisplayName("Mint wrapped assets on target chain with threshold signatures")
    void mintWrapped() throws Exception {
        mockMvc.perform(post("/api/v1/bridge/mint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockTxId\":\"" + lockTxId + "\","
                                + TWO_SIGS
                                + ",\"minterAddress\":\"0xMinter\",\"targetChainId\":\"ethereum\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MINTED"));
    }

    @Test
    @Order(3)
    @DisplayName("Burn wrapped assets (initiate return)")
    void burnWrapped() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/bridge/burn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceChainId\":\"nexus\",\"targetChainId\":\"ethereum\","
                                + "\"amount\":500000,\"userAddress\":\"0xUser\","
                                + "\"targetAddress\":\"0xRecipient\",\"sourceTxHash\":\"0xBurnHash\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BURNED"))
                .andExpect(jsonPath("$.operationType").value("BRIDGE_BURN"))
                .andReturn();
        burnTxId = JsonPath.read(res.getResponse().getContentAsString(), "$.txId");
        assertNotNull(burnTxId, "burn txId should be returned");
    }

    @Test
    @Order(4)
    @DisplayName("Unlock original assets on source chain")
    void unlockAssets() throws Exception {
        mockMvc.perform(post("/api/v1/bridge/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"burnTxId\":\"" + burnTxId + "\","
                                + TWO_SIGS
                                + ",\"unlockerAddress\":\"0xUnlocker\",\"sourceChainId\":\"nexus\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNLOCKED"));
    }

    @Test
    @Order(5)
    @DisplayName("Query bridge transaction by txId")
    void queryTx() throws Exception {
        mockMvc.perform(get("/api/v1/bridge/tx/" + lockTxId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MINTED"))
                .andExpect(jsonPath("$.sourceChainId").value("nexus"))
                .andExpect(jsonPath("$.targetChainId").value("ethereum"))
                .andExpect(jsonPath("$.amount").value(500000));
    }

    @Test
    @Order(6)
    @DisplayName("Reject mint with insufficient signatures (below threshold)")
    void rejectMintWithInsufficientSignatures() throws Exception {
        MvcResult lockRes = mockMvc.perform(post("/api/v1/bridge/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceChainId\":\"nexus\",\"targetChainId\":\"ethereum\","
                                + "\"amount\":100000,\"userAddress\":\"0xUser2\","
                                + "\"targetAddress\":\"0xRecipient2\",\"sourceTxHash\":\"0xLockHash2\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String txId = JsonPath.read(lockRes.getResponse().getContentAsString(), "$.txId");

        mockMvc.perform(post("/api/v1/bridge/mint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockTxId\":\"" + txId + "\","
                                + "\"signatures\":{\"validator-1\":\"sig1\"},"
                                + "\"minterAddress\":\"0xMinter\",\"targetChainId\":\"ethereum\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(7)
    @DisplayName("Bridge status reports active state and pending count")
    void bridgeStatus() throws Exception {
        mockMvc.perform(get("/api/v1/bridge/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.signatureThreshold").value(2));
    }
}
