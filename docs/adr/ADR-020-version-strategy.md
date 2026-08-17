# ADR-020: 版本治理策略 — 双轨制（Two-Tier Version Strategy）

- **状态**：Superseded by ADR-032（2026-08-18）
- **决策人**：项目总监（大湾区靓仔）/ 高见远（架构师），依据阶段 0 用户授权「自行决策并执行」
- **关联**：审计阻断项 **B1**（双 Spring 生态并存 + 版本伪统一）；本报告 §4 阶段 0 / §6.5
- **被替代**：ADR-032-spring-boot-unification（所有 Java 微服务统一 Spring Boot 3.2.5）

> **替代说明（2026-08-18）**：本 ADR 制定的双轨策略（Tier-1 Boot 3.x / Tier-2 Boot 2.x）
> 基于 javax→jakarta 迁移成本过高的假设。Phase 2 已完成全部 Tier-2 模块的
> javax→jakarta 迁移，所有 Java 微服务统一到 Spring Boot 3.2.5。
> 双轨策略不再适用，由 ADR-032 取代。本文档保留作为历史决策记录。

---

## 1. 背景（Context）

审计报告 B1 指出 NexusChain 存在「双 Spring 生态并存 + 版本治理形同虚设」：

- 根 `build.gradle` 的 `subprojects` 块**强制**向所有子模块注入
  `spring-boot-starter-test:2.7.18` 与 `junit:junit:4.13.2`；
- `nexus-gateway` / `nexus-bridge` 实际是 **Spring Boot 3.2.5 / Java 17**（Jakarta 命名空间 `jakarta.*`）；
- `nexus-core` / `nexus-consortium*` / `nexus-exchange-wallet` / `nexus-sdk/java` 停留在
  **Spring Boot 2.x / Java 8**（javax 命名空间 `javax.*`）；
- 根 `ext` 锁 `springBootVersion = '2.7.18'`，但 ARCHITECTURE.md 声称「Spring Boot 3.2.x」——文档与构建事实不一致。

### 关键约束：Jakarta 墙（Jakarta Wall）

Spring Boot 3.x 将 `javax.*` 全面迁移到 `jakarta.*`。`nexus-core` 大规模使用
`javax.servlet` / `javax.annotation` / `javax.xml.bind` / `javax.validation`
（见 `nexus-core/nexus-core/build.gradle:92-96`）——**这些代码在 Boot 3.x 下无法编译**，
除非做 javax→jakarta 迁移（涉及每个 import，高风险、需逐文件验证）。

**结论**：把全部模块强行统一到单一 Boot 主版本（如 3.2.5）在成本上不可接受，
且会砍掉 consortium 第二链 / exchange-wallet 的能力。**B1 的目标不是「单一 Boot 版本」，
而是「版本真统一 = 单一事实来源 + 消除伪统一 + 明确双轨」**。

---

## 2. 决策（Decision）

采用**显式双轨版本策略**，并消除根 `subprojects` 的强制污染：

### 2.1 双轨定义

| 轨道 | 模块 | Boot | Java | JUnit | 命名空间 |
|------|------|------|------|-------|----------|
| **Tier-1（现代）** | gateway, bridge | 3.2.5 | 17（toolchain） | 5（Jupiter） | jakarta |
| **Tier-2（遗产）** | core, consortium/consortium, consortium/crypto, consortium/common, exchange-wallet, sdk/java | 2.7.18 / 2.2.0 / 2.1.6 / — | 17 / 8 | 4（vintage）或 5 | javax |

> exchange-wallet / consortium 当前 Boot 主版本更低（2.1.6 / 2.2.0），属 Tier-2 内部
> 进一步的「亚版本」差异；它们与 core（2.7.18）同为 javax 命名空间，可在 Tier-2 内
> 后续统一到 2.7.18（见 §4 待办），但**不可跨到 Tier-1**。

### 2.2 已落地的 B1 切片（本批次，已改文件）

1. **根 `build.gradle` `subprojects.dependencies` 去污染**：删除强制的
   `junit:junit:4.13.2` 与 `spring-boot-starter-test:2.7.18`。
   - 收益：消除 Boot 2.7 测试 BOM 压在 Boot 3.2 应用上的版本冲突；暴露各模块真实测试配置。
   - 仅保留共享 `org.mockito:mockito-core`。
2. **根 `subprojects` 删除全局 `sourceCompatibility=17`/`targetCompatibility=17`**：
   每个模块本就显式声明自己的 Java 层级（gateway/bridge→toolchain 17、core→17、
   consortium/sdk/exchange-wallet→1.8），全局值被全覆盖且掩盖真相，删除后双轨 Java 策略诚实可见。
3. **根 `ext` 新增 `junitJupiterVersion = '5.10.2'`**：为后续 JUnit 5 统一提供单一常量。
4. **`nexus-exchange-wallet/build.gradle`**：`spring-boot-starter-test` 由「无版本（依赖根强制）」
   改为显式 `:2.1.6.RELEASE`，使其在移除根强制后仍能解析（其 boot 插件当前被注释，无 DM 兜底）。

### 2.3 推荐的统一机制（待真实构建环境验证后落地）

- **Boot 插件版本集中**：在 `settings.gradle` 用 `pluginManagement { plugins { id 'org.springframework.boot' version '3.2.5' ... } }`
  单源 Tier-1 Boot 插件版本，gateway/bridge 改为 `plugins { id 'org.springframework.boot' }`（去硬编码）。
  Tier-2 各模块 Boot 主版本不同（2.7/2.2/2.1.6），无法与 Tier-1 共用同一常量，维持各自声明
  （这是 Jakarta 墙的硬性结果，非遗漏）。
- **第三方版本目录**：可选引入 `gradle/libs.versions.toml` 将根 `ext` 的 20+ 常量收口为版本目录，
  消除「根 ext + 各模块重复声明（如 core 的 `springbootVersion` 拼写副本）」的漂移。

---

## 3. JUnit 策略

- **Tier-1（gateway/bridge）**：已是 JUnit 5（`useJUnitPlatform()`），保持。
- **Tier-2**：当前为 JUnit 4（core `useJUnit()`；sdk/crypto/common/exchange-wallet 直接 `junit:junit`）。
  - 统一到 JUnit 5 在每个 Tier-2 模块**技术上可行**（Boot 2.7 的 starter-test 自带 Jupiter +
    junit-vintage 可跑 JUnit 4），但要求**逐文件迁移现有测试注解**（`org.junit.Test` →
    `org.junit.jupiter.api.Test` 等）。
  - **本批次未盲目切换**：沙箱无 Gradle/JDK，无法编译验证迁移后测试仍可运行。
  - **待办**：在真实构建环境，按模块把 `useJUnit()` → `useJUnitPlatform()` 并迁移测试注解，
    再移除各模块的 `junit:junit` 直接依赖（改由 starter-test 提供）。

---

## 4. 后果（Consequences）

### 正面
- 消除「根强制 2.7 测试 BOM 压在 3.2 应用」的隐性冲突——这是潜在的 classpath 错乱源。
- 版本真相单一化：双轨策略显式写进构建文件与本文档，文档（ARCHITECTURE.md）已对齐。
- exchange-wallet 测试依赖不再「偷偷」依赖根强制，解析确定性提升。

### 负面 / 残留
- 仍有两个 Boot 主版本共存（3.2.5 vs 2.x）——这是 deliberate（双链 + javax 代码存量），
  **不是缺陷**，已在 ARCHITECTURE.md 与本文档固化。
- Tier-2 的 JUnit 4→5 迁移、Boot 插件版本目录化、`springbootVersion` 拼写副本清理，
  均需在**有 Gradle/JDK 的机器**上回归验证，本环境无法执行。

---

## 5. 待真实构建环境验证（⚠️ 必读）

本 ADR 的 §2.2 文件改动仅经**静态核验**（grep 依赖声明、Gradle DSL 语法人工审阅）。
以下必须在具备 Gradle 7.6.1 + JDK 17/8 的环境验证：

1. `./gradlew :nexus-gateway:test`（确认移除根强制后 JUnit 5 测试仍跑通）
2. `./gradlew :nexus-exchange-wallet:test`（确认显式 `:2.1.6.RELEASE` 可解析并跑通）
3. `./gradlew :nexus-core:test`（确认保留的 `junit:junit` + starter-test 2.7.18 仍跑通 JUnit 4）
4. `./gradlew :nexus-consortium:consortium:test` / `:nexus-sdk:java:test`（DM 解析验证）
5. `./gradlew build` 全量（确认无「version not found」/「conflict」）

验证通过后，方可推进 §2.3 / §3 的插件版本目录化与 JUnit 5 统一。