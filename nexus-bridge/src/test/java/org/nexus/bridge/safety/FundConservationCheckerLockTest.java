package org.nexus.bridge.safety;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 资金守恒校验器的多副本锁存在性回归（2026-10-13，接 #103 门禁后清历史缺口）。
 *
 * <p>{@code doCheck()} 本身只读聚合（{@link FundConservationChecker} 内 sumBy... 系列），
 * 但违规分支经 {@code handleViolations → triggerEmergencyPause} 落到
 * {@link DefaultEmergencyPauseService#triggerPause}（:122-136 的真实写库），
 * 后者是"findById→setState→save"的绝对置状态、<b>无 CAS、无 @Version、无去重标记</b>。</p>
 *
 * <p>3 副本同一时刻各读一份非一致性快照：只要某副本读到 {@code minted>locked} 的瞬时抖动，
 * 就会由"三之一"触发一次误紧急暂停（把整条桥打成 EMERGENCY_STOP），并叠 3× ERROR/webhook 风暴。
 * 上锁让"读快照 + 判定 + 写状态"在多副本间串行，去重误判与告警风暴。</p>
 */
class FundConservationCheckerLockTest {

    @Test
    @DisplayName("checkConservation 必须上锁（只读校验，但其副作用是会误停整条桥的真实写库）")
    void checkConservationMustBeLocked() throws NoSuchMethodException {
        Method method = FundConservationChecker.class.getMethod("checkConservation");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertNotNull(lock,
                "FundConservationChecker.checkConservation 缺 @SchedulerLock：违规分支写 EMERGENCY_STOP "
                        + "(DefaultEmergencyPauseService:122-136 绝对置状态、无 CAS/@Version)，"
                        + "prod bridge replicaCount=3 ⇒ 单个副本读到瞬时 minted>locked 抖动即可误停整条桥 + 3× 告警风暴");
        assertFalse(lock.lockAtMostFor().isEmpty(),
                "必须显式 lockAtMostFor：副本崩溃后锁要自动释放，否则守恒校验永久停摆");
        assertFalse(lock.lockAtLeastFor().isEmpty(),
                "必须显式 lockAtLeastFor：防止快重启的副本在同一 5 分钟窗口里重复判定");
    }
}
