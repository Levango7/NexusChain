# B1 Spring Boot 四版本统一到 3.2.x 实施计划

## 第1章 概述

### 1.1 目标

将 NexusChain 项目中四个并存的 Spring Boot 版本统一到 **3.2.x**（推荐 3.2.5，与 gateway/bridge 现状对齐），同步完成相关模块的 JDK 1.8→17 升级与 `javax.*`→`jakarta.*` 命名空间迁移。本计划只描述改动步骤与验证检查点，不包含业务逻辑变更。

### 1.2 现状基线

表：B1 四版本现状基线表

| 模块 | Spring Boot 版本 | Spring Framework | Java 版本 | 构建方式 | javax 依赖 | 本地 lib jar |
| --- | --- | --- | --- | --- | --- | --- |
| nexus-gateway | 3.2.5（plugin） | 6.1.x（BOM 托管） | 17（toolchain） | Boot plugin | 无 | 无 |
| nexus-bridge | 3.2.5（plugin） | 6.1.x（BOM 托管） | 17（toolchain） | Boot plugin | 无 | 无 |
| nexus-core:nexus-core | 2.7.18（坐标） | 5.3.27（坐标） | 17（sourceCompat） | application plugin + 坐标 | servlet/annotation/validation/xml.bind/jaxb | libs/*.jar |
| nexus-exchange-wallet | 2.1.6.RELEASE（坐标） | 5.1.x（BOM 托管） | 1.8 | application plugin + 坐标 | annotation(PostConstruct) | lib/ 19 个 jar |
| nexus-consortium/consortium | 2.2.0.RELEASE（plugin） | 5.2.x（BOM 托管） | 1.8 | Boot plugin（composite includeBuild） | annotation/transaction/persistence | 无 |

根 `build.gradle` ext 声明 `springBootVersion='2.7.18'`、`springVersion='5.3.27'`、`springBootTestVersion='2.7.18'`，但实际无模块消费（core 自覆盖同名 ext，gateway/bridge 用 plugin 自带版本，wallet 硬编码，consortium 为 composite build 看不到根 ext）——属僵尸变量。

### 1.3 统一目标态

表：B1 统一目标态

| 模块 | Spring Boot | Spring Framework | Java | 命名空间 |
| --- | --- | --- | --- | --- |
| nexus-gateway | 3.2.5（不变） | 6.1.x | 17 | jakarta |
| nexus-bridge | 3.2.5（不变） | 6.1.x | 17 | jakarta |
| nexus-core:nexus-core | 3.2.5 | 6.1.x | 17 | jakarta |
| nexus-exchange-wallet | 3.2.5 | 6.1.x | 17 | jakarta |
| nexus-consortium/consortium | 3.2.5（选项B）或 2.2.0（选项A） | 6.1.x 或 5.2.x | 17 或 1.8 | jakarta 或 javax |

### 1.4 依赖与前置

- JDK 17 已就位（项目根 `jdk17/`、`jdk17.zip` 存在；gateway/bridge/core 已 17）。
- Gradle 7.6.1+（root wrapper）。Boot 3.2.5 要求 Gradle ≥ 7.6，满足。
- 本计划不改业务代码，但执行阶段（task id=3）会触及 `build.gradle` 与 Java 源文件的 import 行。
- 沙箱无法运行 `./gradlew build`，所有验证命令列于第8章供真机执行。

## 第2章 各模块升级路径

### 2.1 nexus-gateway / nexus-bridge（保持，零改动）

- 现状已是 Boot 3.2.5 + JDK 17 + jakarta，无需改动。
- 仅需在统一后跑一次 `./gradlew :nexus-gateway:build :nexus-bridge:build` 确认未被根 ext 清理波及（gateway/bridge 不引用根 ext 的 spring 变量，预期无影响）。
- 验证检查点：见 8.1。

### 2.2 nexus-core:nexus-core（Boot 2.7.18 + Spring 5.3.27 → 3.2.5 + 6.1.x）

core 是隐藏的第三大迁移点（任务描述未点名其 javax 依赖，但实际比 wallet 更重）。改动分四步：

1. **Spring Boot 坐标升级**：`nexus-core/nexus-core/build.gradle` 中 `springbootVersion='2.7.18'`→`'3.2.5'`，`springVersion='5.3.27'`→`'6.1.6'`（Spring 6.1.6 为 Boot 3.2.5 对应版本）。所有 `spring-boot-starter-*:${springbootVersion}` 与 `org.springframework:spring-*:${springVersion}` 坐标随之升级。
2. **javax→jakarta 依赖坐标迁移**（见 5.3 详细清单）：
   - `javax.servlet:javax.servlet-api:4.0.1` → `jakarta.servlet:jakarta.servlet-api:6.0.0`
   - `javax.annotation:javax.annotation-api:1.3.2` → `jakarta.annotation:jakarta.annotation-api:2.1.1`
   - `javax.validation:validation-api:2.0.1.Final`（两处） → `jakarta.validation:jakarta.validation-api:3.0.2`
   - `javax.xml.bind:jaxb-api:2.3.1` → `jakarta.xml.bind:jakarta.xml.bind-api:4.0.2`
   - `org.glassfish.jaxb:jaxb-runtime:2.3.9` → `org.glassfish.jaxb:jaxb-runtime:4.0.4`（Jakarta XML Binding 4）
   - `org.hibernate.validator:hibernate-validator:6.2.5.Final` → `7.0.1.Final`（HV 7 = jakarta 命名空间）
3. **Java 源码 import 迁移**：grep core 源码中 `import javax.servlet/annotation/validation/xml.bind/transaction/persistence` 并替换为 `jakarta.*`（执行阶段由 task 3 完成，本计划列出方法：`grep -rn "import javax\.\(servlet\|annotation\|validation\|xml\.bind\|transaction\|persistence\)" nexus-core/nexus-core/src`）。
4. **Spring 6 API 适配**：core 用 `spring-context` 与 `spring-boot-starter-web`，需检查 `WebMvcConfigurer` 等接口是否被实现（见 5.1）。core 的 `hibernateVersion='6.2.5.Final'` 变量名易混淆——实际仅用于 `hibernate-validator`，升级后该变量应改名为 `hibernateValidatorVersion='7.0.1.Final'` 以正名。

### 2.3 nexus-exchange-wallet（2.1.6 → 3.2.5，JDK 1.8→17，跨 2 个大版本）

wallet 跨 2 个大版本（2.1→3.0→3.2），是工作量最集中的模块。改动分五步：

1. **build.gradle 结构改造**：
   - 启用 Boot plugin：取消注释 `plugins { id 'org.springframework.boot' version '3.2.5'; id 'io.spring.dependency-management' version '1.1.4'; id 'java' }`，移除 `apply plugin: 'application'`（或保留 application plugin 与 Boot 共存——需决策，见下）。
   - `sourceCompatibility = '1.8'` → Java 17 toolchain（与 gateway 对齐：`java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }`）。
   - 废弃配置 `compile`→`implementation`、`testImplementation`（Gradle 7+ 已弃用 `compile`）。
   - `mainClassName` → `application { mainClass = 'org.nexus.wallet.ServerApplication' }`（若保留 application plugin）或 Boot 的 `springBoot { mainClass = ... }`。
   - `configurations.runtime`/`configurations.compile`（copyDependencies、fatJar 任务）→ `runtimeClasspath`/`implementation`。
   - `classifier = 'all'`（fatJar）→ `archiveClassifier = 'all'`（Gradle 7+ API）。
2. **依赖坐标升级**：`spring-boot-starter-web:2.1.6.RELEASE` → `spring-boot-starter-web`（无版本，由 Boot plugin BOM 托管）；`spring-boot-starter-test:2.1.6.RELEASE` → `spring-boot-starter-test`。
3. **本地 lib jar 清理**（见 5.4 详细清单）：`lib/` 下 19 个 jar 中，`spring-context-5.1.2.RELEASE.jar`、`validation-api-2.0.0.Final.jar`、`jackson-annotations-2.9.0.jar`、`commons-logging-1.0.4.jar` 等与 Boot 3.2 BOM 严重冲突，必须移除或替换为 BOM 托管版本。
4. **javax→jakarta 源码迁移**：仅 2 处 `javax.annotation.PostConstruct`（见第3章清单）。
5. **JDK 1.8→17 源码扫描**：13 个 Java 文件检查钻石语法、try-with-resources 等无需改；重点检查是否有 Java 8 内部 API（sun.misc.* 等）及被移除 API。

### 2.4 nexus-consortium/consortium（2.2.0 → 3.2.5 或维持隔离）

consortium 为 composite build（`includeBuild 'nexus-consortium'`），具备独立 `settings.gradle`/`config.gradle`/Java 8/Boot 2.2。两种选项的取舍见第4章。本节给出选项B（统一升级）的路径：

1. `consortium/build.gradle`：plugin `org.springframework.boot` version `2.2.0.RELEASE`→`3.2.5`，`io.spring.dependency-management` `1.0.8.RELEASE`→`1.1.4`。
2. `sourceCompatibility = 1.8` → Java 17 toolchain。
3. `compile`/`testCompile` → `implementation`/`testImplementation`（Gradle 7+ 弃用）。
4. `config.gradle` 依赖版本升级：`lombokVersion='1.18.10'`→`'1.18.32'`（JDK 17 + Boot 3 兼容）、`jacksonVersion='2.10.0'`→`'2.14.2'`、`guavaVersion='28.1-jre'`→`'31.1-jre'`、`gRPCVersion='1.25.0'`→`'1.54.0'`、`bouncycastleVersion='1.61'`→`'1.70'`。
5. javax→jakarta 源码迁移：5 处真实 Jakarta EE 迁移（见 4.3）。
6. `common`/`crypto` 子模块：`sourceCompatibility = 1.8`→17；`crypto` 的 `javax.crypto.*` 是 JDK 内置 JCA，**不迁移**。

## 第3章 wallet javax→jakarta 包名迁移清单

### 3.1 Java 源码 import 迁移

grep 结果（`import javax\.` in `nexus-exchange-wallet`）仅 2 处，均为 `javax.annotation.PostConstruct`：

表：wallet Java 源码 javax→jakarta 迁移清单

| 文件 | 行号 | 原始 import | 替换为 |
| --- | --- | --- | --- |
| `nexus-exchange-wallet/src/main/java/org/nexus/wallet/keystore/PlatformKeystore.java` | 9 | `import javax.annotation.PostConstruct;` | `import jakarta.annotation.PostConstruct;` |
| `nexus-exchange-wallet/src/main/java/org/nexus/wallet/Leveldb/Leveldb.java` | 7 | `import javax.annotation.PostConstruct;` | `import jakarta.annotation.PostConstruct;` |

说明：`javax.annotation.PostConstruct` 在 JDK 9+ 已从 `javax.annotation-api` 移至 `jakarta.annotation-api`（Jakarta Annotations 2.1）。Boot 3.2 的 `spring-boot-starter-web` 传递依赖 `jakarta.annotation:jakarta.annotation-api:2.1.1`，故迁移后无需额外声明坐标。

### 3.2 wallet 源码中其他 javax 命名空间扫描结果

- `javax.servlet`：0 处（wallet 控制器未直接 import servlet API，由 starter-web 传递）。
- `javax.validation`：0 处源码 import；但 `lib/validation-api-2.0.0.Final.jar` 存在，属依赖坐标迁移（见 3.3）。
- `javax.persistence`：0 处（wallet 未用 JPA）。
- `javax.transaction`：0 处。
- `javax.xml.bind`：0 处。

结论：**wallet 的 javax→jakarta 源码迁移工作量极小（2 行）**，真正工作量在 build.gradle 改造与本地 lib jar 清理（见 3.3、5.4）。

### 3.3 wallet 本地 lib jar 中的 javax 依赖

表：wallet lib jar 中需处理的 javax/冲突 jar

| jar | 问题 | 处理 |
| --- | --- | --- |
| `validation-api-2.0.0.Final.jar` | javax.validation 2.0，与 Boot 3.2 的 jakarta.validation 3.0 冲突 | 删除，改由 `spring-boot-starter-validation` 传递 `jakarta.validation:jakarta.validation-api:3.0.2` |
| `spring-context-5.1.2.RELEASE.jar` | Spring 5.1，与 Boot 3.2 的 Spring 6.1 直接冲突 | 删除，由 `spring-boot-starter-web` 传递 spring-context 6.1 |
| `jackson-annotations-2.9.0.jar` | Jackson 2.9，与 Boot 3.2 的 Jackson 2.15 冲突 | 删除，由 BOM 托管 |
| `commons-logging-1.0.4.jar` | 古老 commons-logging，Boot 用 jcl-over-slf4j 桥接 | 删除 |
| `bcprov-jdk15on-1.61.jar` | BC 1.61，JDK17 下建议 bcprov-jdk18on 1.70 | 替换为 `org.bouncycastle:bcprov-jdk18on:1.70`（坐标） |
| `guava-24.1-jre.jar` / `gson-2.8.5.jar` / `commons-*` | 版本过旧 | 评估：若仅 wallet 用则升级到根 ext 对齐版本，或由 BOM 托管 |
| `protobuf-java-3.6.1.jar` | 与 core 的 protobuf 3.22.2 不一致 | 升级到 3.22.2 或由根统一 |
| `jna-4.5.2.jar` / `jnaerator-runtime-0.12.jar` / `ochafik-util-0.12.jar` | jnaerator 0.12 极旧 | 需确认是否仍被 `wcli.jar`/keystore 使用；若否删除，若是评估 JDK17 兼容 |
| `jargon2-*-1.1.1.jar` | 与 core/jargon2 1.1.1 一致 | 保留或改坐标 |
| `wcli.jar` | 自研/第三方不明 | 需人工确认来源与 JDK17 兼容性，保留待验证 |

## 第4章 consortium composite build 隔离下的升级策略

### 4.1 composite build 版本统一性结论

`includeBuild 'nexus-consortium'` 使 consortium 作为**独立 Gradle 构建**被组合进来，它保留自己的 `settings.gradle`、`config.gradle`、构建脚本，**不继承根 `build.gradle` 的 ext 与 allprojects/subprojects 配置**。因此：

- consortium 的 Spring Boot 版本**技术上可保持独立**，不强制统一。根构建仅消费 consortium 的构建产物（通过 dependency substitution），不要求版本一致。
- 但若 consortium 产物被 root 模块以依赖方式消费（如 gateway/core 依赖 consortium 的 jar），则运行时 classpath 会出现 Boot 2.2 与 Boot 3.2 双版本冲突——需检查是否存在此类依赖。当前 settings.gradle 注释表明 consortium 是"与 nexus-core 并列的联盟链/侧链"，定位为独立链，预计无跨依赖；执行阶段需 `grep -rn "consortium" nexus-gateway nexus-bridge nexus-core nexus-exchange-wallet --include=build.gradle` 确认。

### 4.2 两种选项取舍分析

表：consortium 升级选项对照表

| 维度 | 选项A：维持 composite 隔离（Boot 2.2 + Java 8） | 选项B：统一升级（Boot 3.2.5 + Java 17） |
| --- | --- | --- |
| 工作量 | 极小（零代码改动） | 中等（5 处 javax→jakarta + build.gradle + config.gradle 版本 + JDK17 验证） |
| 风险 | 低；但长期技术栈分裂 | 中；JPA/Hibernate 6 包名、Spring 6 API 需验证 |
| 一致性 | 不达成"四版本统一"目标 | 达成统一目标 |
| Java 8 维护成本 | 需保留 JDK8 工具链，CI 复杂 | 消除，全栈 JDK17 |
| composite 隔离价值 | 保留，可独立回滚 | 仍保留（composite 结构不变，仅版本升级） |
| 与用户决策关系 | 违背"统一到 3.2.x"决策 | 符合 |
| 跨依赖冲突风险 | 若 gateway/core 依赖 consortium 产物则冲突 | 无 |

### 4.3 取舍建议

**推荐选项B（统一升级），但作为独立最后一步、独立 commit、可独立回滚。** 理由：

1. 用户已明确决策"统一到 3.2.x"，选项A 不达成目标。
2. consortium 的 javax→jakarta 真实迁移点仅 5 处（见下），工作量可控。
3. composite build 结构不变，升级后仍可独立回滚（revert 单 commit 即恢复 Boot 2.2）。
4. 消除 JDK 8 工具链，CI/开发环境统一 JDK 17。

**前提条件**：执行选项B 前必须先确认无 root 模块依赖 consortium 产物（4.1 末 grep 命令）；若存在跨依赖，则选项A 的"零冲突"前提不成立，更应选 B 消除冲突。

表：consortium javax→jakarta 真实迁移清单（排除 javax.crypto）

| 文件 | 行号 | 原始 import | 替换为 |
| --- | --- | --- | --- |
| `consortium/src/main/java/org/nexus/consortium/SimpleBean.java` | 11 | `import javax.annotation.PostConstruct;` | `import jakarta.annotation.PostConstruct;` |
| `consortium/src/main/java/org/nexus/consortium/service/BlockRepositoryService.java` | 14 | `import javax.transaction.Transactional;` | `import jakarta.transaction.Transactional;` |
| `consortium/src/main/java/org/nexus/consortium/entity/Transaction.java` | 8 | `import javax.persistence.*;` | `import jakarta.persistence.*;` |
| `consortium/src/main/java/org/nexus/consortium/entity/HeaderAdapter.java` | 5 | `import javax.persistence.*;` | `import jakarta.persistence.*;` |
| `consortium/src/main/java/org/nexus/consortium/entity/Header.java` | 3-5 | `import javax.persistence.{Entity,Index,Table};` | `import jakarta.persistence.{Entity,Index,Table};` |
| `consortium/src/main/java/org/nexus/consortium/entity/Block.java` | 10 | `import javax.persistence.*;` | `import jakarta.persistence.*;` |

注：`crypto/` 下 15 处 `javax.crypto.*`（SM4Util.java、AES256CTR.java）是 JDK 内置 JCA API，**不属于 Jakarta EE 迁移范围**，Java 17 保留 `javax.crypto`，不改动。

## 第5章 根 ext 变量处理

### 5.1 现状

根 `build.gradle` 的 `subprojects { ext { ... } }` 声明 30 个版本变量，其中 Spring 相关三个：

```
springVersion = '5.3.27'
springBootVersion = '2.7.18'
springBootTestVersion = '2.7.18'
```

### 5.2 消费情况核查

- `springVersion`：core 自身 `ext` 重新声明 `springVersion='5.3.27'`（覆盖根的），实际消费方是 core 自己的覆盖值；根值未被消费。
- `springBootVersion`：core 自身 ext 声明 `springbootVersion='2.7.18'`（注意小写 b，与根的 `springBootVersion` 不同名，故根值未被覆盖也未被消费）；gateway/bridge/wallet/consortium 均不引用根 ext 的 `springBootVersion`。
- `springBootTestVersion`：根 ext 声明但 grep 全项目无 `${springBootTestVersion}` 引用——纯僵尸。

结论：三个 Spring 相关 ext 变量均为**僵尸变量**，删除不影响任何模块构建。

### 5.3 推荐方案

**删除** `springVersion`、`springBootVersion`、`springBootTestVersion` 三个变量；各模块自声明 Spring Boot plugin 版本（gateway/bridge 已是范例：`plugins { id 'org.springframework.boot' version '3.2.5' }`）。

理由：
1. **单一事实源**：plugin 版本写在 `plugins {}` 块最直观，避免 ext 与 plugin 双源。
2. **避免误导**：根 ext 留 `2.7.18` 会让人误以为项目基线是 2.7，与实际 3.2 矛盾。
3. **composite 隔离**：consortium 本就看不到根 ext，自声明是唯一选项，统一模式即"各模块自声明"。
4. **保留非 Spring 变量**：`jacksonVersion`/`guavaVersion`/`nettyVersion`/`grpcVersion` 等仍可保留作共享版本锚（多模块共用），但应核查实际消费方，清理无人引用者。

### 5.4 统一版本锚的替代方案（可选）

若希望保留一个统一版本锚便于未来全栈升级，可在根 ext 保留单一变量：

```
ext { springBootVersion = '3.2.5' }  // 统一锚，各 plugin 块可用 id 'org.springframework.boot' version "${springBootVersion}"
```

但 Gradle `plugins {}` 块不能直接引用 ext（需通过 `pluginManagement` 或 buildSrc）。因此实践中各模块 plugin 块硬编码版本更简单。**本计划采用 5.3 的"各模块自声明 + 删除根 Spring ext"方案。**

## 第6章 预期冲突点

### 6.1 Spring 6 的 AOT / 原生镜像 hints

- Boot 3.x 默认启用 AOT 处理与 GraalVM 原生镜像支持。普通 JVM 运行不强制 AOT，但若模块配置了 `spring-boot-starter-aot` 或 `nativeBuildtools`，需提供 `RuntimeHints`。
- nexus-core/wallet/consortium 当前均为普通 JVM 应用（application plugin / Boot plugin 无 native 配置），**预期无 AOT 强制**。
- 风险点：core 用 `spring-context` 的 bean 注册，若存在 `BeanFactoryPostProcessor` 或 `@Conditional` 复杂逻辑，Boot 3 的 `BeanFactoryInitializationAotContribution` 可能在测试时告警。验证：真机跑 `./gradlew :nexus-core:nexus-core:test` 观察 AOT 相关 warning。

### 6.2 Boot 3 对部分 API 的移除

- `WebMvcConfigurer`：Boot 3 仍保留 `WebMvcConfigurer`（Spring 6 未移除），但其部分默认方法行为变更（如 trailing slash 匹配默认关闭）。core/wallet/consortium 的 `@Configuration` 类若实现 `WebMvcConfigurer` 需检查。执行阶段 grep：`grep -rn "WebMvcConfigurer\|WebMvcConfigurerAdapter\|SpringBootServletInitializer" nexus-core nexus-exchange-wallet nexus-consortium`。`WebMvcConfigurerAdapter` 在 Spring 5 已移除，若残留需改直接 implement `WebMvcConfigurer`。
- `spring.factories` → `AutoConfiguration.imports`：Boot 3 改用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。若模块有自定义 auto-configuration 注册在旧 `spring.factories`，需迁移。检查：`find nexus-core nexus-exchange-wallet nexus-consortium -name "spring.factories"`。
- `TrailingSlash` 匹配：Spring 6 默认关闭尾斜杠匹配，若 controller 路径以 `/` 结尾且前端调用带/不带斜杠，需显式 `configurer.setUseTrailingSlashMatch(true)` 或修正前端。

### 6.3 wallet 本地 lib jar 与 Boot 3 兼容性

- `spring-context-5.1.2.RELEASE.jar`：**致命冲突**。Spring 6 与 Spring 5 同 classpath 会导致 `NoSuchMethodError`/`ClassCastException`。必须删除（3.3）。
- `validation-api-2.0.0.Final.jar`：javax.validation 2.0 的 `@Valid`/`@NotNull` 注解类与 jakarta.validation 3.0 的全限定名不同，混用会导致校验静默失效。必须删除。
- `jackson-annotations-2.9.0.jar`：Jackson 2.9 `@JsonProperty` 等与 Boot 3.2 的 Jackson 2.15 二进制兼容但版本差大，建议删除由 BOM 托管。
- `wcli.jar`/`jnaerator-runtime-0.12.jar`/`jna-4.5.2.jar`：来源不明、版本极旧，JDK 17 下 `sun.misc.Unsafe` 相关反射可能被 JDK 17 强模块化阻断。需真机启动 wallet 验证（`./gradlew :nexus-exchange-wallet:bootRun`）。
- `bcprov-jdk15on-1.61.jar`：BC 1.61 在 JDK 17 下可运行但已不维护，建议 `bcprov-jdk18on:1.70`。

### 6.4 Hibernate 6 包名变化

- Hibernate ORM 5→6：包名 `org.hibernate` 主体保留，但 `javax.persistence.*`→`jakarta.persistence.*`（JPA 3）。consortium 的 4 处 `javax.persistence.*` 迁移即对应此。
- `HibernateValidator` 6→7：`javax.validation`→`jakarta.validation`。core 的 `hibernate-validator:6.2.5.Final`→`7.0.1.Final` 对应。
- Boot 3.2 默认 Hibernate ORM 6.4，若 consortium/core 有 `@Entity`/`@Table`/`@Id` 等 JPA 注解，迁移后需确认 `jakarta.persistence.*` 的注解类被 Hibernate 6 识别。
- 风险：Hibernate 6 移除部分废弃 API（如 `Criteria` 旧 API、`Query.setResultTransformer`）。consortium 的 `*Dao.java`/`*RepositoryService.java` 需检查。grep：`grep -rn "setResultTransformer\|Criteria\|HibernateCallback" nexus-consortium/consortium/src`。

### 6.5 Gradle 配置弃用

- `compile`/`testCompile`/`runtime` 配置在 Gradle 7+ 已弃用，Gradle 8 将移除。wallet 与 consortium 大量使用 `compile`，升级时必须改 `implementation`/`testImplementation`/`runtimeClasspath`，否则 Gradle 8 构建失败。
- `configurations.compile`/`configurations.runtime`（wallet 的 copyDependencies/fatJar）→ `configurations.implementation`/`runtimeClasspath`，注意 `implementation` 不可解析（需用 `runtimeClasspath`）。

### 6.6 Lombok 与 JDK 17

- consortium `lombokVersion='1.18.10'` 不支持 JDK 17（Lombok 1.18.10 仅支持到 JDK 15）。需升级到 `1.18.32`+。core 若用 Lombok 同理检查。

## 第7章 回滚方案

### 7.1 回滚粒度原则

按模块分步，每步一个独立 commit，可独立回滚（`git revert <commit>`）。推荐提交顺序与回滚边界：

表：B1 分步提交与回滚边界

| 步骤 | commit 范围 | 模块 | 可独立回滚 | 依赖前置 |
| --- | --- | --- | --- | --- |
| S1 | 根 ext 清理（删除 springVersion/springBootVersion/springBootTestVersion） | 根 build.gradle | 是 | 无 |
| S2 | nexus-core 升级（坐标+javax→jakarta 依赖+源码 import） | nexus-core/nexus-core | 是 | S1（避免 ext 干扰） |
| S3 | nexus-exchange-wallet 升级（build.gradle+lib 清理+2 处 import+JDK17） | nexus-exchange-wallet | 是 | S1 |
| S4 | nexus-consortium 升级（选项B：build.gradle+config.gradle+5 处 import+JDK17） | nexus-consortium | 是（composite 隔离） | S1 |
| S5 | 全量验证与修复（API 适配、AOT hint、Hibernate 6 适配） | 跨模块 | 否（修复性） | S2,S3,S4 |

### 7.2 各步回滚命令

命令示例：各步回滚命令

```
# 回滚 S1（恢复根 ext Spring 变量）
git revert <S1-commit>

# 回滚 S2（core 恢复 Boot 2.7.18 + javax）
git revert <S2-commit>

# 回滚 S3（wallet 恢复 Boot 2.1.6 + JDK8 + lib jar）
git revert <S3-commit>

# 回滚 S4（consortium 恢复 Boot 2.2 + JDK8，composite 隔离使其不影响其他模块）
git revert <S4-commit>
```

### 7.3 回滚注意事项

- S2/S3/S4 之间无代码依赖（各模块独立构建），可任意顺序回滚。但 S1 删除根 ext 后，若单独回滚 S1 而不回滚 S2/S3/S4，则根 ext 恢复的 `2.7.18` 变量再次成为僵尸（无害但不一致）。建议 S1 与 S2/S3/S4 一起回滚或保留 S1。
- S4 因 composite build 隔离，回滚最安全——consortium 恢复 Boot 2.2 后，root 构建不受影响（前提：4.1 确认无跨依赖）。
- lib jar 删除（S3）回滚需 `git checkout <prev> -- nexus-exchange-wallet/lib/` 恢复被删 jar 文件。

## 第8章 验证检查点

### 8.1 gateway / bridge（S0 基线确认）

命令示例：gateway/bridge 不变验证

```
./gradlew :nexus-gateway:build :nexus-bridge:build
./gradlew :nexus-gateway:test :nexus-bridge:test
```

预期：与升级前一致（零改动）。

### 8.2 nexus-core（S2 后）

命令示例：core 升级验证

```
# 编译
./gradlew :nexus-core:nexus-core:compileJava
# 单元测试
./gradlew :nexus-core:nexus-core:test
# 依赖树确认无 javax 残截、无 Spring 5 残留
./gradlew :nexus-core:nexus-core:dependencies --configuration runtimeClasspath | grep -E "javax\.|spring-context:5"
# jakarta 依赖确认
./gradlew :nexus-core:nexus-core:dependencies --configuration runtimeClasspath | grep -E "jakarta\."
```

### 8.3 nexus-exchange-wallet（S3 后）

命令示例：wallet 升级验证

```
# 编译（JDK17）
./gradlew :nexus-exchange-wallet:compileJava
# 测试
./gradlew :nexus-exchange-wallet:test
# 启动验证（关键：lib jar JDK17 兼容性）
./gradlew :nexus-exchange-wallet:bootRun
# 依赖树确认无 Spring 5.1 / javax.validation 2.0 残留
./gradlew :nexus-exchange-wallet:dependencies --configuration runtimeClasspath | grep -E "spring-context:5\.1|validation-api-2"
# 确认 lib jar 已清理
ls nexus-exchange-wallet/lib/
```

### 8.4 nexus-consortium（S4 后，选项B）

命令示例：consortium 升级验证

```
# composite build 下需进入 consortium 目录或用 included build
cd nexus-consortium && ./gradlew :consortium:compileJava :consortium:test
# 或从根
./gradlew :nexus-consortium:consortium:compileJava
# 依赖树确认 Boot 3.2.5、jakarta.persistence
./gradlew :nexus-consortium:consortium:dependencies --configuration runtimeClasspath | grep -E "spring-boot:3\.2\.5|jakarta\.persistence"
# JPA 实体扫描确认 Hibernate 6 识别
./gradlew :nexus-consortium:consortium:test --tests "*RepositoryService*"
```

### 8.5 全量集成（S5 后）

命令示例：全量构建验证

```
# 全量构建
./gradlew buildAll
# 全量测试
./gradlew testAll
# 跨模块依赖一致性
./gradlew dependencies --configuration runtimeClasspath | grep -E "spring-boot:[0-9]" | sort -u
# 预期输出仅 3.2.5 一行
```

### 8.6 运行时冒烟（真机）

命令示例：运行时冒烟测试

```
# 各服务启动
./gradlew :nexus-gateway:bootRun &
./gradlew :nexus-bridge:bootRun &
./gradlew :nexus-exchange-wallet:bootRun &
# consortium
cd nexus-consortium && ./gradlew :consortium:bootRun &
# 健康检查（若有 actuator）
curl http://localhost:<port>/actuator/health
```

## 第9章 关键结论摘要

### 9.1 wallet 迁移工作量评估

- **javax→jakarta 源码迁移：极小（2 行 import）**。wallet 仅 13 个 Java 文件，javax 用法只有 2 处 `javax.annotation.PostConstruct`。
- **真正工作量集中在三处**：
  1. `build.gradle` 结构改造（plugin 启用、compile→implementation、application/Boot 共存决策、JDK17 toolchain、废弃 API 替换）——约 15 行改动。
  2. 本地 `lib/` 19 个 jar 清理——其中 `spring-context-5.1.2`、`validation-api-2.0.0`、`jackson-annotations-2.9.0`、`commons-logging-1.0.4` 必删，`bcprov-jdk15on-1.61` 必换，`wcli`/`jnaerator`/`jna` 需人工确认 JDK17 兼容——这是**最大风险点**。
  3. JDK 1.8→17 源码扫描（13 文件，预计无语法问题，重点查 sun.misc/内部 API）。
- **结论：wallet 的"跨 2 个大版本"难度不在包名迁移，而在本地 lib jar 的兼容性治理。建议 S3 前先逐 jar 确认来源与 JDK17 可用性，不明 jar 单独隔离验证。**

### 9.2 consortium 取舍建议

- **推荐选项B（统一升级到 Boot 3.2.5 + JDK17）**，作为独立最后一步 S4，独立 commit，可独立回滚。
- 理由：符合用户"统一到 3.2.x"决策；javax→jakarta 真实迁移仅 5 处（1 PostConstruct + 1 Transactional + 4 persistence）；composite build 结构不变仍保隔离回滚能力；消除 JDK8 工具链。
- **前提**：执行前 grep 确认无 root 模块依赖 consortium 产物；若有跨依赖，选项A 的"零冲突"前提不成立，更应选 B。
- consortium 的 15 处 `javax.crypto.*` 是 JDK 内置 JCA，**不迁移**，避免误改。

### 9.3 nexus-core 是隐藏的第三大迁移点

- 任务描述只点名 wallet 跨 2 大版本，但 core 实际也是 Boot 2.7.18 + Spring 5.3.27 + javax（servlet/annotation/validation/xml.bind/jaxb + hibernate-validator 6），升级工作量与 wallet 相当甚至更大（依赖坐标迁移 6 处 + 源码 import 扫描）。
- **建议 S2 与 S3 并列为本次统一的核心工作项，不可遗漏 core。**

### 9.4 根 ext 变量处理结论

- 删除 `springVersion`/`springBootVersion`/`springBootTestVersion` 三个僵尸变量，各模块自声明 plugin 版本（gateway/bridge 范式）。保留非 Spring 的共享版本锚（jackson/guava/netty 等）待后续清理。

### 9.5 整体风险排序

1. **最高**：wallet 本地 lib jar（spring-context-5.1.2、wcli、jnaerator）的 JDK17/Boot3 兼容性——需真机启动验证。
2. **高**：core 的 javax→jakarta 依赖坐标 + 源码迁移（6 处坐标 + 未知数量源码 import，需 grep 确认）。
3. **中**：consortium 的 Hibernate 6/JPA 3 适配（4 处 persistence + Dao/RepositoryService API 检查）。
4. **低**：wallet 的 2 处 PostConstruct、consortium 的 PostConstruct/Transactional。
5. **低**：gateway/bridge 零改动。

### 9.6 交付物

- 本计划：`.codeartsdoer/plans/b1-spring-unify/plan.md`
- 后续执行：task id=3 按 S1→S2→S3→S4→S5 顺序实施，每步独立 commit，真机 `./gradlew buildAll` 验证。