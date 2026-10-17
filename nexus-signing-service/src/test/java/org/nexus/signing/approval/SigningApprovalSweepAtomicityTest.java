package org.nexus.signing.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.MpcApprovalPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 过期清理扫描的<b>原子迁移</b>回归（#32 第一片：共享 store 的前置条件）。
 *
 * <p>背景：{@code cleanupExpiredRequests} 原先对两处状态迁移用裸 {@code requestStore.save(key, 新值)}
 * 整行覆写——在 {@link MapApprovalStore} 下每副本只看自己那份内存，覆写看不见别人；一旦审批 store
 * 换成 {@link JpaApprovalStore}（prod {@code nexus-signing-service.replicaCount=3} 共享同一张
 * {@code signing_approval_request} 表），三副本同分钟各扫各覆写，就把"别人刚迁移的状态"打回旧值
 * （丢更新），还会对同一请求各写一条审计 ⇒ 审计噪声 ×3、状态来回抖。</p>
 *
 * <p>本片把扫描里两处迁移改为 {@link ApprovalStore#compareAndTransition}（JPA 侧靠实体
 * {@code @Version} 乐观锁，并发只有一个赢家）。同仓已有正确先例
 * {@link SigningApprovalService#revertExecuting} 就是 CAS；本测试把扫描也钉在同一口径上。
 * <b>顺序不能反</b>：必须先有本片，才能安全地把 prod 切到共享 store。</p>
 */
@DisplayName("审批清理扫描的状态迁移必须是原子 CAS")
class SigningApprovalSweepAtomicityTest {

    private static final BigDecimal AMOUNT = new BigDecimal("50000");

    private ApprovalStore store;
    private AuditLogService auditLog;
    private SigningApprovalService service;

    @BeforeEach
    void setUp() {
        store = mock(ApprovalStore.class);
        auditLog = mock(AuditLogService.class);
        MpcApprovalPolicy policy = mock(MpcApprovalPolicy.class);
        // 四参公共构造器：注入自定义 store，绕开配置选择逻辑，只看扫描行为
        service = new SigningApprovalService(policy, auditLog, store, null);
    }

    /** 构造指定状态/到期时间的请求（包私有构造器，测试同包可用）。 */
    private static SigningApprovalRequest request(String id, SigningApprovalRequest.Status status,
                                                  Instant deadline) {
        return new SigningApprovalRequest(
                id, "pkFrom", "pkToHash", AMOUNT, "USDT", 2,
                deadline.minusSeconds(3600), deadline,
                status, Set.of("a@nexus", "b@nexus"), Set.of(), "initiator@nexus");
    }

    private void storeContains(SigningApprovalRequest... requests) {
        Set<Map.Entry<String, SigningApprovalRequest>> entries = new LinkedHashSet<>();
        for (SigningApprovalRequest r : requests) {
            entries.add(Map.entry(r.getRequestId(), r));
        }
        when(store.entrySet()).thenReturn(entries);
    }

    @Test
    @DisplayName("1.PENDING 已过期且本副本抢到迁移权：走 CAS，不走裸 save，审计恰好一条")
    void pendingExpired_usesCas_notBlindSave() {
        String id = "req-win-expire";
        storeContains(request(id, SigningApprovalRequest.Status.PENDING, Instant.now().minusSeconds(60)));
        when(store.compareAndTransition(id, SigningApprovalRequest.Status.PENDING,
                SigningApprovalRequest.Status.EXPIRED)).thenReturn(true);

        service.cleanupExpiredRequests();

        verify(store).compareAndTransition(id, SigningApprovalRequest.Status.PENDING,
                SigningApprovalRequest.Status.EXPIRED);
        verify(store, never()).save(any(), any());
        verify(auditLog, times(1)).log(any());
    }

    @Test
    @DisplayName("2.反向守卫：CAS 返回 false（别副本已先迁移）时不得覆写、不得再写审计")
    void pendingExpired_lostCas_mustNotOverwriteOrAudit() {
        String id = "req-lose-expire";
        storeContains(request(id, SigningApprovalRequest.Status.PENDING, Instant.now().minusSeconds(60)));
        when(store.compareAndTransition(id, SigningApprovalRequest.Status.PENDING,
                SigningApprovalRequest.Status.EXPIRED)).thenReturn(false);

        service.cleanupExpiredRequests();

        // 这条是本片的核心：旧实现无条件 save(整行覆写) + 无条件写审计 ⇒ 两个断言都会红
        verify(store, never()).save(any(), any());
        verify(auditLog, never()).log(any());
    }

    @Test
    @DisplayName("3.EXECUTING 卡死恢复也走 CAS（与 revertExecuting 同口径），不用裸 save")
    void executingStuck_usesCas_notBlindSave() {
        String id = "req-stuck-executing";
        storeContains(request(id, SigningApprovalRequest.Status.EXECUTING, Instant.now().minusSeconds(60)));
        when(store.compareAndTransition(id, SigningApprovalRequest.Status.EXECUTING,
                SigningApprovalRequest.Status.APPROVED)).thenReturn(true);

        service.cleanupExpiredRequests();

        verify(store).compareAndTransition(id, SigningApprovalRequest.Status.EXECUTING,
                SigningApprovalRequest.Status.APPROVED);
        verify(store, never()).save(any(), any());
    }

    @Test
    @DisplayName("4.EXECUTING 卡死恢复抢输：不覆写、不审计（避免把别副本的 EXECUTED 打回 APPROVED）")
    void executingStuck_lostCas_mustNotOverwriteOrAudit() {
        String id = "req-stuck-loser";
        storeContains(request(id, SigningApprovalRequest.Status.EXECUTING, Instant.now().minusSeconds(60)));
        when(store.compareAndTransition(id, SigningApprovalRequest.Status.EXECUTING,
                SigningApprovalRequest.Status.APPROVED)).thenReturn(false);

        service.cleanupExpiredRequests();

        verify(store, never()).save(any(), any());
        verify(auditLog, never()).log(any());
    }

    @Test
    @DisplayName("5.未到期请求完全不被触碰（扫描判据未被动）")
    void notYetExpired_isNotTouched() {
        String id = "req-still-pending";
        storeContains(request(id, SigningApprovalRequest.Status.PENDING, Instant.now().plusSeconds(600)));

        service.cleanupExpiredRequests();

        verify(store, never()).compareAndTransition(any(), any(), any());
        verify(store, never()).save(any(), any());
        verify(auditLog, never()).log(any());
    }

    @Test
    @DisplayName("6.回归护栏：终态且过保留期仍被 remove（本片不改清理语义）")
    void terminalBeyondRetention_stillRemoved() {
        String id = "req-terminal";
        storeContains(request(id, SigningApprovalRequest.Status.REJECTED, Instant.now().minusSeconds(7200)));

        service.cleanupExpiredRequests();

        verify(store, times(1)).remove(id);
        verify(store, never()).compareAndTransition(any(), any(), any());
        verify(store, never()).save(any(), any());
    }
}
