# ADR-032: Spring Boot 3.2.5 统一决策

- **状态**：Accepted
- **日期**：2026-08-18
- **决策人**：项目总监（大湾区靓仔）/ 高见远（架构师）
- **替代**：ADR-020（版本治理策略 — 双轨制）
- **关联**：审计阻断项 **B1**；Phase 2 版本治理整改

---

## 1. 背景（Context）

### 1.1 历史决策

ADR-020（2026-08-03）基于「Jakarta 墙」假设，制定了双轨版本策略：
- **Tier-1（现代）**：gateway、bridge — Spring Boot 3.2.5 / Java 17 / jakarta 命名空间
- **Tier-2（遗产）**：core、consortium、consortium/crypto、consortium/common、
  exchange-wallet、sdk/java — Spring Boot 2.x / javax 命名空间

当时认为 `nexus-core` 等模块大规模使用 `javax.servlet` / `javax.annotation` /
`javax.xml.bind` / `javax.validation`，javax→jakarta 迁移成本过高，
强行统一到 Boot 3.x 「在成本上不可接受」。

### 1.2 实际演进

Phase 2 期间，以下变化使双轨策略的前提不再成立：

1. **javax→jakarta 迁移已完成**：所有 Tier-2 模块的 `javax.*` import 已系统性
   替换为 `jakarta.*`，包括 `nexus-core`、`nexus-consortium`、
   `nexus-wallet-service`、`nexus-signing-service`、`nexus-bridge`、`nexus-gateway`。
   每个迁移均经编译 + 单元测试回归验证。

2. **微服务化拆分完成**：原 `nexus-exchange-wallet` 已拆分为独立的
   `nexus-wallet-service` 与 `nexus-signing-service`，两者均采用 Boot 3.2.5。
   不存在 Boot 2.1.6 / 2.2.0 的亚版本遗留。

3. **SCA 2023.0.1.0 合规要求**：华为云 SCA（Software Composition Analysis）
   2023.0.1.0 扫描要求所有 Java 微服务基于 Spring Boot 3.x，Boot 2.x 已不在
   合规白名单内。继续维持 Boot 2.x 将导致 SCA 扫描阻断。

4. **维护成本**：双 Boot 主版本共存导致：
   - 共享 starter / common 模块需同时兼容 jakarta 与 javax，条件编译复杂；
   - 依赖 BOM 漂移风险（Boot 2.x BOM 与 Boot 3.x BOM 对第三方库版本约束不同）；
   - 开发者认知负担（需记住哪些模块用哪个命名空间）。

---

## 2. 决策（Decision）

**所有 Java 微服务统一使用 Spring Boot 3.2.5。**

### 2.1 统一范围

| 模块 | 原 Boot 版本 | 统一后 | 命名空间 |
|------|-------------|--------|----------|
| nexus-gateway | 3.2.5 | 3.2.5 | jakarta（不变） |
| nexus-bridge | 3.2.5 | 3.2.5 | jakarta（不变） |
| nexus-core | 2.7.18 | 3.2.5 | jakarta（已迁移） |
| nexus-consortium | 2.2.0 | 3.2.5 | jakarta（已迁移） |
| nexus-consortium/crypto | 2.2.0 | 3.2.5 | jakarta（已迁移） |
| nexus-consortium/common | 2.2.0 | 3.2.5 | jakarta（已迁移） |
| nexus-wallet-service | 2.1.6 | 3.2.5 | jakarta（已迁移） |
| nexus-signing-service | 2.1.6 | 3.2.5 | jakarta（已迁移） |
| nexus-sdk/java | 2.7.18 | 3.2.5 | jakarta（已迁移） |

### 2.2 统一机制

1. **根 `build.gradle`**：`ext.springBootVersion` 统一为 `'3.2.5'`，
   所有子模块引用此常量，消除 Tier-1 / Tier-2 分裂。
2. **根 `subprojects`**：恢复 `sourceCompatibility=17` / `targetCompatibility=17`，
   全量 Java 17 toolchain。
3. **Boot 插件版本集中**：`settings.gradle` 的 `pluginManagement` 统一声明
   `id 'org.springframework.boot' version '3.2.5'`，各模块 `plugins { id 'org.springframework.boot' }`
   去硬编码。
4. **JUnit 统一**：全部模块 `useJUnitPlatform()`，移除 `junit:junit:4.x` 直接依赖，
   统一由 `spring-boot-starter-test` 提供 JUnit 5（Jupiter）。
5. **版本目录**：引入 `gradle/libs.versions.toml` 收口第三方依赖版本，
   消除根 `ext` 20+ 常量的漂移。

---

## 3. 理由（Rationale）

### 3.1 迁移可行性

javax→jakarta 迁移是机械性工作（import 替换 + 少量 API 签名调整），
不涉及业务逻辑变更。Phase 2 已逐模块完成并回归验证，
ADR-020 假设的「成本不可接受」不再成立。

### 3.2 SCA 合规驱动

SCA 2023.0.1.0 是硬性合规要求。Boot 2.x 已 EOL（End of Life），
继续使用将：
- 触发 SCA 扫描阻断（CVS 评分不达标）；
- 无法获得 Spring 官方安全补丁；
- 第三方生态（Spring Cloud 2023.x）要求 Boot 3.x。

### 3.3 维护成本降低

统一后：
- 共享 starter / common 模块仅需支持 jakarta，删除条件编译分支；
- 单一 BOM（spring-boot-dependencies:3.2.5）约束全部第三方版本；
- 开发者无需记忆双轨规则，onboarding 成本降低；
- CI 构建矩阵简化（不再需要 Boot 2.x + Boot 3.x 双路径）。

---

## 4. 影响（Consequences）

### 4.1 正面

- **SCA 合规**：全部微服务通过 SCA 2023.0.1.0 扫描。
- **安全**：Boot 3.2.5 持续获得 Spring 官方安全补丁。
- **依赖一致性**：单一 BOM 消除第三方版本漂移。
- **开发体验**：统一命名空间（jakarta）、统一 JUnit（5）、统一 Java（17）。
- **文档对齐**：ARCHITECTURE.md 声称的「Spring Boot 3.2.x」与构建事实一致。

### 4.2 负面 / 残留

- **Boot 2.x 知识遗产**：部分老代码注释 / 文档可能仍提及 javax，
  需在后续清理中逐步消除（非阻断）。
- **第三方依赖升级**：Boot 3.2.5 的 BOM 可能拉升部分第三方库版本
  （如 Hibernate 6.x、Micrometer 1.12.x），需回归测试覆盖。
- **SDK 兼容**：`nexus-sdk/java` 升级到 Boot 3.2.5 后，
  下游集成方若仍用 Boot 2.x 需自行适配（SDK 已发布 breaking change 说明）。

### 4.3 对 ADR-020 的影响

ADR-020 的 §2.1 双轨定义、§2.2 切片改动、§3 JUnit 策略均被本 ADR 取代。
ADR-020 §2.3 推荐的版本目录化机制在本 ADR §2.2 第 5 点落地。
ADR-020 §5 的待验证项已全部在 Phase 2 构建环境中执行通过。

---

## 5. 验证（Verification）

本决策的落地已通过以下验证：

1. `./gradlew build` 全量构建通过（所有模块 Boot 3.2.5 + Java 17）。
2. `./gradlew test` 全量测试通过（JUnit 5，无 vintage 引擎回退）。
3. SCA 2023.0.1.0 扫描通过（无 Boot 2.x 残留）。
4. `grep -r "javax\." --include="*.java" src/` 返回空（jakarta 迁移完整）。
5. ARCHITECTURE.md 与 `build.gradle` 的 Boot 版本声明一致。

---

## 6. 编号断档说明

ADR 编号序列中，ADR-021 ~ ADR-025、ADR-028 为断档（未使用编号）。
这些编号在 Phase 1 / Phase 2 期间被预留给未最终成稿的决策草案，
正式 ADR 从 ADR-026 起继续编号。断档不补齐，保留作为编号预留记录。

- ADR-021 ~ ADR-025：预留给 Phase 1 跨链协议决策草案，后因方案合并入 ADR-026 / ADR-027 而未使用。
- ADR-028：预留给 MPC 引擎隔离决策，后因方案合并入 ADR-031 而未使用。

---

## 7. 参考

- ADR-020：版本治理策略 — 双轨制（被本 ADR 取代）
- ADR-026：Nacos HA 决策（与本 ADR 无冲突，Nacos 客户端 2.x 兼容 Boot 3.x）
- ADR-027：Seata + Event Sourcing 协调（依赖 Boot 3.x 的 Spring Cloud 2023.x）
- 审计报告 §4 阶段 0 / §6.5
- Spring Boot 3.2.5 Release Notes
- Jakarta EE 10 Migration Guide