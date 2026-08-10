package org.nexus.signing.mpc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MpcApprovalPolicy} 单元测试。
 */
public class MpcApprovalPolicyTest {

    private MpcApprovalPolicy policy;

    @BeforeEach
    public void setUp() {
        policy = new MpcApprovalPolicy();
    }

    @Test
    public void testGetRequiredApproversColdWalletTier() {
        // amount >= 50000 → cold wallet threshold (3)
        assertEquals(3, policy.getRequiredApprovers(new BigDecimal("50000"), "NEX"));
        assertEquals(3, policy.getRequiredApprovers(new BigDecimal("100000"), "NEX"));
        assertEquals(3, policy.getRequiredApprovers(new BigDecimal("50000.01"), "NEX"));
    }

    @Test
    public void testGetRequiredApproversWarmWalletTier() {
        // amount < 50000 → warm wallet (1)
        assertEquals(1, policy.getRequiredApprovers(new BigDecimal("49999"), "NEX"));
        assertEquals(1, policy.getRequiredApprovers(new BigDecimal("1"), "NEX"));
    }

    @Test
    public void testGetRequiredApproversNullAmountThrows() { assertThrows(IllegalArgumentException.class, () -> {
        policy.getRequiredApprovers(null, "NEX");
        });
    }

    @Test
    public void testGetRequiredApproversZeroAmountThrows() { assertThrows(IllegalArgumentException.class, () -> {
        policy.getRequiredApprovers(BigDecimal.ZERO, "NEX");
        });
    }

    @Test
    public void testGetRequiredApproversNegativeAmountThrows() { assertThrows(IllegalArgumentException.class, () -> {
        policy.getRequiredApprovers(new BigDecimal("-1"), "NEX");
        });
    }

    @Test
    public void testIsColdWalletTier() {
        assertTrue(policy.isColdWalletTier(new BigDecimal("50000")));
        assertTrue(policy.isColdWalletTier(new BigDecimal("999999")));
        assertFalse(policy.isColdWalletTier(new BigDecimal("49999")));
        assertFalse(policy.isColdWalletTier(null));
    }

    @Test
    public void testWhitelistManagement() {
        assertFalse(policy.isAddressWhitelisted("addr1"));
        policy.addToWhitelist("addr1");
        assertTrue(policy.isAddressWhitelisted("addr1"));
        policy.removeFromWhitelist("addr1");
        assertFalse(policy.isAddressWhitelisted("addr1"));
    }

    @Test
    public void testIsAddressWhitelistedNullReturnsFalse() {
        assertFalse(policy.isAddressWhitelisted(null));
    }

    @Test
    public void testAddEmptyToWhitelistIgnored() {
        policy.addToWhitelist("");
        policy.addToWhitelist(null);
        // 不应抛异常，也不应添加
        assertFalse(policy.isAddressWhitelisted(""));
        assertFalse(policy.isAddressWhitelisted(null));
    }

    @Test
    public void testRemoveNullFromWhitelistNoThrow() {
        policy.removeFromWhitelist(null); // 不抛异常
    }

    @Test
    public void testCanSignWarmWalletAlwaysTrue() {
        // 暖钱包金额 → 不需要 MPC，直接 true
        assertTrue(policy.canSign(new BigDecimal("100"), List.of()));
        assertTrue(policy.canSign(new BigDecimal("100"), null));
    }

    @Test
    public void testCanSignColdWalletQuorumReached() {
        // 冷钱包金额 + 3 在线 → true
        List<MpcParticipant> threeOnline = List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2"),
                new MpcParticipant("p3", "h3", "pk3"));
        assertTrue(policy.canSign(new BigDecimal("50000"), threeOnline));
    }

    @Test
    public void testCanSignColdWalletQuorumNotReached() {
        // 冷钱包金额 + 仅 2 在线 → false
        List<MpcParticipant> twoOnline = List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2"));
        assertFalse(policy.canSign(new BigDecimal("50000"), twoOnline));
    }

    @Test
    public void testCanSignColdWalletNullParticipants() {
        // 冷钱包 + null 列表 → false
        assertFalse(policy.canSign(new BigDecimal("50000"), null));
    }

    @Test
    public void testCanSignNullAmountThrows() { assertThrows(NullPointerException.class, () -> {
        policy.canSign(null, List.of());
        });
    }

    @Test
    public void testGetColdWalletPolicy() {
        ThresholdPolicy cold = policy.getColdWalletPolicy();
        assertEquals(3, cold.getThreshold());
        assertEquals(5, cold.getTotalParticipants());
    }
}