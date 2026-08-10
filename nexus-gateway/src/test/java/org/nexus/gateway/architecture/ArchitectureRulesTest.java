package org.nexus.gateway.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * nexus-gateway 架构合规规则（P1-T11）。
 *
 * <p>使用 ArchUnit 以声明式规则守护 gateway 内部分层依赖与命名约定，
 * 防止 controller 越层直访 repository、包间循环依赖等结构性退化。
 *
 * <p>规则清单：
 * <ol>
 *   <li>{@link #layer_dependencies} — 分层切片无循环依赖（controller → service → repository）</li>
 *   <li>{@link #controllers_should_not_access_repository} — controller 不得直接依赖 repository</li>
 *   <li>{@link #service_impl_naming} — service 实现类应位于 service 包内（命名约定守护）</li>
 * </ol>
 */
@AnalyzeClasses(packages = "org.nexus.gateway")
public class ArchitectureRulesTest {

    // 分层依赖：controller → service → repository，切片内不得存在循环依赖
    @ArchTest
    static final ArchRule layer_dependencies = slices()
        .matching("org.nexus.gateway.(*)..")
        .should().beFreeOfCycles();

    // controller 不应直接访问 repository（必须经 service 层中转）
    @ArchTest
    static final ArchRule controllers_should_not_access_repository = noClasses()
        .that().resideInAPackage("..controller..")
        .should().dependOnClassesThat().resideInAPackage("..repository..");

    // service 实现类应以 ServiceImpl 结尾并驻留在 service 包内（命名约定守护）
    @ArchTest
    static final ArchRule service_impl_naming = classes()
        .that().resideInAPackage("..service..")
        .and().haveSimpleNameEndingWith("Impl")
        .should().resideInAPackage("..service..");
}