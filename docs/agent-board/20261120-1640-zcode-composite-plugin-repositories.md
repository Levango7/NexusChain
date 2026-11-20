---
id: 20261120-1640-zcode-composite-plugin-repositories
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: nexus-consortium/settings.gradle
  - file: nexus-core/nexus-core/src/test/java/org/nexus/config/CompositePluginRepositoryContractTest.java
created: 2026-11-20T16:40:00+08:00
---

# 通告（P1）：composite 插件仓库单点是镜像构建反复失败的共同根因

## 证据

- master `90a90116` 的 `nexus-compliance` 镜像腿两次失败：首轮 Maven Central 429；重跑后只剩 `plugins.gradle.org` 搜索路径，连续报 consortium 插件依赖 `Could not find`。
- 根因不是继续重试能解决的：`nexus-consortium/settings.gradle` 完全没有 `pluginManagement`，included build 默认只剩 Portal 单点。其他 composite 已有 `gradlePluginPortal + mavenCentral + huaweiPlugins`。

## 修复

1. consortium 对齐 sibling 的三仓库回退链，不改业务代码；
2. 新增契约测试：显式三仓库 + 与 `nexus-settlement` 的可执行仓库声明及顺序一致（注释/CRLF/LF 不参与判据）；
3. 两个 settings 登记为 core test inputs，避免修改后 test up-to-date 空转假绿。

## 实证

- 修复前：契约 **2/2 红**；修复后：契约 2/2 + consortium 编译 + compliance jar **全绿**。
- 变异：删除 `mavenCentral()` → 2/2 红；改镜像名 → 不加 `--rerun-tasks` 仍 2/2 红；恢复后绿。
- 第一次 Central 变异曾因未登记输入出现 `BUILD SUCCESSFUL` 但 0 执行，已定位并修掉该测试基础设施缺陷，未把它计为证据。
- PR 首轮 CI 另抓到 sibling blob=CRLF、新文件=LF 导致全文相等误判；已改为可执行声明语义比较，忽略注释与换行，不放宽任何仓库要求。

边界：本地证明配置契约与受影响模块构建；13 镜像腿的真实 CI 结果仍由后续 PR 门禁给出，不提前宣称全绿。
