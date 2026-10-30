package org.nexus.metrics;

import io.micrometer.core.instrument.binder.MeterBinder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配审计门禁（PLAN-058 固化）：**每个 MeterBinder 实现都必须带注册路径**。
 *
 * <p>为什么需要它：{@link FinalityMetrics} 与 {@link AbstainRiskMetrics} 曾缺
 * {@code @Component} ⇒ 从未进容器 ⇒ 指标从未暴露 ⇒ 三条告警永不会触发，
 * 而两者的单测各有 4~6 例全绿。这与 PLAN-038（Bean 重复）、PLAN-055
 * （ShedLock 方法签名被拒）同型：**类写对了但没接进容器/调度，单测不起容器所以全绿**。</p>
 *
 * <p>本测试用组件扫描器扫出本仓所有 {@code MeterBinder} 实现（不实例化），
 * 逐个断言类上带 {@code @Component}。将来新增指标类忘写注解，CI 直接红。</p>
 *
 * <p>豁免机制：确需通过 {@code @Bean} 手工注册的类，加进 {@link #MANUAL_BEANS}
 * 并写明理由——白名单是显式的，比"忘了"好。</p>
 */
class WiringAuditTest {

    /** 经 @Bean 手工注册的 MeterBinder（当前无；保留机制以便将来显式豁免）。 */
    private static final Set<String> MANUAL_BEANS = Set.of();

    @Test
    void everyMeterBinderHasARegistrationPath() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        scanner.addIncludeFilter(new AssignableTypeFilter(MeterBinder.class));

        Set<String> found = new TreeSet<>();
        for (org.springframework.beans.factory.config.BeanDefinition def
                : scanner.findCandidateComponents("org.nexus")) {
            try {
                Class<?> type = ClassUtils.resolveClassName(def.getBeanClassName(),
                        ClassUtils.getDefaultClassLoader());
                if (MeterBinder.class.isAssignableFrom(type) && !type.isInterface()) {
                    found.add(type.getName());
                }
            } catch (Throwable ignored) {
                // \u65e0\u6cd5\u52a0\u8f7d\u7684\u7c7b\u4e0d\u5f71\u54cd\u5269\u4f59\u5224\u5b9a
            }
        }

        assertTrue(found.size() >= 3,
                "至少应扫出场内 3 个 MeterBinder（FinalityMetrics / AbstainRiskMetrics / "
                        + "BlockPersistenceMetrics），实际 = " + found + "（扫描器配置可能坏了）");

        for (String name : found) {
            if (MANUAL_BEANS.contains(name)) {
                continue;
            }
            try {
                Class<?> type = Class.forName(name);
                assertTrue(type.isAnnotationPresent(Component.class),
                        name + " 实现了 MeterBinder 但没带 @Component ⇒ 它的指标不会进 "
                                + "/actuator/prometheus（PLAN-058 缺陷的 CI 门禁）。"
                                + "若确需 @Bean 手工注册，请加进 WiringAuditTest.MANUAL_BEANS 并写明理由。");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
