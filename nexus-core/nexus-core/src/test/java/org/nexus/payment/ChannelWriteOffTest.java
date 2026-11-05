package org.nexus.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.TransactionPool;
import org.nexus.core.payment.ChannelManager;
import org.nexus.core.payment.ChannelSettlement;
import org.nexus.core.payment.ChannelUpdate;
import org.nexus.core.payment.DisputeResolution;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.service.ChannelSettlementService;
import org.nexus.service.PaymentChannelService;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * B-2 写端点收口：通道 6 个写端点。
 *
 * <p>支付扩展类型（0x10–0x1a）保留未启用后，open/close/cooperative-close/
 * settle/force-expire/unilateral-close 均返回 5010，且被拒后通道状态未变：
 * 保持 OPEN、无 DisputeRecord、无结算记录、无半关闭（CLOSING）。</p>
 */
class ChannelWriteOffTest {

    private ChannelManager channelManager;
    private DisputeResolution disputeResolution;
    private PaymentChannelService channelService;
    private ChannelSettlementService settlementService;

    @BeforeEach
    void setUp() throws Exception {
        channelManager = new ChannelManager();
        disputeResolution = new DisputeResolution(new TransactionPool());
        channelService = new PaymentChannelService();
        settlementService = new ChannelSettlementService();
        setField(settlementService, "channelManager", channelManager);
        setField(settlementService, "disputeResolution", disputeResolution);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
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

    private PaymentChannel newOpenChannel() {
        PaymentChannel channel = channelManager.openChannel("p1", "p2", 1000L, 10);
        assertNotNull(channel);
        assertEquals(PaymentChannel.State.OPEN, channel.getState());
        return channel;
    }

    /** 被拒后：通道仍 OPEN、无争议记录、无结算记录。 */
    private void assertStateUnchanged(String channelId) {
        PaymentChannel channel = channelManager.getChannel(channelId);
        assertNotNull(channel);
        assertEquals(PaymentChannel.State.OPEN, channel.getState());
        assertNull(disputeResolution.getDisputeStatus(channelId));
        assertFalse(settlementService.getSettlementStatus(channelId).getData()
                instanceof ChannelSettlement);
    }

    @Test
    void openChannelReturnsNotEnabled() {
        assertNotEnabled(channelService.openChannel("p1", "p2", 1000L, 10));
    }

    @Test
    void openChannelEmptyFromRejectedBeforeNotEnabled() {
        assertFail(channelService.openChannel("", "p2", 1000L, 10),
                "from address is required");
    }

    @Test
    void closeChannelReturnsNotEnabledAndStateUnchanged() {
        PaymentChannel channel = newOpenChannel();
        String channelId = channel.getChannelId();
        assertNotEnabled(channelService.closeChannel(channelId, 500L, 500L, 0L));
        assertStateUnchanged(channelId);
    }

    @Test
    void closeChannelEmptyIdRejectedBeforeNotEnabled() {
        assertFail(channelService.closeChannel("", 500L, 500L, 0L), "channelId is required");
    }

    @Test
    void cooperativeCloseReturnsNotEnabledAndStateUnchanged() {
        PaymentChannel channel = newOpenChannel();
        String channelId = channel.getChannelId();
        APIResult result = settlementService.cooperativeClose(channelId, 500L, 500L, 0L,
                new byte[ChannelUpdate.SIGNATURE_SIZE], new byte[ChannelUpdate.SIGNATURE_SIZE],
                new byte[32], new byte[32]);
        assertNotEnabled(result);
        assertStateUnchanged(channelId);
    }

    @Test
    void unilateralCloseReturnsNotEnabledAndStateUnchanged() {
        PaymentChannel channel = newOpenChannel();
        String channelId = channel.getChannelId();
        ChannelUpdate update = new ChannelUpdate(channelId, 1L, 500L, 500L,
                new byte[ChannelUpdate.SIGNATURE_SIZE], new byte[ChannelUpdate.SIGNATURE_SIZE],
                System.currentTimeMillis());
        APIResult result = settlementService.unilateralClose(channelId, update,
                new byte[32], new byte[ChannelUpdate.SIGNATURE_SIZE]);
        assertNotEnabled(result);
        assertStateUnchanged(channelId);
    }

    @Test
    void settleChannelReturnsNotEnabledAndStateUnchanged() {
        PaymentChannel channel = newOpenChannel();
        String channelId = channel.getChannelId();
        APIResult result = settlementService.settleChannel(channelId, 100L);
        assertNotEnabled(result);
        assertStateUnchanged(channelId);
    }

    @Test
    void forceExpireReturnsNotEnabledAndStateUnchanged() {
        PaymentChannel channel = newOpenChannel();
        String channelId = channel.getChannelId();
        APIResult result = settlementService.forceExpire(channelId, 100L);
        assertNotEnabled(result);
        assertStateUnchanged(channelId);
    }

    @Test
    void cooperativeCloseEmptyIdRejectedBeforeNotEnabled() {
        assertFail(settlementService.cooperativeClose("", 500L, 500L, 0L,
                new byte[ChannelUpdate.SIGNATURE_SIZE], new byte[ChannelUpdate.SIGNATURE_SIZE],
                new byte[32], new byte[32]), "channelId is required");
    }

    @Test
    void unilateralCloseInvalidSignatureRejectedBeforeNotEnabled() {
        ChannelUpdate update = new ChannelUpdate("ch_x", 1L, 1L, 1L,
                new byte[ChannelUpdate.SIGNATURE_SIZE], new byte[ChannelUpdate.SIGNATURE_SIZE],
                System.currentTimeMillis());
        assertFail(settlementService.unilateralClose("ch_x", update, new byte[32], new byte[16]),
                "Invalid closer signature size");
    }
}
