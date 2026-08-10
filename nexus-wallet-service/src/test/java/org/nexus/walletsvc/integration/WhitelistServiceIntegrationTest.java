package org.nexus.walletsvc.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.walletsvc.entity.WhitelistEntryEntity;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;
import org.nexus.walletsvc.whitelist.AddressWhitelistService;
import org.nexus.walletsvc.whitelist.WhitelistEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AddressWhitelistService} 集成测试（Phase 4 任务 #74，设计文档 §4.6.2 / §4.4.2）。
 *
 * <p>使用 {@code @SpringBootTest} + H2 内存数据库，验证
 * {@code DefaultAddressWhitelistService} 的完整流程：
 * addWhitelist → isWhitelisted → removeWhitelist → isWhitelisted，
 * 确认数据库中软删除（active=false）正确生效
 * （替代原 ConcurrentHashMap 内存存储）。</p>
 *
 * <p>验证要点：
 * <ul>
 *   <li>addWhitelist：持久化到 address_whitelist 表，firstWithdrawalAvailableAt = now + delay</li>
 *   <li>isWhitelisted：查询 active=true 的记录</li>
 *   <li>removeWhitelist：软删除（active=false），记录仍存在</li>
 *   <li>checkFirstTimeWithdrawal：延迟期内返回 true</li>
 *   <li>地址唯一约束：重复添加抛 IllegalStateException</li>
 *   <li>@Transactional 事务边界：写操作正确提交</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class WhitelistServiceIntegrationTest {

    @Autowired
    private AddressWhitelistService whitelistService;

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;

    /** 测试用白名单地址（长度 ≥ 20，满足 DefaultAddressWhitelistService.isValidAddress 校验）。 */
    private static final String TEST_ADDRESS = "0xwhitelistIntegrationTest123456";
    private static final String TEST_MERCHANT = "merchant-it-whitelist";

    @Test
    @DisplayName("addWhitelist: 持久化到数据库并返回 DTO")
    void addWhitelist_persistedToDatabase() {
        WhitelistEntry entry = whitelistService.addWhitelist(TEST_ADDRESS, "Test label", TEST_MERCHANT);

        assertNotNull(entry);
        assertEquals(TEST_ADDRESS, entry.getAddress());
        assertEquals(TEST_MERCHANT, entry.getMerchantId());
        assertTrue(entry.getActive());
        assertNotNull(entry.getAddedAt());
        assertNotNull(entry.getFirstWithdrawalAvailableAt());

        // 通过 Repository 验证数据库持久化
        WhitelistEntryEntity entity = whitelistEntryRepository.findByAddress(TEST_ADDRESS).orElseThrow();
        assertEquals(TEST_MERCHANT, entity.getMerchantId());
        assertTrue(entity.getActive());
    }

    @Test
    @DisplayName("isWhitelisted: 活跃地址返回 true")
    void isWhitelisted_activeAddressReturnsTrue() {
        whitelistService.addWhitelist("0xwhitelistActiveCheck1234567890", "Active", TEST_MERCHANT);

        assertTrue(whitelistService.isWhitelisted("0xwhitelistActiveCheck1234567890"));
    }

    @Test
    @DisplayName("isWhitelisted: 不存在地址返回 false")
    void isWhitelisted_nonExistentReturnsFalse() {
        assertFalse(whitelistService.isWhitelisted("0xnonExistentAddress123456789012"));
    }

    @Test
    @DisplayName("isWhitelisted: null/空地址返回 false")
    void isWhitelisted_nullOrEmptyReturnsFalse() {
        assertFalse(whitelistService.isWhitelisted(null));
        assertFalse(whitelistService.isWhitelisted(""));
        assertFalse(whitelistService.isWhitelisted("   "));
    }

    @Test
    @DisplayName("removeWhitelist: 软删除后 isWhitelisted=false 但记录仍存在")
    void removeWhitelist_softDeleteMakesInactive() {
        String addr = "0xsoftDeleteIntegrationTest12345678";
        whitelistService.addWhitelist(addr, "To be removed", TEST_MERCHANT);
        assertTrue(whitelistService.isWhitelisted(addr));

        whitelistService.removeWhitelist(addr);

        // isWhitelisted 返回 false（active=false）
        assertFalse(whitelistService.isWhitelisted(addr));

        // 但记录仍存在于数据库（软删除）
        WhitelistEntryEntity entity = whitelistEntryRepository.findByAddress(addr).orElseThrow();
        assertFalse(entity.getActive(), "软删除后 active 应为 false");
        assertNotNull(entity.getUpdatedAt(), "updatedAt 应被 @PreUpdate 维护");
    }

    @Test
    @DisplayName("removeWhitelist: 不存在地址不抛异常")
    void removeWhitelist_nonExistentNoOp() {
        // 不存在的地址 → log warn + return（不抛异常）
        whitelistService.removeWhitelist("0xnonExistentRemoveTarget12345678");
        // 无异常即通过
    }

    @Test
    @DisplayName("addWhitelist: 重复地址抛 IllegalStateException")
    void addWhitelist_duplicateAddressThrows() {
        String addr = "0xduplicateAddressTest123456789012";
        whitelistService.addWhitelist(addr, "First add", TEST_MERCHANT);

        assertThrows(IllegalStateException.class,
                () -> whitelistService.addWhitelist(addr, "Second add", TEST_MERCHANT));
    }

    @Test
    @DisplayName("addWhitelist: 无效地址抛 IllegalArgumentException")
    void addWhitelist_invalidAddressThrows() {
        // null
        assertThrows(IllegalArgumentException.class,
                () -> whitelistService.addWhitelist(null, "label", TEST_MERCHANT));
        // 空字符串
        assertThrows(IllegalArgumentException.class,
                () -> whitelistService.addWhitelist("", "label", TEST_MERCHANT));
        // 空白
        assertThrows(IllegalArgumentException.class,
                () -> whitelistService.addWhitelist("   ", "label", TEST_MERCHANT));
        // 长度不足（< 20）
        assertThrows(IllegalArgumentException.class,
                () -> whitelistService.addWhitelist("0xshort", "label", TEST_MERCHANT));
        // 含空格
        assertThrows(IllegalArgumentException.class,
                () -> whitelistService.addWhitelist("0xaddress with space12345678", "label", TEST_MERCHANT));
    }

    @Test
    @DisplayName("addWhitelist: 缺少 merchantId 抛 IllegalArgumentException")
    void addWhitelist_missingMerchantIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> whitelistService.addWhitelist("0xvalidAddress123456789012345", "label", null));
        assertThrows(IllegalArgumentException.class,
                () -> whitelistService.addWhitelist("0xvalidAddress123456789012345", "label", ""));
        assertThrows(IllegalArgumentException.class,
                () -> whitelistService.addWhitelist("0xvalidAddress123456789012345", "label", "  "));
    }

    @Test
    @DisplayName("checkFirstTimeWithdrawal: 延迟期内返回 true")
    void checkFirstTimeWithdrawal_delayInEffect() {
        // 默认延迟 24 小时，刚添加的地址延迟期内
        String addr = "0xfirstTimeDelayTest12345678901234";
        whitelistService.addWhitelist(addr, "First time", TEST_MERCHANT);

        assertTrue(whitelistService.checkFirstTimeWithdrawal(addr),
                "刚添加的地址应在首次提币延迟期内");
    }

    @Test
    @DisplayName("checkFirstTimeWithdrawal: 不存在地址返回 false")
    void checkFirstTimeWithdrawal_nonExistentReturnsFalse() {
        assertFalse(whitelistService.checkFirstTimeWithdrawal("0xnonExistentFirstTime12345678"));
    }

    @Test
    @DisplayName("完整流程: add → isWhitelisted → remove → isWhitelisted")
    void fullWorkflow_addCheckRemoveCheck() {
        String addr = "0xfullWorkflowTestAddress1234567890";

        // 1. add
        whitelistService.addWhitelist(addr, "Full workflow", TEST_MERCHANT);
        assertTrue(whitelistService.isWhitelisted(addr), "添加后应已加白");

        // 2. remove（软删除）
        whitelistService.removeWhitelist(addr);
        assertFalse(whitelistService.isWhitelisted(addr), "移除后应未加白");

        // 3. 数据库中记录仍存在（active=false）
        WhitelistEntryEntity entity = whitelistEntryRepository.findByAddress(addr).orElseThrow();
        assertFalse(entity.getActive());
    }
}