package org.nexus.sdk.client;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TransportMode / InProcessSigningServiceClient / InProcessWalletMgmtClient 单元测试。
 */
class SdkClientSkeletonTest {

    @Test
    void transportMode_shouldHaveTwoValues() {
        TransportMode[] modes = TransportMode.values();
        assertEquals(2, modes.length);
        assertEquals(TransportMode.IN_PROCESS, TransportMode.valueOf("IN_PROCESS"));
        assertEquals(TransportMode.HTTP, TransportMode.valueOf("HTTP"));
    }

    @Test
    void inProcessSigningServiceClient_signTransfer_shouldReturnNull() {
        InProcessSigningServiceClient client = new InProcessSigningServiceClient();

        assertNull(client.signTransfer("pubkey", "toHash", BigDecimal.TEN));
    }

    @Test
    void inProcessSigningServiceClient_transfer_shouldReturnNull() {
        InProcessSigningServiceClient client = new InProcessSigningServiceClient();

        assertNull(client.transfer("pubkey", "toHash", BigDecimal.TEN, "privkey"));
    }

    @Test
    void inProcessSigningServiceClient_canSignViaMpc_shouldReturnFalse() {
        InProcessSigningServiceClient client = new InProcessSigningServiceClient();

        assertFalse(client.canSignViaMpc(BigDecimal.TEN));
    }

    @Test
    void inProcessWalletMgmtClient_addressToPubkeyHash_shouldReturnNull() {
        InProcessWalletMgmtClient client = new InProcessWalletMgmtClient();

        assertNull(client.addressToPubkeyHash("1Address"));
    }

    @Test
    void inProcessWalletMgmtClient_verifyAddress_shouldReturnFalse() {
        InProcessWalletMgmtClient client = new InProcessWalletMgmtClient();

        assertFalse(client.verifyAddress("1Address"));
    }

    @Test
    void inProcessWalletMgmtClient_isAddressWhitelisted_shouldReturnFalse() {
        InProcessWalletMgmtClient client = new InProcessWalletMgmtClient();

        assertFalse(client.isAddressWhitelisted("1Address"));
    }

    @Test
    void inProcessWalletMgmtClient_getCustodyTier_shouldReturnHot() {
        InProcessWalletMgmtClient client = new InProcessWalletMgmtClient();

        assertEquals("HOT", client.getCustodyTier("wallet-1"));
    }
}