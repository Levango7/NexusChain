package org.nexus.signing.mpc;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MpcWallet} 单元测试。
 */
public class MpcWalletTest {

    @Test
    public void testGettersAndSetters() {
        MpcWallet wallet = new MpcWallet();
        wallet.setWalletId("w1");
        wallet.setParticipants(List.of("p1", "p2", "p3"));
        wallet.setThreshold(2);
        wallet.setPublicKey("joint-pk-hex");
        wallet.setStatus(MpcWallet.WalletStatus.ACTIVE);
        LocalDateTime created = LocalDateTime.now();
        wallet.setCreatedAt(created);
        LocalDateTime rotated = LocalDateTime.now();
        wallet.setLastRotatedAt(rotated);
        wallet.setLabel("cold wallet");

        assertEquals(wallet.getWalletId(), "w1");
        assertEquals(3, wallet.getParticipants().size());
        assertEquals(2, wallet.getThreshold().intValue());
        assertEquals(wallet.getPublicKey(), "joint-pk-hex");
        assertEquals(MpcWallet.WalletStatus.ACTIVE, wallet.getStatus());
        assertEquals(created, wallet.getCreatedAt());
        assertEquals(rotated, wallet.getLastRotatedAt());
        assertEquals(wallet.getLabel(), "cold wallet");
    }

    @Test
    public void testDefaultStatusActive() {
        MpcWallet wallet = new MpcWallet();
        assertEquals(MpcWallet.WalletStatus.ACTIVE, wallet.getStatus());
    }

    @Test
    public void testAllStatusValues() {
        MpcWallet wallet = new MpcWallet();
        for (MpcWallet.WalletStatus s : Arrays.asList(
                MpcWallet.WalletStatus.ACTIVE,
                MpcWallet.WalletStatus.ROTATING,
                MpcWallet.WalletStatus.FROZEN,
                MpcWallet.WalletStatus.DECOMMISSIONED)) {
            wallet.setStatus(s);
            assertEquals(s, wallet.getStatus());
        }
    }

    @Test
    public void testDefaultParticipantsEmptyList() {
        MpcWallet wallet = new MpcWallet();
        assertNotNull(wallet.getParticipants());
        assertEquals(0, wallet.getParticipants().size());
    }
}