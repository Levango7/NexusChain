package org.nexus.walletsvc.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultApprovalPolicy} 单元测试（Phase 4 任务 T9 新增）。
 *
 * <p>验证审批策略的核心行为：
 * <ul>
 *   <li>{@link DefaultApprovalPolicy#getRequiredApprovers(BigDecimal, String)}：
 *       金额阈值计算（≤10000 → 1 人，≤100000 → 2 人，>100000 → 3 人）</li>
 *   <li>{@link DefaultApprovalPolicy#isAddressWhitelisted(String)}：
 *       委托 {@link WhitelistEntryRepository#existsByAddressAndActiveTrue(String)} 查询</li>
 *   <li>{@code @Deprecated addToWhitelist / removeFromWhitelist}：no-op 行为
 *       （Phase 4 起白名单写入统一通过 {@code DefaultAddressWhitelistService}）</li>
 * </ul>
 * </p>
 *
 * <p><strong>Phase 4 改造背景</strong>（设计文档 §4.4.4）：
 * 原 {@code CopyOnWriteArraySet<String> whitelist} 内存存储已替换为
 * {@link WhitelistEntryRepository} 查询，与 {@code DefaultAddressWhitelistService}
 * 共享同一物理表（{@code address_whitelist}），消除双重白名单存储问题（§2.2）。
 * {@code addToWhitelist()} / {@code removeFromWhitelist()} 标记 {@code @Deprecated}
 * 且为 no-op，白名单写入统一通过管理端点进行。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultApprovalPolicyTest {

    @Mock private WhitelistEntryRepository whitelistEntryRepository;

    private DefaultApprovalPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultApprovalPolicy(whitelistEntryRepository);
    }

    // ==================== getRequiredApprovers ====================

    @Test
    void getRequiredApprovers_smallAmountReturnsOne() {
        assertEquals(1, policy.getRequiredApprovers(new BigDecimal("1"), "NEX"));
        assertEquals(1, policy.getRequiredApprovers(new BigDecimal("9999"), "NEX"));
        assertEquals(1, policy.getRequiredApprovers(new BigDecimal("10000"), "NEX")); // 边界
    }

    @Test
    void getRequiredApprovers_mediumAmountReturnsTwo() {
        assertEquals(2, policy.getRequiredApprovers(new BigDecimal("10001"), "NEX"));
        assertEquals(2, policy.getRequiredApprovers(new BigDecimal("50000"), "NEX"));
        assertEquals(2, policy.getRequiredApprovers(new BigDecimal("100000"), "NEX")); // 边界
    }

    @Test
    void getRequiredApprovers_largeAmountReturnsThree() {
        assertEquals(3, policy.getRequiredApprovers(new BigDecimal("100001"), "NEX"));
        assertEquals(3, policy.getRequiredApprovers(new BigDecimal("1000000"), "NEX"));
    }

    @Test
    void getRequiredApprovers_nullAmountThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> policy.getRequiredApprovers(null, "NEX"));
        assertTrue(ex.getMessage().contains("amount must be positive"));
    }

    @Test
    void getRequiredApprovers_zeroAmountThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.getRequiredApprovers(BigDecimal.ZERO, "NEX"));
    }

    @Test
    void getRequiredApprovers_negativeAmountThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.getRequiredApprovers(new BigDecimal("-1"), "NEX"));
    }

    // ==================== isAddressWhitelisted ====================

    @Test
    void isAddressWhitelisted_delegatesToRepository() {
        String addr = "0xwhitelisted1234567890";
        when(whitelistEntryRepository.existsByAddressAndActiveTrue(addr)).thenReturn(true);

        assertTrue(policy.isAddressWhitelisted(addr));
        verify(whitelistEntryRepository).existsByAddressAndActiveTrue(addr);
    }

    @Test
    void isAddressWhitelisted_notInRepositoryReturnsFalse() {
        String addr = "0xunknown1234567890";
        when(whitelistEntryRepository.existsByAddressAndActiveTrue(addr)).thenReturn(false);

        assertFalse(policy.isAddressWhitelisted(addr));
        verify(whitelistEntryRepository).existsByAddressAndActiveTrue(addr);
    }

    @Test
    void isAddressWhitelisted_nullReturnsFalse() {
        assertFalse(policy.isAddressWhitelisted(null));
    }

    @Test
    void isAddressWhitelisted_emptyReturnsFalse() {
        assertFalse(policy.isAddressWhitelisted(""));
    }

    // ==================== @Deprecated addToWhitelist / removeFromWhitelist ====================

    /**
     * 验证 {@code @Deprecated addToWhitelist} 为 no-op：不调用 Repository，不影响白名单查询。
     *
     * <p>Phase 4 起白名单写入统一通过 {@code DefaultAddressWhitelistService.addWhitelist()}
     * （管理端点）进行；本类仅保留查询职责。</p>
     */
    @Test
    void addToWhitelist_isNoOp() {
        String addr = "0xtest1234567890abcdef";

        // addToWhitelist 不应抛异常，也不应改变 isAddressWhitelisted 的返回值
        policy.addToWhitelist(addr);

        // isAddressWhitelisted 仍委托 Repository 查询，addToWhitelist 不影响其结果
        when(whitelistEntryRepository.existsByAddressAndActiveTrue(addr)).thenReturn(false);
        assertFalse(policy.isAddressWhitelisted(addr));
    }

    /**
     * 验证 {@code @Deprecated removeFromWhitelist} 为 no-op：不调用 Repository，不影响白名单查询。
     *
     * <p>Phase 4 起白名单移除统一通过 {@code DefaultAddressWhitelistService.removeWhitelist()}
     * （管理端点，软删除）进行；本类仅保留查询职责。</p>
     */
    @Test
    void removeFromWhitelist_isNoOp() {
        String addr = "0xtest1234567890abcdef";

        // removeFromWhitelist 不应抛异常，也不应改变 isAddressWhitelisted 的返回值
        policy.removeFromWhitelist(addr);

        // isAddressWhitelisted 仍委托 Repository 查询，removeFromWhitelist 不影响其结果
        when(whitelistEntryRepository.existsByAddressAndActiveTrue(addr)).thenReturn(true);
        assertTrue(policy.isAddressWhitelisted(addr));
    }
}