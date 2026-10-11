package org.nexus.gateway.wiring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 装配生效性门禁（P0 回归，2026-10-11）。
 *
 * <p><b>为什么需要它</b>：本次给两个类加了构造器重载后，CI 的 k6 冒烟**连挂三次**
 * （compose 里网关起不来：{@code No default constructor found}）。根因是
 * <b>多构造器类没有 @Autowired</b>：Spring 去找默认（无参）构造器 → BeanCreationException
 * → 整个网关上下文起不来。同批还在 {@code AuditLogService} 上发现<b>静默版</b>：
 * Spring 选中无参构造器 → 仓储永不注入 → 审计静默退回"只写日志"。</p>
 *
 * <p><b>教训</b>："Bean 被创建"不等于"依赖注入成功"；而 sandbox profile 的 Spring 上下文
 * <b>根本没有实例化这些 Bean</b>（所以本地 32 例全过却没暴露）。故本门禁：</p>
 * <ul>
 *   <li>用 <b>dev profile</b>（与 docker-compose 一致的启动路径）加载完整上下文；</li>
 *   <li>反射断言关键依赖<b>真的注入进来了</b>（不是 null）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("dev")
class WiringEffectivenessGuardTest {

    @Autowired
    private org.nexus.gateway.fundreport.AutoWithdrawService autoWithdrawService;

    @Autowired
    private org.nexus.gateway.audit.AuditLogService auditLogService;

    @Autowired
    private org.nexus.gateway.reconciliation.compensation.AutoCompensationService autoCompensationService;

    @Test
    @DisplayName("出款接线真的生效：AutoWithdrawService 注入了 PayoutExecutor 与账户服务")
    void payoutWiringIsEffective() throws Exception {
        assertNotNull(autoWithdrawService, "AutoWithdrawService Bean 必须存在（上下文能起）");
        assertNotNull(readField(autoWithdrawService, "payoutExecutor"),
                "payoutExecutor 必须注入——否则出款去向接线是假的（静默退回旧行为）");
        assertNotNull(readField(autoWithdrawService, "payoutAccountService"),
                "payoutAccountService 必须注入");
    }

    @Test
    @DisplayName("审计落库真的生效：AuditLogService 注入了仓储")
    void auditPersistenceIsEffective() throws Exception {
        assertNotNull(auditLogService);
        assertNotNull(readField(auditLogService, "auditEventRepository"),
                "auditEventRepository 必须注入——否则审计只会写日志文件、永不落库（静默降级）");
    }

    /** 读出真实字段值：先 unwrap Spring 代理（CGLIB 代理实例自身不持有注入的字段），
     *  再沿继承链向上找字段。 */
    @Test
    @DisplayName("补偿执行出口真的注入：AutoCompensationService 注入了账本与渠道退款服务")
    void compensationExecutorsAreWired() throws Exception {
        assertNotNull(autoCompensationService);
        assertNotNull(readField(autoCompensationService, "accountService"),
                "accountService 必须注入——否则内部调账补偿永远 fail-closed");
        assertNotNull(readField(autoCompensationService, "channelRefundService"),
                "channelRefundService 必须注入——否则长款渠道退款永远 fail-closed");
    }

    private static Object readField(Object bean, String name) throws Exception {
        Object target = bean;
        if (target instanceof org.springframework.aop.framework.Advised advised) {
            target = advised.getTargetSource().getTarget();
        }
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass());
    }
}
