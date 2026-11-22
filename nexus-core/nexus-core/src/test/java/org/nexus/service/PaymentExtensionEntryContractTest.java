package org.nexus.service;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.payment.BatchTransferPayload.TransferItem;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 入口契约测试：支付扩展 0x10–0x1a“保留未启用”语义固化。
 *
 * <p>所有写端点在合法入参下统一返回 5010 + 固定消息（不构造交易、不改状态）；
 * 读端点仍可用。本测试只固化现状，不改变生产行为、不触碰链上门禁。</p>
 */
class PaymentExtensionEntryContractTest {

    @Test
    void allWriteEndpointsReturnStableNotEnabledCode() throws Exception {
        BridgeService bridge = new BridgeService();
        // @Value 默认配置由 Spring 注入；手工 new 时显式设置镜像生产默认值。
        setField(bridge, "singleTxLimit", 1_000_000_000L);
        setField(bridge, "dailyLimit", 10_000_000_000L);
        setField(bridge, "timelockDuration", 3600L);
        setField(bridge, "minValidators", 3);
        StableCoinService stableCoin = new StableCoinService();
        PaymentChannelService channel = new PaymentChannelService();
        BatchTransferService batch = new BatchTransferService();
        ChannelSettlementService settlement = new ChannelSettlementService();

        Map<String, APIResult> writes = new LinkedHashMap<>();
        writes.put("bridge.lock", bridge.lock("aa", "ETH", "bb", 10L, "cc", 0L));
        writes.put("bridge.mint", bridge.mint("br-1", "ETH", "recipient", 10L,
                List.of("pk1", "pk2", "pk3"), List.of("s1", "s2", "s3")));
        writes.put("bridge.burn", bridge.burn("aa", "ETH", 10L, "cc", 0L));
        writes.put("bridge.getStatus", bridge.getStatus("hash"));
        writes.put("stableCoin.mint", stableCoin.mint("aa", 1L, 1L, "key", 1L));
        writes.put("stableCoin.redeem", stableCoin.redeem("aa", 1L, "key", 1L));
        writes.put("stableCoin.getCollateralRatio", stableCoin.getCollateralRatio("aa"));
        writes.put("channel.openChannel", channel.openChannel("a", "b", 1L, 0));
        writes.put("channel.closeChannel", channel.closeChannel("c", 1L, 1L, 0L));
        writes.put("channel.getChannelState", channel.getChannelState("c"));
        writes.put("batch.batchTransfer", batch.batchTransfer("aa", List.of(new TransferItem("a", 1L)), "key", 1L));
        writes.put("batch.getBatchStatus", batch.getBatchStatus("hash"));
        writes.put("settlement.cooperativeClose", settlement.cooperativeClose("c", 1L, 1L, 0L,
                new byte[64], new byte[64], new byte[32], new byte[32]));
        writes.put("settlement.settleChannel", settlement.settleChannel("c", 1L));
        writes.put("settlement.forceExpire", settlement.forceExpire("c", 1L));

        for (Map.Entry<String, APIResult> entry : writes.entrySet()) {
            assertNotEnabled(entry.getValue(), entry.getKey());
        }
    }

    @Test
    void readEndpointsRemainAvailable() throws Exception {
        // @Value 默认配置由 Spring 注入；手工 new 时显式设置镜像生产默认值。
        StableCoinService stableCoin = new StableCoinService();
        setField(stableCoin, "symbol", "NEX");
        setField(stableCoin, "pegPrice", "1.00");
        setField(stableCoin, "priceSource", "PEG");
        APIResult price = stableCoin.getPrice();
        assertEquals(APIResult.SUCCESS, price.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> priceData = (Map<String, Object>) price.getData();
        assertEquals("NEX", priceData.get("symbol"));
        assertEquals("1.00", priceData.get("price"));
        assertEquals("PEG", priceData.get("source"));
        assertNotNull(priceData.get("timestamp"));

        BridgeService bridge = new BridgeService();
        setField(bridge, "singleTxLimit", 1_000_000_000L);
        setField(bridge, "dailyLimit", 10_000_000_000L);
        setField(bridge, "timelockDuration", 3600L);
        setField(bridge, "minValidators", 3);
        APIResult limit = bridge.getLimit();
        assertEquals(APIResult.SUCCESS, limit.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> limitData = (Map<String, Object>) limit.getData();
        assertEquals(1_000_000_000L, ((Number) limitData.get("singleTxLimit")).longValue());
        assertEquals(10_000_000_000L, ((Number) limitData.get("dailyLimit")).longValue());
        assertEquals(3600L, ((Number) limitData.get("timelockDuration")).longValue());

        APIResult channels = new PaymentChannelService().listChannelsByAddress("a");
        assertEquals(APIResult.SUCCESS, channels.getCode());
        assertTrue(((List<?>) channels.getData()).isEmpty());
    }

    private static void assertNotEnabled(APIResult result, String label) {
        assertEquals(APIResult.PAYMENT_EXTENSION_NOT_ENABLED, result.getCode(), label);
        assertEquals(APIResult.PAYMENT_EXTENSION_NOT_ENABLED_MESSAGE, result.getMessage(), label);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
