package org.nexus.service;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 支付扩展（0x10–0x1a）读路径收口测试。
 *
 * <p>裁决见 {@code docs/decision-payment-extension-onchain.md} §9：B-1 要求
 * 4 处读接口在未启用时返回稳定错误码 5010，而不是模拟的 OPEN / LOCKED /
 * 零仓位 / UNKNOWN 数据。路由与签名保持不变。</p>
 */
public class PaymentExtensionReadPathTest {

    @Test
    void channelStateReturnsNotEnabled() {
        APIResult result = new PaymentChannelService().getChannelState("ch_test");
        assertNotEnabled(result);
    }

    @Test
    void bridgeStatusReturnsNotEnabled() {
        APIResult result = new BridgeService().getStatus("0xabc");
        assertNotEnabled(result);
    }

    @Test
    void stableCoinPositionReturnsNotEnabled() {
        APIResult result = new StableCoinService().getCollateralRatio("nex_test");
        assertNotEnabled(result);
    }

    @Test
    void batchStatusReturnsNotEnabled() {
        APIResult result = new BatchTransferService().getBatchStatus("0xabc");
        assertNotEnabled(result);
    }

    @Test
    void emptyInputIsRejectedBeforeNotEnabled() {
        APIResult result = new PaymentChannelService().getChannelState("");
        assertEquals(APIResult.FAIL, result.getCode());
        assertEquals("channelId is required", result.getMessage());
    }

    private static void assertNotEnabled(APIResult result) {
        assertEquals(APIResult.PAYMENT_EXTENSION_NOT_ENABLED, result.getCode());
        assertEquals(APIResult.PAYMENT_EXTENSION_NOT_ENABLED_MESSAGE, result.getMessage());
        assertNull(result.getData());
    }
}
