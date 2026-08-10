package org.nexus.core.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * nexus-core 架构合规规则（P1-T11）。
 *
 * <p>守护 nexus-core 内部模块边界，防止底层共识/治理/L2 越界依赖上层服务
 * 或无关模块，保持基础设施层的单向依赖与可独立部署性。
 *
 * <p>规则清单：
 * <ol>
 *   <li>{@link #consensus_should_not_depend_on_gateway} — 共识层不得依赖 gateway（基础设施层不反向依赖服务层）</li>
 *   <li>{@link #l2_should_not_depend_on_bridge} — L2 Rollup 不得直接依赖 bridge（扩容层与跨链桥解耦）</li>
 *   <li>{@link #governance_should_not_depend_on_p2p} — 治理层不得依赖 P2P 网络层（治理逻辑与传输解耦）</li>
 * </ol>
 *
 * <p>分析范围：{@code org.nexus}（nexus-core 根包，涵盖 consensus / governance /
 * l2 / p2p / core 等子包）。gateway / bridge 的类不在 nexus-core 的编译类路径上，
 * 规则 1、2 在当前依赖图下自然成立；规则 3 守护 governance 与 p2p 的运行时解耦。
 */
@AnalyzeClasses(packages = "org.nexus")
public class CoreArchitectureRulesTest {

    // 共识层不应依赖 gateway：基础设施层不得反向依赖服务层
    @ArchTest
    static final ArchRule consensus_should_not_depend_on_gateway = noClasses()
        .that().resideInAPackage("..consensus..")
        .should().dependOnClassesThat().resideInAPackage("..gateway..");

    // L2 Rollup 不应直接依赖 bridge：扩容层与跨链桥保持解耦
    @ArchTest
    static final ArchRule l2_should_not_depend_on_bridge = noClasses()
        .that().resideInAPackage("..l2..")
        .should().dependOnClassesThat().resideInAPackage("..bridge..");

    // 治理层不应依赖 P2P 网络层：治理逻辑与网络传输解耦
    @ArchTest
    static final ArchRule governance_should_not_depend_on_p2p = noClasses()
        .that().resideInAPackage("..governance..")
        .should().dependOnClassesThat().resideInAPackage("..p2p..");
}