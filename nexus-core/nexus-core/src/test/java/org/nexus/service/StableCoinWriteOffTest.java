package org.nexus.service;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * B-2 写端点收口测试：StableCoin 的 mint / redeem。
 *
 * <p>支付扩展类型（0x10–0x1a）保留未启用时，两个写端点必须返回
 * {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}（5010），且不再构造
 * MINT_STABLECOIN / REDEEM_STABLECOIN 交易；空参校验仍先于 5010。</p>
 */
class StableCoinWriteOffTest {

    private final StableCoinService service = new StableCoinService();

    @Test
    void mintReturnsNotEnabled() {
        APIResult result = service.mint("aa", 100L, 50L, "bb", 0L);
        assertNotEnabled(result);
    }

    @Test
    void mintEmptyFromRejectedBeforeNotEnabled() {
        APIResult result = service.mint("", 100L, 50L, "bb", 0L);
        assertFail(result, "fromPubkey is required");
    }

    @Test
    void mintNonPositiveCollateralRejectedBeforeNotEnabled() {
        APIResult result = service.mint("aa", 0L, 50L, "bb", 0L);
        assertFail(result, "collateralAmount must be greater than 0");
    }

    @Test
    void redeemReturnsNotEnabled() {
        APIResult result = service.redeem("aa", 10L, "bb", 0L);
        assertNotEnabled(result);
    }

    @Test
    void redeemNonPositiveAmountRejectedBeforeNotEnabled() {
        APIResult result = service.redeem("aa", 0L, "bb", 0L);
        assertFail(result, "redeemAmount must be greater than 0");
    }

    private static void assertNotEnabled(APIResult result) {
        assertEquals(APIResult.PAYMENT_EXTENSION_NOT_ENABLED, result.getCode());
        assertEquals(APIResult.PAYMENT_EXTENSION_NOT_ENABLED_MESSAGE, result.getMessage());
        assertNull(result.getData());
    }

    private static void assertFail(APIResult result, String message) {
        assertEquals(APIResult.FAIL, result.getCode());
        assertEquals(message, result.getMessage());
    }
}
