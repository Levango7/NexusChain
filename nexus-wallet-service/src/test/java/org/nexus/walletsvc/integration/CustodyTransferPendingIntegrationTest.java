package org.nexus.walletsvc.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.wallet.WalletTransactionRequest;
import org.nexus.sdk.wallet.WalletTransactionResult;
import org.nexus.walletsvc.custody.CustodyService;
import org.nexus.walletsvc.custody.CustodyTransferUnverifiedException;
import org.nexus.walletsvc.entity.CustodyBalanceEntity;
import org.nexus.walletsvc.entity.CustodyTransferPendingEntity;
import org.nexus.walletsvc.entity.CustodyTransferPendingEntity.PendingStatus;
import org.nexus.walletsvc.execution.OnChainExecutionClient;
import org.nexus.walletsvc.repository.CustodyBalanceRepository;
import org.nexus.walletsvc.repository.CustodyTransferPendingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 托管出金待处置（Flyway V5 + 事务语义）集成测试（2026-10-25，Board 裁决 20261025-0955 §五）。
 *
 * <p>本类只验<b>真实事务</b>才能证清的两件事，单测（Mock 仓储、绕过代理）证不了：</p>
 * <ol>
 *   <li>{@code EXECUTED_UNVERIFIED} 抛出 {@link CustodyTransferUnverifiedException} 时，
 *       冷热余额调整<strong>保持提交</strong>（{@code noRollbackFor} 真的生效）；</li>
 *   <li>{@code AWAITING_APPROVAL} 抛异常回滚余额时，{@code APPROVAL_OPEN} 待处置行
 *       <strong> survives the rollback</strong>（{@code REQUIRES_NEW} 独立事务真的生效）。</li>
 * </ol>
 *
 * <p>顺带证明 V5 DDL 与 Entity 对齐：测试 profile 是 {@code ddl-auto: validate} +
 * Flyway 实跑，任何列名/类型/宽度漂移都会让上下文起不来。</p>
 *
 * <p>与 {@code CustodyServiceIntegrationTest} 不同，本类<strong>不</strong>加类级
 * {@code @Transactional}：那会把被测事务包进测试事务，回滚/提交行为就测不到了。
 * 代价是必须自己收尾——{@link #cleanup()} 把 HOT/COLD 复位为 0 并清空待处置表，
 * 避免污染共享 H2 库里的其它测试。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CustodyTransferPendingIntegrationTest {

    private static final String COLD_ADDR = "0xcustodypendingaddr0123456789";
    private static final BigDecimal HOT_START = new BigDecimal("1000");
    private static final BigDecimal SWEEP_AMOUNT = new BigDecimal("400");

    @Autowired
    private CustodyService custodyService;

    @Autowired
    private CustodyBalanceRepository custodyBalanceRepository;

    @Autowired
    private CustodyTransferPendingRepository pendingRepository;

    /** 替换真实 HttpOnChainExecutionClient：按用例返回指定执行结果。 */
    @MockitoBean
    private OnChainExecutionClient executionClient;

    @BeforeEach
    void seedHotBalance() {
        setBalances(HOT_START, BigDecimal.ZERO);
    }

    @AfterEach
    void cleanup() {
        pendingRepository.deleteAll();
        setBalances(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void setBalances(BigDecimal hot, BigDecimal cold) {
        CustodyBalanceEntity hotEntity = custodyBalanceRepository.findByTier("HOT").orElseThrow();
        hotEntity.setBalance(hot);
        custodyBalanceRepository.save(hotEntity);
        CustodyBalanceEntity coldEntity = custodyBalanceRepository.findByTier("COLD").orElseThrow();
        coldEntity.setBalance(cold);
        custodyBalanceRepository.save(coldEntity);
    }

    private void stubExecuteResult(WalletTransactionResult result) {
        when(executionClient.execute(any(WalletTransactionRequest.class))).thenReturn(result);
    }

    /** 读库（新事务，不受被测事务结局影响）。 */
    private BigDecimal hotFromDb() {
        return custodyBalanceRepository.findByTier("HOT").orElseThrow().getBalance();
    }

    private BigDecimal coldFromDb() {
        return custodyBalanceRepository.findByTier("COLD").orElseThrow().getBalance();
    }

    @Test
    @DisplayName("EXECUTED_UNVERIFIED：余额调整保持提交，并登记 VERIFY_REQUIRED")
    void executedUnverified_keepsBalanceShiftCommitted() {
        stubExecuteResult(new WalletTransactionResult(null,
                WalletTransactionResult.Status.EXECUTED_UNVERIFIED, 0, "broadcast ack lost", false, "APR-IT-1"));

        assertThrows(CustodyTransferUnverifiedException.class,
                () -> custodyService.depositToCold(COLD_ADDR, SWEEP_AMOUNT));

        // 钱可能已出：账面按「已挪动」提交，回滚才会造出反向对账缺口
        assertEquals(0, new BigDecimal("600").compareTo(hotFromDb()), "HOT 应停在扣减后的 600（未回滚）");
        assertEquals(0, SWEEP_AMOUNT.compareTo(coldFromDb()), "COLD 应停在增加后的 400（未回滚）");

        assertEquals(1L, pendingRepository.countByStatus(PendingStatus.VERIFY_REQUIRED));
        CustodyTransferPendingEntity pending =
                pendingRepository.findTop100ByStatusOrderByCreatedAtAsc(PendingStatus.VERIFY_REQUIRED).get(0);
        assertEquals("APR-IT-1", pending.getApprovalId());
        assertEquals("SWEEP", pending.getTransferType());
        assertEquals(COLD_ADDR, pending.getAddress());
        assertEquals(0, SWEEP_AMOUNT.compareTo(pending.getAmount()));
        assertEquals("NEX", pending.getCurrency());
        assertTrue(pending.getCreatedAt() != null && pending.getUpdatedAt() != null);
    }

    @Test
    @DisplayName("AWAITING_APPROVAL：余额回滚，但 APPROVAL_OPEN 行活过回滚")
    void awaitingApproval_rollsBackBalancesButKeepsPendingRow() {
        stubExecuteResult(new WalletTransactionResult(null,
                WalletTransactionResult.Status.AWAITING_APPROVAL, 0, null, false, "APR-IT-2"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> custodyService.depositToCold(COLD_ADDR, SWEEP_AMOUNT));
        assertTrue(ex.getMessage().contains("awaiting signing approval"), ex.getMessage());

        // 钱没出：冷热余额撤销，回到起始账面
        assertEquals(0, HOT_START.compareTo(hotFromDb()), "HOT 应回滚到 1000");
        assertEquals(0, BigDecimal.ZERO.compareTo(coldFromDb()), "COLD 应回滚到 0");

        // 悬空审批的留痕必须活下来，否则无人知道有一笔托管出金等着人工
        assertEquals(1L, pendingRepository.countByStatus(PendingStatus.APPROVAL_OPEN));
        CustodyTransferPendingEntity pending =
                pendingRepository.findTop100ByStatusOrderByCreatedAtAsc(PendingStatus.APPROVAL_OPEN).get(0);
        assertEquals("APR-IT-2", pending.getApprovalId());
        assertTrue(pending.getErrorNote().contains("回滚"), pending.getErrorNote());
    }

    @Test
    @DisplayName("确定失败（无哈希 FAILED）：余额回滚且不登记待处置")
    void definiteFailure_rollsBackAndRecordsNothing() {
        stubExecuteResult(new WalletTransactionResult(null,
                WalletTransactionResult.Status.FAILED, 0, "gateway unreachable", false));

        assertThrows(IllegalStateException.class,
                () -> custodyService.depositToCold(COLD_ADDR, SWEEP_AMOUNT));

        assertEquals(0, HOT_START.compareTo(hotFromDb()));
        assertEquals(0, BigDecimal.ZERO.compareTo(coldFromDb()));
        assertEquals(0L, pendingRepository.count());
    }

    @Test
    @DisplayName("request_key 唯一：同一幂等键不得登记两次")
    void requestKeyIsUniqueAtDatabaseLevel() {
        CustodyTransferPendingEntity first = pendingRow("custody-it-dedup-key", PendingStatus.APPROVAL_OPEN);
        pendingRepository.saveAndFlush(first);

        assertThrows(DataIntegrityViolationException.class,
                () -> pendingRepository.saveAndFlush(
                        pendingRow("custody-it-dedup-key", PendingStatus.VERIFY_REQUIRED)));
    }

    private static CustodyTransferPendingEntity pendingRow(String requestKey, PendingStatus status) {
        CustodyTransferPendingEntity entity = new CustodyTransferPendingEntity();
        entity.setRequestKey(requestKey);
        entity.setTransferType("SWEEP");
        entity.setAddress(COLD_ADDR);
        entity.setAmount(SWEEP_AMOUNT);
        entity.setCurrency("NEX");
        entity.setStatus(status);
        return entity;
    }
}
