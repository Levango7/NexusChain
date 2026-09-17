package org.nexus.bridge.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.bridge.BridgeException;
import org.nexus.bridge.BridgeService;
import org.nexus.bridge.LockRequest;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.repository.SagaInstanceRepository;

import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P0 回归测试（2026-09-17）：Saga 失败态必须存活于异常之外。
 *
 * <p>原缺陷：{@code executeLockMint} / {@code executeBurnUnlock} 标注了
 * {@code @Transactional}，而失败路径在写入 COMPENSATING → 补偿记录 → FAILED
 * 之后立即 {@code throw new BridgeException(...)}，导致这些写入随事务一并回滚。
 * 后果：源链 lock/burn 已生效，但「需要补偿」这一事实没有任何持久化痕迹，
 * 异步对账任务扫不到，资金滞留且无审计线索。</p>
 *
 * <p>本测试用记录型 Repository 捕获每一次 {@code save} 时的状态快照，
 * 断言异常抛出后最终状态为 FAILED 且补偿记录已写入 payload。
 * 注意必须记录**快照**而非对象引用 —— 同一个 SagaInstance 实例被反复改写，
 * 持有引用会让所有历史条目都呈现最终状态，从而掩盖真实缺陷。</p>
 */
class BridgeSagaCoordinatorFailurePersistenceTest {

    /** 记录每次 save 时的状态与 payload 快照。 */
    private static final class RecordingRepo {
        final List<SagaState> states = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();

        /** 注意：方法名不可叫 mock()，否则会遮蔽 Mockito 的静态导入 mock(Class)。 */
        SagaInstanceRepository recordingRepo() {
            SagaInstanceRepository repo = mock(SagaInstanceRepository.class);
            when(repo.save(any(SagaInstance.class))).thenAnswer(inv -> {
                SagaInstance s = inv.getArgument(0);
                states.add(s.getState());
                payloads.add(s.getPayload());
                return s;
            });
            return repo;
        }

        SagaState lastState() { return states.get(states.size() - 1); }

        String lastPayload() { return payloads.get(payloads.size() - 1); }
    }

    private static BridgeTransaction lockTx() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("lock-tx-1");
        tx.setAmount(100L);
        tx.setUserAddress("0xuser");
        tx.setSourceChainId("nexus");
        tx.setTargetChainId("ethereum");
        return tx;
    }

    /**
     * 结构性守卫：这是本 P0 的**直接**回归测试。
     *
     * <p>为什么必须单独断言注解：上面的行为测试直接 new 出协调器、不经过 Spring
     * 代理，事务根本不生效，因此它在修复前后**都会通过** —— 无法证明回滚问题已修。
     * 真正决定成败的是「方法上没有 @Transactional」，故直接断言注解不存在。</p>
     */
    @Test
    @DisplayName("P0: executeLockMint / executeBurnUnlock 不得标注 @Transactional")
    void executeMethodsMustNotBeTransactional() {
        for (String name : new String[] {"executeLockMint", "executeBurnUnlock"}) {
            Method method = Arrays.stream(BridgeSagaCoordinator.class.getMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未找到方法 " + name));
            assertNull(method.getAnnotation(Transactional.class),
                    name + " 被标注了 @Transactional —— 失败态与补偿记录会随异常回滚，"
                            + "资金滞留且对账任务无从发现（P0 复现）");
        }
    }

    @Test
    @DisplayName("lock→mint: mint 失败后 FAILED 状态与补偿记录必须已持久化")
    void lockMintFailureStateIsPersisted() {
        RecordingRepo repo = new RecordingRepo();

        BridgeService bridge = mock(BridgeService.class);
        when(bridge.lock(any(LockRequest.class))).thenReturn(lockTx());
        when(bridge.mint(any(MintRequest.class)))
                .thenThrow(new RuntimeException("target chain unavailable"));

        BridgeSagaCoordinator coordinator =
                new BridgeSagaCoordinator(bridge, repo.recordingRepo(), new ObjectMapper());

        BridgeException thrown = assertThrows(BridgeException.class,
                () -> coordinator.executeLockMint(new LockRequest(), new MintRequest()));
        assertEquals("BRIDGE_SAGA_MINT_FAILED", thrown.getErrorCode());

        // 关键断言：状态机确实走完 COMPENSATING → FAILED，且最终态为 FAILED
        assertTrue(repo.states.contains(SagaState.COMPENSATING),
                "必须留下 COMPENSATING 痕迹");
        assertEquals(SagaState.FAILED, repo.lastState(),
                "最终状态必须是 FAILED —— 若被事务回滚，对账任务将无法发现待补偿的 Saga");

        // 关键断言：补偿记录（含 lockTxId）必须已写入 payload
        String payload = repo.lastPayload();
        assertNotNull(payload, "payload 不得为空");
        assertTrue(payload.contains("UNLOCK_AFTER_FAILED_MINT"),
                "补偿类型必须写入 payload");
        assertTrue(payload.contains("lock-tx-1"),
                "补偿记录必须包含 lockTxId，否则无法定位待解锁的源链资产");
    }

    @Test
    @DisplayName("burn→unlock: unlock 失败后 FAILED 状态与重试上下文必须已持久化")
    void burnUnlockFailureStateIsPersisted() {
        RecordingRepo repo = new RecordingRepo();

        BridgeService bridge = mock(BridgeService.class);
        when(bridge.burn(any())).thenReturn(lockTx());
        when(bridge.unlock(any())).thenThrow(new RuntimeException("unlock rejected"));

        BridgeSagaCoordinator coordinator =
                new BridgeSagaCoordinator(bridge, repo.recordingRepo(), new ObjectMapper());

        BridgeException thrown = assertThrows(BridgeException.class,
                () -> coordinator.executeBurnUnlock(
                        new org.nexus.bridge.BurnRequest(),
                        new org.nexus.bridge.UnlockRequest()));
        assertEquals("BRIDGE_SAGA_UNLOCK_FAILED", thrown.getErrorCode());

        assertEquals(SagaState.FAILED, repo.lastState(),
                "最终状态必须是 FAILED —— 否则重试任务扫不到这笔待解锁");
        String payload = repo.lastPayload();
        assertNotNull(payload, "payload 不得为空");
        assertTrue(payload.contains("RETRY_UNLOCK"),
                "重试上下文必须写入 payload，否则用户资产永久锁定");
    }
}
