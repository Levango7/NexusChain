package org.nexus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * B-2 写端点收口：BRIDGE_LOCK / BRIDGE_MINT。
 *
 * <p>支付扩展类型（0x10–0x1a）保留未启用后，lock/mint 不再组装 payload、
 * 不再构造 BRIDGE_LOCK / BRIDGE_MINT 交易，统一返回 5010；空参与配对校验
 * 仍先于 5010 返回 FAIL（5000）。</p>
 */
class BridgeMintPayloadFormatTest {

    private BridgeService bridgeService;

    @BeforeEach
    void setUp() throws Exception {
        bridgeService = new BridgeService();
        setField(bridgeService, "singleTxLimit", 1_000_000L);
        setField(bridgeService, "minValidators", 0);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void lockReturnsNotEnabled() {
        var result = bridgeService.lock("aa", "ETH", "bb", 10L, "cc", 0L);
        assertNotEnabled(result);
    }

    @Test
    void lockEmptyFromRejectedBeforeNotEnabled() {
        var result = bridgeService.lock("", "ETH", "bb", 10L, "cc", 0L);
        assertFail(result, "fromPubkey is required");
    }

    @Test
    void mintReturnsNotEnabled() {
        var result = bridgeService.mint("br_1", "ETH", "recipient", 10L,
                List.of(), List.of());
        assertNotEnabled(result);
    }

    @Test
    void mintEmptyBridgeTxIdRejectedBeforeNotEnabled() {
        var result = bridgeService.mint("", "ETH", "recipient", 10L,
                List.of(), List.of());
        assertFail(result, "bridgeTxId is required");
    }

    @Test
    void mintPubkeySignaturePairingMismatchRejectedBeforeNotEnabled() {
        var result = bridgeService.mint("br_1", "ETH", "recipient", 10L,
                List.of("aa"), List.of());
        assertFail(result,
                "validator pubkeys (1) must pair one-to-one with signatures (0)");
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
