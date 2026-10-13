package org.nexus.gateway.schedulerlock;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.alert.AlertEngine;
import org.nexus.gateway.alert.AlertEscalationService;
import org.nexus.gateway.orchestration.settlement.ChainSettlementConfirmationService;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 三个网关多副本调度器的分布式锁存在性回归（2026-10-13，接 #103 门禁后清历史缺口）。
 *
 * <p>本类钉的是"注解在不在"这一事实，作为将来有人误删 {@code @SchedulerLock} 的守卫；
 * 每条用例的注释写清"没有这把锁时 3 个副本究竟坏在哪"，严重性不靠命名暗示。</p>
 */
class GatewaySchedulerLockTest {

    private static void assertLocked(Class<?> owner, String methodName, String why) throws NoSuchMethodException {
        Method method = owner.getMethod(methodName);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertNotNull(lock, owner.getSimpleName() + "." + methodName + " 缺 @SchedulerLock：" + why);
        assertFalse(lock.lockAtMostFor().isEmpty(),
                owner.getSimpleName() + "." + methodName + " 必须显式 lockAtMostFor：副本崩溃后锁要自动释放");
        assertFalse(lock.lockAtLeastFor().isEmpty(),
                owner.getSimpleName() + "." + methodName + " 必须显式 lockAtLeastFor：防止快重启的副本在同一窗口里重复执行");
    }

    @Test
    @DisplayName("AlertEngine.checkAlerts 必须上锁（冷却是 JVM 内存态，挡不住 3 副本各插一条告警）")
    void alertEngineMustBeLocked() throws NoSuchMethodException {
        assertLocked(AlertEngine.class, "checkAlerts",
                "AlertEngine:43,155-162 的 lastTriggeredMap 是 ConcurrentHashMap（每 JVM 一份），"
                        + "AlertEngine:174-178 号称的 P1-5 幂等检查读的是同一张内存表 ⇒ 跨副本零去重；"
                        + "triggerAlert 在 :195 INSERT AlertEvent，而 AlertEvent 无 @Version(alert/AlertEvent.java:15-17)、"
                        + "alert_events 表无唯一键 ⇒ prod 3 副本同刻各插一条重复告警，通知渠道一旦启用即 3× 触发");
    }

    @Test
    @DisplayName("AlertEscalationService.checkEscalations 必须上锁（读共享库、对 message 做非幂等字符串追加）")
    void alertEscalationMustBeLocked() throws NoSuchMethodException {
        assertLocked(AlertEscalationService.class, "checkEscalations",
                "扫描判据来自共享 DB 的未解决事件(:55) ⇒ 三副本读到同一批行并都判定需升级；"
                        + "escalate() 在 :121 对 message 做 `message + \" [ESCALATED...]\"` 字符串追加（非幂等），"
                        + "而 AlertEvent 无 @Version ⇒ 升级说明被追加三遍，且 :137 升级通知对同一事件发 3 次");
    }

    @Test
    @DisplayName("ChainSettlementConfirmationService.scanPendingConfirmations 必须上锁（整批 @Transactional + @Version 乐观锁冲突 = 整轮回滚）")
    void chainSettlementScanMustBeLocked() throws NoSuchMethodException {
        assertLocked(ChainSettlementConfirmationService.class, "scanPendingConfirmations",
                "整个扫描是一个 @Transactional(:281)，内层 checkConfirmation/handleConfirmationTimeout 各自 @Transactional 加入同一批；"
                        + "SettlementConfirmationRecord 有 @Version(:71) ⇒ 三副本对同一 PENDING 记录并发 save 时输者抛乐观锁异常，"
                        + "连带把本 tick 其它记录的合法状态迁移一起回滚，并对同一批 PENDING 制造 3× 链上 RPC fan-out"
                        + "（非双付——该路径不动钱；坏在状态抖动与外部负载）");
    }
}
