package org.nexus.bridge;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * nexus-bridge 应用入口。
 *
 * <p>中6 改进：启用 ShedLock 分布式锁（{@link EnableSchedulerLock}），AOP 代理拦截
 * {@code @SchedulerLock} 注解的调度方法（如 BridgeSagaCoordinator.recoverIncompleteSagas()），
 * 通过 JdbcTemplateLockProvider + shedlock 表保证多实例部署时同一时刻仅一个实例执行。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT4M")
public class BridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(BridgeApplication.class, args);
    }
}