package org.nexus.gateway.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ApprovalExecutionLink#businessId()} 单元测试：推进任务要靠它把链接还原成业务标识，
 * 解析形态必须与 {@link SigningTransfers#requestKey} 严格对偶。
 */
class ApprovalExecutionLinkTest {

    private static ApprovalExecutionLink link(String requestKey) {
        ApprovalExecutionLink link = new ApprovalExecutionLink();
        link.setRequestKey(requestKey);
        return link;
    }

    @Test
    @DisplayName("剥掉 execution:<类型>: 前缀得到业务标识")
    void stripsPrefix() {
        assertEquals("pay_1", link("execution:CHAIN:pay_1").businessId());
        assertEquals("SUB1", link("execution:SUB_AUTH:SUB1").businessId());
    }

    @Test
    @DisplayName("业务标识自带冒号时只剥前两段")
    void keepsInnerColons() {
        assertEquals("SUB1:3", link("execution:SUB_CHARGE:SUB1:3").businessId());
        assertEquals("chain_abc:100", link("execution:CHAIN_REFUND:chain_abc:100").businessId());
    }

    @Test
    @DisplayName("形态不符时回落原值，绝不抛异常")
    void malformedKeyFallsBack() {
        assertEquals("plain-key", link("plain-key").businessId());
        assertEquals("one:segment", link("one:segment").businessId());
        assertNull(link(null).businessId());
    }

    @Test
    @DisplayName("与 requestKey 构造对偶：round-trip 一致")
    void roundTripWithRequestKey() {
        String key = SigningTransfers.requestKey("SUB_CHARGE", "SUB1:2", null);
        assertEquals("SUB1:2", link(key).businessId());
    }
}
