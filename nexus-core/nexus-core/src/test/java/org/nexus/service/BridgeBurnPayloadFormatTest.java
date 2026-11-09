package org.nexus.service;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * B-2 写端点收口：BRIDGE_BURN。
 *
 * <p>支付扩展类型（0x10–0x1a）保留未启用后，burn 不再组装 payload、
 * 不再构造 BRIDGE_BURN 交易，统一返回 5010；空参与金额校验仍先于 5010。</p>
 */
class BridgeBurnPayloadFormatTest {

    private final BridgeService bridgeService = new BridgeService();

    // 用户 from pubkey（32 字节 hex = 64 字符）+ prikey（签名）
    private static final String FROM_PUB = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
    private static final String PRIKEY = "1122334455667788990011223344556677889900112233445566778899001122";

    @Test
    void burnReturnsNotEnabled() {
        var result = bridgeService.burn(FROM_PUB, "ETH", 500L, PRIKEY, 1L);
        assertNotEnabled(result);
    }

    @Test
    void burnZeroAmountRejectedBeforeNotEnabled() {
        var result = bridgeService.burn(FROM_PUB, "ETH", 0L, PRIKEY, 1L);
        assertFail(result, "amount must be greater than 0");
    }

    @Test
    void burnMissingPubkeyRejectedBeforeNotEnabled() {
        var result = bridgeService.burn(null, "ETH", 500L, PRIKEY, 1L);
        assertFail(result, "fromPubkey is required");
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
