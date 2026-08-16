package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BlacklistRule 配置加载测试（此前 blacklist 无数据源注入——规则空转不生效）。
 */
class BlacklistRuleTest {

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private RiskTransaction tx(String payer, String payee) {
        RiskTransaction t = new RiskTransaction();
        t.setPayerAddress(payer);
        t.setPayeeAddress(payee);
        t.setAmount(new java.math.BigDecimal("100"));
        t.setCurrency("NEX");
        return t;
    }

    @Test
    void loadsFromConfig_onPostConstruct() throws Exception {
        BlacklistRule rule = new BlacklistRule();
        setField(rule, "blacklistConfig", "0xabc123,0xdef456 , 0xghi789");
        rule.loadBlacklist();
        assertEquals(3, rule.getBlacklist().size(), "应从配置加载 3 个黑名单地址");
        assertTrue(rule.getBlacklist().contains("0xabc123"), "应含配置地址");
    }

    @Test
    void blacklistedPayer_rejected() throws Exception {
        BlacklistRule rule = new BlacklistRule();
        setField(rule, "blacklistConfig", "0xabc123");
        rule.loadBlacklist();
        assertTrue(rule.check(tx("0xabc123", "0xgood")), "黑名单付款方应命中");
        assertTrue(rule.check(tx("0xgood", "0xabc123")), "黑名单收款方应命中");
    }

    @Test
    void nonBlacklisted_passed() throws Exception {
        BlacklistRule rule = new BlacklistRule();
        setField(rule, "blacklistConfig", "0xabc123");
        rule.loadBlacklist();
        assertFalse(rule.check(tx("0xgood1", "0xgood2")), "非黑名单地址应通过");
    }

    @Test
    void noConfig_ruleInactive_notMatch() throws Exception {
        BlacklistRule rule = new BlacklistRule();
        setField(rule, "blacklistConfig", "");
        rule.loadBlacklist();  // 未配置 → 空黑名单
        assertFalse(rule.check(tx("0xabc123", "0xgood")),
                "未配置时规则不生效（空黑名单不命中）——诚实行为");
    }
}
