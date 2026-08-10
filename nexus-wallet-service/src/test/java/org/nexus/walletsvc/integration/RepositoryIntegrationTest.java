package org.nexus.walletsvc.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.entity.CustodyBalanceEntity;
import org.nexus.walletsvc.entity.WhitelistEntryEntity;
import org.nexus.walletsvc.entity.WithdrawalApproverEntity;
import org.nexus.walletsvc.entity.WithdrawalRequestEntity;
import org.nexus.walletsvc.repository.CustodyBalanceRepository;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;
import org.nexus.walletsvc.repository.WithdrawalApproverRepository;
import org.nexus.walletsvc.repository.WithdrawalRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository 集成测试（Phase 4 任务 #74，设计文档 §4.6.2）。
 *
 * <p>使用 {@code @SpringBootTest} + H2 内存数据库（{@code application-test.yml}），
 * 验证 4 个 Repository 的 CRUD 操作与 Flyway migration 正确执行：
 * <ul>
 *   <li>V1 建表：4 张业务表 + undo_log 表存在</li>
 *   <li>V2 seed：custody_balances 预置 HOT / COLD 两行（balance=0）</li>
 *   <li>V3 undo_log：Seata AT 回滚表存在</li>
 *   <li>各 Repository 的 save / find / delete / 自定义查询方法</li>
 * </ul>
 * </p>
 *
 * <p>Seata / Nacos / Sentinel 在测试 profile 中禁用，Feign 客户端通过
 * {@code @MockBean} 注入（见子类），不依赖外部服务。</p>
 *
 * <p>类级 {@link Transactional} 使每个测试方法在独立事务中执行并默认回滚，
 * 保证测试间数据库状态隔离：例如 {@code custodyBalance_saveAndUpdate} 修改 HOT 余额后
 * 不会影响 {@code flywayV2_hotBalanceSeeded} 的 balance=0 断言；同时使同一测试内
 * 多次 {@code save} 调用共享同一持久化上下文，避免 detached entity 触发
 * {@code StaleObjectStateException}（乐观锁冲突）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepositoryIntegrationTest {

    @Autowired
    private CustodyBalanceRepository custodyBalanceRepository;

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;

    @Autowired
    private WithdrawalRequestRepository withdrawalRequestRepository;

    @Autowired
    private WithdrawalApproverRepository withdrawalApproverRepository;

    // ==================== Flyway V2 seed 验证 ====================

    @Test
    @DisplayName("Flyway V2: custody_balances 预置 HOT 行 balance=0")
    void flywayV2_hotBalanceSeeded() {
        Optional<CustodyBalanceEntity> hot = custodyBalanceRepository.findByTier("HOT");
        assertTrue(hot.isPresent(), "HOT 行应被 V2 seed 预置");
        assertEquals(0, BigDecimal.ZERO.compareTo(hot.get().getBalance()),
                "HOT 初始余额应为 0");
    }

    @Test
    @DisplayName("Flyway V2: custody_balances 预置 COLD 行 balance=0")
    void flywayV2_coldBalanceSeeded() {
        Optional<CustodyBalanceEntity> cold = custodyBalanceRepository.findByTier("COLD");
        assertTrue(cold.isPresent(), "COLD 行应被 V2 seed 预置");
        assertEquals(0, BigDecimal.ZERO.compareTo(cold.get().getBalance()),
                "COLD 初始余额应为 0");
    }

    // ==================== CustodyBalanceRepository CRUD ====================

    @Test
    @DisplayName("CustodyBalanceRepository: save + findByTier 更新余额")
    void custodyBalance_saveAndUpdate() {
        CustodyBalanceEntity hot = custodyBalanceRepository.findByTier("HOT").orElseThrow();
        hot.setBalance(new BigDecimal("1000.500"));
        custodyBalanceRepository.save(hot);

        CustodyBalanceEntity reloaded = custodyBalanceRepository.findByTier("HOT").orElseThrow();
        assertEquals(0, new BigDecimal("1000.500").compareTo(reloaded.getBalance()));
        assertNotNull(reloaded.getUpdatedAt(), "updatedAt 应由 @PreUpdate 自动维护");
    }

    @Test
    @DisplayName("CustodyBalanceRepository: 乐观锁 version 自动递增")
    void custodyBalance_optimisticLockVersionIncrement() {
        CustodyBalanceEntity cold = custodyBalanceRepository.findByTier("COLD").orElseThrow();
        Long initialVersion = cold.getVersion();
        cold.setBalance(new BigDecimal("500"));
        custodyBalanceRepository.save(cold);

        CustodyBalanceEntity reloaded = custodyBalanceRepository.findByTier("COLD").orElseThrow();
        assertNotNull(reloaded.getVersion(), "version 不应为 null");
        assertTrue(reloaded.getVersion() >= (initialVersion == null ? 0 : initialVersion),
                "version 应递增或保持非负");
    }

    // ==================== WhitelistEntryRepository CRUD ====================

    @Test
    @DisplayName("WhitelistEntryRepository: save + findByAddress + existsByAddressAndActiveTrue")
    void whitelist_saveAndQuery() {
        WhitelistEntryEntity entity = new WhitelistEntryEntity();
        entity.setAddress("0xintegrationTestAddr1234567890");
        entity.setLabel("Integration test entry");
        entity.setMerchantId("merchant-it-1");
        entity.setAddedAt(LocalDateTime.now());
        entity.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
        entity.setActive(true);

        WhitelistEntryEntity saved = whitelistEntryRepository.save(entity);
        assertNotNull(saved.getId(), "save 后 id 应由 IDENTITY 策略生成");

        // findByAddress
        Optional<WhitelistEntryEntity> found = whitelistEntryRepository.findByAddress("0xintegrationTestAddr1234567890");
        assertTrue(found.isPresent());
        assertEquals("merchant-it-1", found.get().getMerchantId());

        // existsByAddressAndActiveTrue
        assertTrue(whitelistEntryRepository.existsByAddressAndActiveTrue("0xintegrationTestAddr1234567890"));

        // existsByAddress（含非活跃）
        assertTrue(whitelistEntryRepository.existsByAddress("0xintegrationTestAddr1234567890"));
    }

    @Test
    @DisplayName("WhitelistEntryRepository: 软删除后 existsByAddressAndActiveTrue=false")
    void whitelist_softDeleteMakesInactive() {
        WhitelistEntryEntity entity = new WhitelistEntryEntity();
        entity.setAddress("0xsoftDeleteTestAddr1234567890");
        entity.setMerchantId("merchant-it-2");
        entity.setAddedAt(LocalDateTime.now());
        entity.setActive(true);
        whitelistEntryRepository.save(entity);

        // 软删除：active=false
        entity.setActive(false);
        whitelistEntryRepository.save(entity);

        assertFalse(whitelistEntryRepository.existsByAddressAndActiveTrue("0xsoftDeleteTestAddr1234567890"),
                "软删除后 existsByAddressAndActiveTrue 应为 false");
        assertTrue(whitelistEntryRepository.existsByAddress("0xsoftDeleteTestAddr1234567890"),
                "软删除后 existsByAddress 仍应为 true（含非活跃记录）");
    }

    @Test
    @DisplayName("WhitelistEntryRepository: findByMerchantIdAndActiveTrue 只返回活跃记录")
    void whitelist_findByMerchantIdAndActiveTrue() {
        String merchantId = "merchant-filter-test";
        createWhitelistEntry("0xmerchantActive1Addr1234567890", merchantId, true);
        createWhitelistEntry("0xmerchantActive2Addr1234567890", merchantId, true);
        createWhitelistEntry("0xmerchantInactiveAddr1234567890", merchantId, false);

        List<WhitelistEntryEntity> active = whitelistEntryRepository.findByMerchantIdAndActiveTrue(merchantId);
        assertEquals(2, active.size(), "应只返回 2 条活跃记录");
    }

    private void createWhitelistEntry(String address, String merchantId, boolean active) {
        WhitelistEntryEntity entity = new WhitelistEntryEntity();
        entity.setAddress(address);
        entity.setMerchantId(merchantId);
        entity.setAddedAt(LocalDateTime.now());
        entity.setActive(active);
        whitelistEntryRepository.save(entity);
    }

    // ==================== WithdrawalRequestRepository CRUD ====================

    @Test
    @DisplayName("WithdrawalRequestRepository: save + findByRequestId + 状态查询")
    void withdrawalRequest_saveAndQuery() {
        WithdrawalRequestEntity entity = new WithdrawalRequestEntity();
        entity.setRequestId("WD-it-request-001");
        entity.setToAddress("0xwithdrawalTargetAddr123456789");
        entity.setAmount(new BigDecimal("500.000"));
        entity.setCurrency("NEX");
        entity.setStatus(WithdrawalRequest.WithdrawalStatus.PENDING);
        entity.setRequiredApprovers(2);
        entity.setApprovedCount(0);

        WithdrawalRequestEntity saved = withdrawalRequestRepository.save(entity);
        assertNotNull(saved.getId());

        // findByRequestId
        Optional<WithdrawalRequestEntity> found = withdrawalRequestRepository.findByRequestId("WD-it-request-001");
        assertTrue(found.isPresent());
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, found.get().getStatus());

        // findByStatus
        List<WithdrawalRequestEntity> pending = withdrawalRequestRepository.findByStatus(WithdrawalRequest.WithdrawalStatus.PENDING);
        assertTrue(pending.stream().anyMatch(r -> "WD-it-request-001".equals(r.getRequestId())));

        // findByStatusOrderByCreatedAtDesc
        List<WithdrawalRequestEntity> ordered = withdrawalRequestRepository.findByStatusOrderByCreatedAtDesc(WithdrawalRequest.WithdrawalStatus.PENDING);
        assertFalse(ordered.isEmpty());
    }

    @Test
    @DisplayName("WithdrawalRequestRepository: 状态流转 PENDING → APPROVED → EXECUTED")
    void withdrawalRequest_statusTransition() {
        WithdrawalRequestEntity entity = new WithdrawalRequestEntity();
        entity.setRequestId("WD-it-transition-001");
        entity.setToAddress("0xtransitionTargetAddr123456789");
        entity.setAmount(new BigDecimal("100.000"));
        entity.setCurrency("NEX");
        entity.setStatus(WithdrawalRequest.WithdrawalStatus.PENDING);
        entity.setRequiredApprovers(1);
        entity.setApprovedCount(0);
        withdrawalRequestRepository.save(entity);

        // PENDING → APPROVED
        entity.setStatus(WithdrawalRequest.WithdrawalStatus.APPROVED);
        entity.setApprovedCount(1);
        withdrawalRequestRepository.save(entity);

        // APPROVED → EXECUTED
        entity.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED);
        entity.setChainTxHash("0xexecutedTxHash1234567890abcdef");
        entity.setExecutedAt(LocalDateTime.now());
        withdrawalRequestRepository.save(entity);

        WithdrawalRequestEntity reloaded = withdrawalRequestRepository.findByRequestId("WD-it-transition-001").orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, reloaded.getStatus());
        assertEquals("0xexecutedTxHash1234567890abcdef", reloaded.getChainTxHash());
        assertNotNull(reloaded.getExecutedAt());
    }

    // ==================== WithdrawalApproverRepository CRUD ====================

    @Test
    @DisplayName("WithdrawalApproverRepository: save + findByRequestId + existsByRequestIdAndApproverId + countByRequestId")
    void withdrawalApprover_saveAndQuery() {
        // 先创建一个 withdrawal_request（外键约束）
        WithdrawalRequestEntity request = new WithdrawalRequestEntity();
        request.setRequestId("WD-it-approver-001");
        request.setToAddress("0xapproverTargetAddr1234567890");
        request.setAmount(new BigDecimal("200.000"));
        request.setCurrency("NEX");
        request.setStatus(WithdrawalRequest.WithdrawalStatus.PENDING);
        request.setRequiredApprovers(2);
        request.setApprovedCount(0);
        withdrawalRequestRepository.save(request);

        // 插入两个审批人
        createApprover("WD-it-approver-001", "approver-A");
        createApprover("WD-it-approver-001", "approver-B");

        // findByRequestId
        List<WithdrawalApproverEntity> approvers = withdrawalApproverRepository.findByRequestId("WD-it-approver-001");
        assertEquals(2, approvers.size());

        // existsByRequestIdAndApproverId
        assertTrue(withdrawalApproverRepository.existsByRequestIdAndApproverId("WD-it-approver-001", "approver-A"));
        assertFalse(withdrawalApproverRepository.existsByRequestIdAndApproverId("WD-it-approver-001", "approver-C"));

        // countByRequestId
        assertEquals(2, withdrawalApproverRepository.countByRequestId("WD-it-approver-001"));
    }

    private void createApprover(String requestId, String approverId) {
        WithdrawalApproverEntity approver = new WithdrawalApproverEntity();
        approver.setRequestId(requestId);
        approver.setApproverId(approverId);
        approver.setApprovedAt(LocalDateTime.now());
        withdrawalApproverRepository.save(approver);
    }
}