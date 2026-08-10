# nexus-core Protobuf 生成类说明（v1.9.2）

## 现状

以下两个文件是 protobuf 编译器生成的类，已被**作为源码提交**到仓库：

| 文件 | 包名 | 头部标注的源 proto |
|------|------|--------------------|
| `src/main/java/org/nexus/p2p/NexusChainOuterClass.java` (577 KB) | `org.nexus.p2p` | `nexus.proto` |
| `src/main/java/org/nexus/protobuf/tcp/ProtocolModel.java` (484 KB) | `org.nexus.protobuf.tcp` | `Protocol.proto` |

## 为什么保留在源码树（不移除、不改构建期生成）

1. **源 proto 已丢失**：生成类头部标注 `source: nexus.proto`，但仓库中**不存在** `nexus.proto`。
2. **现存 proto 与生成类不匹配**：仓库现存 `src/main/proto/NexusChain.proto` 声明
   `option java_package = "org.conpay.p2p"`，而提交的生成类位于 `org.nexus.p2p`，
   类名与包名均不一致。从现存 proto 构建期生成，产出的类无法匹配 7 处现有 import 引用。
3. 因此这两个生成类目前是**事实上的源码（source of truth）**，删除会直接破坏编译。

## 待办（恢复构建期生成的前置条件）

要从源码树移除这两个文件并改为构建期生成，需先：

1. **恢复或重建 `nexus.proto`**（从 `NexusChainOuterClass.java` 反推 schema，或找回原始文件）；
2. 将 proto 的 `java_package` / `java_outer_classname` 配置为与现有引用一致
   （`org.nexus.p2p` / `NexusChainOuterClass`）；
3. 在 `nexus-core/nexus-core/build.gradle` 接入 `com.google.protobuf` 插件，
   验证生成类与 7 处引用逐一匹配后，再删除提交的生成类。

> 注：`src/main/proto/NexusChain.proto`（org.conpay.p2p）与
> `src/main/java/org/nexus/protobuf/tcp/{Protocol,Hatch,Payment}.proto`（org.conpay.protobuf.tcp）
> 属于历史遗留的另一套包命名，与上述生成类无关，勿混淆。
