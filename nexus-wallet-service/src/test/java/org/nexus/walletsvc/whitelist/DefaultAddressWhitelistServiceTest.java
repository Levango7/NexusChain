package org.nexus.walletsvc.whitelist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.walletsvc.entity.WhitelistEntryEntity;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultAddressWhitelistService} 单元测试（Phase 3 任务 T13，Phase 4 任务 T9 改造）。
 *
 * <p>验证白名单增删查、首次提币延迟检查与按商户过滤。
 * {@code firstWithdrawalDelayHours} 通过反射注入（模拟 {@code @Value} 配置）。</p>
 *
 * <p><strong>Phase 4 任务 T9 改造</strong>（设计文档 §4.6.1）：
 * 原直接操作 {@code ConcurrentHashMap} 内存字段，现改为 Mock
 * {@link WhitelistEntryRepository}。用内部 {@link ConcurrentHashMap} + Mockito
 * {@code Answer} 模拟真实 Repository 行为（save → 存入 Map，
 * existsByAddress / existsByAddressAndActiveTrue / findByAddress → 查 Map），
 * 使测试断言不变（行为契约保持），仅调整 Arrange 阶段。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultAddressWhitelistServiceTest {

    /** 测试用链上地址（长度 34，满足 20-128 校验区间，无空格）。 */
    private static final String ADDR = "1A2B3C4D5E6F7G8H9J0KLMNOPQRSTUVWXYZ123";
    private static final String ADDR_2 = "1AnotherAddress0000000000000000000000000";

    @Mock private WhitelistEntryRepository whitelistEntryRepository;

    /** 模拟 address_whitelist 表的内存存储（address → entity）。 */
    private final Map<String, WhitelistEntryEntity> store = new ConcurrentHashMap<>();

    private DefaultAddressWhitelistService service;

    @BeforeEach
    void setUp() throws Exception {
        store.clear();

        // Mock Repository：用 Answer 委托到内部 Map，模拟真实 JPA Repository 行为
        when(whitelistEntryRepository.existsByAddress(anyString())).thenAnswer(inv -> {
            String addr = inv.getArgument(0);
            return store.containsKey(addr);
        });
        when(whitelistEntryRepository.existsByAddressAndActiveTrue(anyString())).thenAnswer(inv -> {
            String addr = inv.getArgument(0);
            WhitelistEntryEntity e = store.get(addr);
            return e != null && Boolean.TRUE.equals(e.getActive());
        });
        when(whitelistEntryRepository.findByAddress(anyString())).thenAnswer(inv -> {
            String addr = inv.getArgument(0);
            return Optional.ofNullable(store.get(addr));
        });
        when(whitelistEntryRepository.findByMerchantIdAndActiveTrue(anyString())).thenAnswer(inv -> {
            String merchantId = inv.getArgument(0);
            List<WhitelistEntryEntity> result = new ArrayList<>();
            for (WhitelistEntryEntity e : store.values()) {
                if (merchantId.equals(e.getMerchantId()) && Boolean.TRUE.equals(e.getActive())) {
                    result.add(e);
                }
            }
            return result;
        });
        when(whitelistEntryRepository.save(any(WhitelistEntryEntity.class))).thenAnswer(inv -> {
            WhitelistEntryEntity entity = inv.getArgument(0);
            store.put(entity.getAddress(), entity);
            return entity;
        });

        service = new DefaultAddressWhitelistService(whitelistEntryRepository);
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
        service.addWhitelist(ADDR, "label", "merchant-1");
        // 手动将到期时间改为过去，模拟延迟已过（操作存储中的 Entity）
        WhitelistEntryEntity entity = whitelistEntryRepository.findByAddress(ADDR).orElseThrow();
        entity.setFirstWithdrawalAvailableAt(LocalDateTime.now().minusMinutes(1));

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
    void checkFirstTimeWithdrawal_nullAvailableAtReturnsFalse() {
        service.addWhitelist(ADDR, "label", "merchant-1");
        // 操作存储中的 Entity，将 firstWithdrawalAvailableAt 置 null
        WhitelistEntryEntity entity = whitelistEntryRepository.findByAddress(ADDR).orElseThrow();
        entity.setFirstWithdrawalAvailableAt(null);

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
