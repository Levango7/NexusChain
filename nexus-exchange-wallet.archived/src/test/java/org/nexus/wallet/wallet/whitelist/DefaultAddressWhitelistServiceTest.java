package org.nexus.wallet.wallet.whitelist;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.*;

/**
 * {@link DefaultAddressWhitelistService} 单元测试：验证白名单增删查、
 * 首次提币延迟检查与按商户过滤。
 */
public class DefaultAddressWhitelistServiceTest {

    private DefaultAddressWhitelistService service;

    private static final String ADDR = "1A2B3C4D5E6F7G8H9J0KLMNOPQRSTUVWXYZ123";

    @Before
    public void setUp() throws Exception {
        service = new DefaultAddressWhitelistService();
        setField(service, "firstWithdrawalDelayHours", 24L);
    }

    @Test
    public void testAddWhitelist_createsEntryWithDelay() {
        WhitelistEntry entry = service.addWhitelist(ADDR, "Exchange hot wallet", "merchant-1");

        assertNotNull(entry);
        assertEquals(ADDR, entry.getAddress());
        assertEquals("Exchange hot wallet", entry.getLabel());
        assertEquals("merchant-1", entry.getMerchantId());
        assertNotNull(entry.getAddedAt());
        assertNotNull(entry.getFirstWithdrawalAvailableAt());
        assertTrue(entry.getActive());
        // firstWithdrawalAvailableAt 应约等于 now + 24h
        assertTrue(entry.getFirstWithdrawalAvailableAt().isAfter(LocalDateTime.now().plusHours(23)));
    }

    @Test
    public void testIsWhitelisted_afterAdd() {
        assertFalse(service.isWhitelisted(ADDR));
        service.addWhitelist(ADDR, "label", "merchant-1");
        assertTrue(service.isWhitelisted(ADDR));
    }

    @Test
    public void testIsWhitelisted_nullOrEmptyReturnsFalse() {
        assertFalse(service.isWhitelisted(null));
        assertFalse(service.isWhitelisted(""));
    }

    @Test
    public void testRemoveWhitelist_softDeactivate() {
        service.addWhitelist(ADDR, "label", "merchant-1");
        assertTrue(service.isWhitelisted(ADDR));

        service.removeWhitelist(ADDR);

        assertFalse(service.isWhitelisted(ADDR));
    }

    @Test
    public void testRemoveWhitelist_unknownAddressNoError() {
        // 移除不存在的地址不应抛异常
        service.removeWhitelist("1NonExistentAddress000000000000000000000");
    }

    @Test
    public void testCheckFirstTimeWithdrawal_withinDelayReturnsTrue() {
        service.addWhitelist(ADDR, "label", "merchant-1");
        // 刚加入，24h 延迟未到期
        assertTrue(service.checkFirstTimeWithdrawal(ADDR));
    }

    @Test
    public void testCheckFirstTimeWithdrawal_afterDelayReturnsFalse() throws Exception {
        WhitelistEntry entry = service.addWhitelist(ADDR, "label", "merchant-1");
        // 手动将到期时间改为过去，模拟延迟已过
        entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().minusMinutes(1));

        assertFalse(service.checkFirstTimeWithdrawal(ADDR));
    }

    @Test
    public void testCheckFirstTimeWithdrawal_unknownAddressReturnsFalse() {
        assertFalse(service.checkFirstTimeWithdrawal("1UnknownAddress000000000000000000000000"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddWhitelist_invalidShortAddressThrows() {
        service.addWhitelist("short", "label", "merchant-1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddWhitelist_nullMerchantThrows() {
        service.addWhitelist(ADDR, "label", null);
    }

    @Test
    public void testListByMerchant_filtersActiveEntries() {
        service.addWhitelist(ADDR, "a", "merchant-1");
        service.addWhitelist("1AnotherAddress0000000000000000000000000", "b", "merchant-2");

        List<WhitelistEntry> m1 = service.listByMerchant("merchant-1");
        assertEquals(1, m1.size());
        assertEquals(ADDR, m1.get(0).getAddress());

        // 软删除后不再出现在列表中
        service.removeWhitelist(ADDR);
        assertEquals(0, service.listByMerchant("merchant-1").size());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
