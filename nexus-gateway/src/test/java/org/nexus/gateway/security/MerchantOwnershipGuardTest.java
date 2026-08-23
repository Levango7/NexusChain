package org.nexus.gateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MerchantOwnershipGuard} 单元测试（P0-4 IDOR 防护核心逻辑）。
 */
class MerchantOwnershipGuardTest {

    private final MerchantOwnershipGuard guard = new MerchantOwnershipGuard();

    @Test
    @DisplayName("requireMerchantId：Long 属性正常解析")
    void requireMerchantId_resolvesLongAttribute() {
        jakarta.servlet.http.HttpServletRequest request =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getAttribute(MerchantOwnershipGuard.MERCHANT_ID_ATTR))
                .thenReturn(100L);

        assertEquals(100L, guard.requireMerchantId(request));
    }

    @Test
    @DisplayName("requireMerchantId：字符串数字属性可解析")
    void requireMerchantId_parsesStringAttribute() {
        jakarta.servlet.http.HttpServletRequest request =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getAttribute(MerchantOwnershipGuard.MERCHANT_ID_ATTR))
                .thenReturn("100");

        assertEquals(100L, guard.requireMerchantId(request));
    }

    @Test
    @DisplayName("fail-closed：属性缺失时拒绝，不得放行")
    void requireMerchantId_failsClosedWhenMissing() {
        jakarta.servlet.http.HttpServletRequest request =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getAttribute(MerchantOwnershipGuard.MERCHANT_ID_ATTR))
                .thenReturn(null);

        assertThrows(MerchantOwnershipException.class, () -> guard.requireMerchantId(request));
    }

    @Test
    @DisplayName("fail-closed：属性非数字时拒绝")
    void requireMerchantId_failsClosedWhenUnparseable() {
        jakarta.servlet.http.HttpServletRequest request =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getAttribute(MerchantOwnershipGuard.MERCHANT_ID_ATTR))
                .thenReturn("not-a-number");

        assertThrows(MerchantOwnershipException.class, () -> guard.requireMerchantId(request));
    }

    @Test
    @DisplayName("requireOwned：属主访问通过")
    void requireOwned_allowsOwner() {
        assertDoesNotThrow(() -> guard.requireOwned(100L, 100L, "order", 1L));
    }

    @Test
    @DisplayName("requireOwned：跨商户访问拒绝")
    void requireOwned_rejectsNonOwner() {
        assertThrows(MerchantOwnershipException.class,
                () -> guard.requireOwned(200L, 100L, "order", 1L));
    }

    @Test
    @DisplayName("requireOwned：资源无属主信息时拒绝（不默认放行）")
    void requireOwned_rejectsWhenOwnerUnknown() {
        assertThrows(MerchantOwnershipException.class,
                () -> guard.requireOwned(100L, null, "order", 1L));
    }
}
