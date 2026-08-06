package org.nexus.walletsvc.whitelist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultAddressWhitelistService} 单元测试（Phase 3 任务 T13）。
 *
 * <p>验证白名单增删查、首次提币延迟检查与按商户过滤。
 * {@link DefaultAddressWhitelistService} 无外部 Feign 依赖，纯内存单元测试；
 * {@code firstWithdrawalDelayHours} 通过反射注入（模拟 {@code @Value} 配置）。</p>
 */
class DefaultAddressWhitelistServiceTest {

    /** 测试用链上地址（长度 34，满足 20-128 校验区间，无空格）。 */
    private static final String ADDR = "1A2B3C4D5E6F7G8H9J0KLMNOPQRSTUVWXYZ123";
    private static final String ADDR_2 = "1AnotherAddress0000000000000000000000000";

    private DefaultAddressWhitelistService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DefaultAddressWhitelistService();
        setField(service, "firstWithdrawalDelayHours", 24L);
    }

    // ==================== addWhitelist ====================

    @Test
    void addWhitelist_createsEntryWithDelay() {
        WhitelistEntry entry = service.addWhitelist(ADDR, "Exchange hot wallet", "merchant-1");

        assertNotNull(entry);
        assertEquals(ADDR, entry.getAddress());
        assertEquals("Exchange hot wallet", entry.getLabel());
        assertEquals("merchant-1", entry.getMerchantId());
        assertNotNull(entry.getAddedAt());
        assertNotNull(entry.getFirstWithdrawalAvailableAt());
        assertTrue(entry.getActive());
        // firstWithdrawalAvailableAt 应约等于 now + 24h（允许 1h 容差）
        assertTrue(entry.getFirstWithdrawalAvailableAt().isAfter(LocalDateTime.now().plusHours(23)));
        assertTrue(entry.getFirstWithdrawalAvailableAt().isBefore(LocalDateTime.now().plusHours(25)));
    }

    @Test
    void addWhitelist_trimsAddressAndMerchantId() {
        WhitelistEntry entry = service.addWhitelist("  " + ADDR + "  ", "label", "  merchant-1  ");

        assertEquals(ADDR, entry.getAddress());
        assertEquals("merchant-1", entry.getMerchantId());
    }

    @Test
    void addWhitelist_nullAddressThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.addWhitelist(null, "label", "merchant-1"));
        assertTrue(ex.getMessage().contains("address is required"));
    }

    @Test
    void addWhitelist_emptyAddressThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addWhitelist("   ", "label", "merchant-1"));
    }

    @Test
    void addWhitelist_shortAddressThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.addWhitelist("short", "label", "merchant-1"));
        assertTrue(ex.getMessage().contains("invalid chain address"));
    }

    @Test
    void addWhitelist_longAddressThrows() {
        // 长度 > 128
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 130; i++) {
            tooLong.append('a');
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.addWhitelist(tooLong.toString(), "label", "merchant-1"));
    }

    @Test
    void addWhitelist_addressWithWhitespaceThrows() {
        // 长度合法但含内部空格
        String withSpace = "1A2B3C4D5E6F7G8H9J0 KLMNOPQRSTUVWXYZ1234";
        assertThrows(IllegalArgumentException.class,
                () -> service.addWhitelist(withSpace, "label", "merchant-1"));
    }

    @Test
    void addWhitelist_nullMerchantThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addWhitelist(ADDR, "label", null));
    }

    @Test
    void addWhitelist_emptyMerchantThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addWhitelist(ADDR, "label", "   "));
    }

    // ==================== isWhitelisted ====================

    @Test
    void isWhitelisted_falseBeforeAdd() {
        assertFalse(service.isWhitelisted(ADDR));
    }

    @Test
    void isWhitelisted_trueAfterAdd() {
        service.addWhitelist(ADDR, "label", "merchant-1");
        assertTrue(service.isWhitelisted(ADDR));
    }

    @Test
    void isWhitelisted_nullReturnsFalse() {
        assertFalse(service.isWhitelisted(null));
    }

    @Test
    void isWhitelisted_emptyReturnsFalse() {
        assertFalse(service.isWhitelisted(""));
        assertFalse(service.isWhitelisted("   "));
    }

    @Test
    void isWhitelisted_unknownAddressReturnsFalse() {
        assertFalse(service.isWhitelisted(ADDR_2));
    }

    @Test
    void isWhitelisted_falseAfterSoftDelete() {
        service.addWhitelist(ADDR, "label", "merchant-1");
        assertTrue(service.isWhitelisted(ADDR));

        service.removeWhitelist(ADDR);

        assertFalse(service.isWhitelisted(ADDR));
    }

    // ==================== removeWhitelist ====================

    @Test
    void removeWhitelist_softDeactivatesEntry() {
        service.addWhitelist(ADDR, "label", "merchant-1");

        service.removeWhitelist(ADDR);

        assertFalse(service.isWhitelisted(ADDR));
    }

    @Test
    void removeWhitelist_unknownAddressNoError() {
        // 移除不存在的地址不应抛异常
        service.removeWhitelist(ADDR_2);
    }

    @Test
    void removeWhitelist_nullAddressThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.removeWhitelist(null));
    }

    @Test
    void removeWhitelist_emptyAddressThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.removeWhitelist("   "));
    }

    // ==================== checkFirstTimeWithdrawal ====================

    @Test
    void checkFirstTimeWithdrawal_withinDelayReturnsTrue() {
        service.addWhitelist(ADDR, "label", "merchant-1");
        // 刚加入，24h 延迟未到期
        assertTrue(service.checkFirstTimeWithdrawal(ADDR));
    }

    @Test
    void checkFirstTimeWithdrawal_afterDelayReturnsFalse() {
        WhitelistEntry entry = service.addWhitelist(ADDR, "label", "merchant-1");
        // 手动将到期时间改为过去，模拟延迟已过
        entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().minusMinutes(1));

        assertFalse(service.checkFirstTimeWithdrawal(ADDR));
    }

    @Test
    void checkFirstTimeWithdrawal_unknownAddressReturnsFalse() {
        assertFalse(service.checkFirstTimeWithdrawal(ADDR_2));
    }

    @Test
    void checkFirstTimeWithdrawal_nullReturnsFalse() {
        assertFalse(service.checkFirstTimeWithdrawal(null));
    }

    @Test
    void checkFirstTimeWithdrawal_emptyReturnsFalse() {
        assertFalse(service.checkFirstTimeWithdrawal(""));
    }

    @Test
    void checkFirstTimeWithdrawal_falseAfterSoftDelete() {
        service.addWhitelist(ADDR, "label", "merchant-1");
        service.removeWhitelist(ADDR);

        assertFalse(service.checkFirstTimeWithdrawal(ADDR));
    }

    @Test
    void checkFirstTimeWithdrawal_nullAvailableAtReturnsFalse() throws Exception {
        WhitelistEntry entry = service.addWhitelist(ADDR, "label", "merchant-1");
        entry.setFirstWithdrawalAvailableAt(null);

        assertFalse(service.checkFirstTimeWithdrawal(ADDR));
    }

    // ==================== listByMerchant ====================

    @Test
    void listByMerchant_filtersActiveEntriesByMerchant() {
        service.addWhitelist(ADDR, "a", "merchant-1");
        service.addWhitelist(ADDR_2, "b", "merchant-2");

        List<WhitelistEntry> m1 = service.listByMerchant("merchant-1");
        assertEquals(1, m1.size());
        assertEquals(ADDR, m1.get(0).getAddress());

        List<WhitelistEntry> m2 = service.listByMerchant("merchant-2");
        assertEquals(1, m2.size());
        assertEquals(ADDR_2, m2.get(0).getAddress());
    }

    @Test
    void listByMerchant_excludesSoftDeleted() {
        service.addWhitelist(ADDR, "a", "merchant-1");
        service.removeWhitelist(ADDR);

        assertEquals(0, service.listByMerchant("merchant-1").size());
    }

    @Test
    void listByMerchant_nullMerchantReturnsEmpty() {
        service.addWhitelist(ADDR, "a", "merchant-1");
        assertEquals(0, service.listByMerchant(null).size());
    }

    @Test
    void listByMerchant_emptyMerchantReturnsEmpty() {
        service.addWhitelist(ADDR, "a", "merchant-1");
        assertEquals(0, service.listByMerchant("   ").size());
    }

    @Test
    void listByMerchant_trimsMerchantId() {
        service.addWhitelist(ADDR, "a", "merchant-1");

        List<WhitelistEntry> result = service.listByMerchant("  merchant-1  ");
        assertEquals(1, result.size());
    }

    // ==================== helpers ====================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}