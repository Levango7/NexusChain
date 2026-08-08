package org.nexus.gateway.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.wallet.WithdrawalRequest;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign fallback 类单元测试：验证降级时返回安全默认值（fail-closed）。
 * 覆盖 BridgeServiceFallback / SigningServiceFallback / WalletMgmtFallback
 * 与三个 FallbackFactory。
 */
class FallbackTest {

    // === BridgeServiceFallback ===

    @Test
    @DisplayName("BridgeServiceFallback: 跨链操作返回 FAILED Map")
    void bridge_crossChainOps_returnFailed() {
        BridgeServiceFallback fb = new BridgeServiceFallback();
        Map<String, Object> lock = fb.lock(Map.of("amount", 100));
        Map<String, Object> mint = fb.mint(Map.of("amount", 100));
        Map<String, Object> burn = fb.burn(Map.of("amount", 100));
        Map<String, Object> unlock = fb.unlock(Map.of("amount", 100));

        for (Map<String, Object> r : new Map[]{lock, mint, burn, unlock}) {
            assertEquals("FAILED", r.get("status"));
            assertEquals("BRIDGE_SERVICE_UNAVAILABLE", r.get("reason"));
        }
    }

    @Test
    @DisplayName("BridgeServiceFallback: 查询操作返回 null")
    void bridge_queryOps_returnNull() {
        BridgeServiceFallback fb = new BridgeServiceFallback();
        assertNull(fb.getTransaction("tx-1"));
        assertNull(fb.getBySourceHash("src-1"));
        assertNull(fb.status());
    }

    // === SigningServiceFallback ===

    @Test
    @DisplayName("SigningServiceFallback: signTransfer/transfer 返回 null，canSignViaMpc 返回 false")
    void signingFallback_failClosed() {
        SigningServiceFallback fb = new SigningServiceFallback();
        assertNull(fb.signTransfer("from", "to", BigDecimal.ONE));
        assertNull(fb.transfer("from", "to", BigDecimal.ONE, "priv"));
        assertFalse(fb.canSignViaMpc(BigDecimal.ONE));
        assertNull(fb.getNoncePool("addr"));
    }

    // === WalletMgmtFallback ===

    @Test
    @DisplayName("WalletMgmtFallback: 地址工具 fail-closed")
    void walletMgmtFallback_failClosed() {
        WalletMgmtFallback fb = new WalletMgmtFallback();
        assertNull(fb.addressToPubkeyHash("NEX-ADDR"));
        assertFalse(fb.verifyAddress("NEX-ADDR"));
        assertFalse(fb.isAddressWhitelisted("NEX-ADDR"));
    }

    @Test
    @DisplayName("WalletMgmtFallback: 提现审批返回 null")
    void walletMgmtFallback_withdrawals_returnNull() {
        WalletMgmtFallback fb = new WalletMgmtFallback();
        assertNull(fb.requestWithdrawal("to", BigDecimal.ONE, "NEX"));
        assertNull(fb.approveWithdrawal("req-1", "approver"));
        assertNull(fb.rejectWithdrawal("req-1", "approver", "fraud"));
        assertNull(fb.executeWithdrawal("req-1"));
        assertNull(fb.getWithdrawal("req-1"));
    }

    @Test
    @DisplayName("WalletMgmtFallback: 托管/冷钱包/白名单返回 null/false")
    void walletMgmtFallback_custodyAndCold_returnNull() {
        WalletMgmtFallback fb = new WalletMgmtFallback();
        assertNull(fb.getCustodyTier("wallet-1"));
        assertNull(fb.depositToCold("addr", BigDecimal.ONE));
        assertNull(fb.withdrawFromCold("addr", BigDecimal.ONE, "approval-1"));
        assertNull(fb.addWhitelist("addr", "label", "merchant-1"));
    }

    // === FallbackFactory ===

    @Test
    @DisplayName("GatewayBridgeServiceFallbackFactory.create 返回 BridgeServiceFallback")
    void bridgeFactory_create() {
        GatewayBridgeServiceFallbackFactory factory = new GatewayBridgeServiceFallbackFactory();
        var fb = factory.create(new RuntimeException("service down"));
        assertNotNull(fb);
        assertEquals("FAILED", fb.lock(Map.of()).get("status"));
    }

    @Test
    @DisplayName("GatewaySigningServiceFallbackFactory.create 返回 SigningServiceFallback")
    void signingFactory_create() {
        GatewaySigningServiceFallbackFactory factory = new GatewaySigningServiceFallbackFactory();
        var fb = factory.create(new RuntimeException("service down"));
        assertNotNull(fb);
        assertNull(fb.signTransfer("from", "to", BigDecimal.ONE));
    }

    @Test
    @DisplayName("GatewayWalletMgmtFallbackFactory.create 返回 WalletMgmtFallback")
    void walletMgmtFactory_create() {
        GatewayWalletMgmtFallbackFactory factory = new GatewayWalletMgmtFallbackFactory();
        var fb = factory.create(new RuntimeException("service down"));
        assertNotNull(fb);
        assertNull(fb.addressToPubkeyHash("addr"));
    }
}