# 方案 PLAN-031：FIFO IPC 守护线程化 + 总开关（修复 PLAN-027 发现 1 / P1）

> 承接 PLAN-027 §五后续顺序最后一项修复。首两个修复切片：
> PLAN-029/030（F2/F1 一并交付）。本切片完成后四项缺陷全部修复，
> 具备重跑多机实验（`停 C 60s → 恢复 → 补票追平` 闭环）的条件。

## 一、问题

`org.nexus.ipc.Fifo` 实现 `ApplicationRunner`。Linux 分支：

```java
run(args) → initFifo() → while(true) reader.read(...) → close
```

`initFifo()` 里 `new FileReader(readFile)` 打开 FIFO **读端** —— POSIX 语义
下该 open **阻塞直到有写端连接**。此阻塞发生在
`SpringApplication.callRunners()` 内、**先于 ApplicationReadyEvent**：

- 后果链：`ValidatorNodeBootstrapper`（监听 ready）永不执行 ⇒ 不自注册、
  不广播验证人集、最终性协调器不装填 ⇒ 不出块（多机实验线程栈实证：
  main 卡 `Fifo.initFifo` → `callRunners`）。
- 影响面：任何**带 mkfifo 的 Linux**（WSL/Ubuntu 服务器/完整发行版）卡死；
  生产 core 镜像（distroless 无 mkfifo）走 `catch(IOException)` 优雅降级
  **恰好幸免** —— 所以这个 P1 在生产容器里"看不出"，但在非 distroless
  Linux 部署（运维直跑 JVM 的形态）是 100% 必现。

## 二、修复

- **守护线程化**：`run()` 改为启动单个守护线程（`nexus-fifo-ipc`）执行
  `initFifo() + 读循环 + closeFifo()`，main 立即返回——ApplicationReadyEvent
  照常发布（自注册/出块/最终性不再被 IPC 拖累），IPC 功能照常可用。
- **并发面说明**：`ctx.publishEvent` 同步分发（事件 listener 与读循环
  同在 worker 线程）；`writer` 字段由 worker 写入、事件 handler 由同一
  worker 线程读取——**无新增跨线程可见性面**（原 main-only 结构的
  阻塞路径本来就比 REST 命令路径特异，REST+FIFO 并发面不变）。
- **异常语义保留**：init 失败（mkfifo 不可用）→ 优雅降级退出 worker
  （与旧行为 catch(IOException) 一致）；`InterruptedException` → 恢复
  中断标志并退出；读循环 异常 → warn 退出（守护线程不拖 JVM）。
- **总开关**：`nexus.ipc.enabled`（默认 **true** 保持既有行为；`false`
  完全跳过——容器/CI 无 mkfifo 环境的显式逃生口，替代"Link distroless
  恰好因 mkfifo 缺失而绕开"的偶然刚好）。
- **可观测**：`lastFifoWorkerThread`（包内可见）记录 worker 引用，
  供测试与诊断不依赖线程枚举判定。

## 三、测试（nexus-core，全部通过）

`FifoBootNotBlockedTest` 4 例（跨平台稳定：子类显式模拟 isLinuxSystem）：

| 用例 | 验证点 |
|------|--------|
| `linuxRunReturnsBeforeFifoReadable` | **核心**：Linux 路径 `run()` 3s 内返回（旧行为卡死 initFifo） |
| `workerThreadRunsOnLinux` | mkfifo 可用环境：daemon worker 后台存活 |
| `disabledIpcNeverSpawnsWorker` | `nexus.ipc.enabled=false` 零线程创建 |
| `windowsPathNeverSpawnsWorker` | 非 Linux 语义不变（无 worker） |

## 四、明确边界（勿夸大）

- **Windows 真机单测不逐字节复刻 POSIX**：windowsPath 场景由子类模拟语义
  （CI runner ubuntu 上跑 mkfifo 存活性用例；本机 Windows 上 skip）。
- **真实验证在 WSL**：本切片落库后，多机重跑实验时把 B 的 `/usr/bin/mkfifo`
  **改回原名**（此前为绕行而改名）—— systemd 单元 `nexus-b` 启动应当
  直接可 ready + 出块，即"Linux 非 distroless 启动"回归在本实验里一并完成。
- **读循环 `exit` 语义保留**：读到 "exit" 载荷退出并关闭——旧行为不变。
- **事件风暴面**：读循环 1024 字符切片 → 事件 per 切片，与旧行为相同，
  不在本切片改报文协议。

## 五、后续顺序

1. 四项缺陷修复完毕（F3 ✅ PLAN-028 / F2 ✅ PLAN-029 / F1 ✅ PLAN-030 /
   **本切片 ✅**）：
2. **重跑多机实验**（`mm-run.ps1`）：完整闭环
   `停 C（app+PG）60s → 恢复 → 补票追平`；WSL B 还原 mkfifo 后验证
   非 distroless 启动（Fifo 修复对外的直接实证）；
3. 更新 `PLAN-027` §五（闭环数据）、roadmap #34/#35/#30（状态：已修 + 多机实证）。
