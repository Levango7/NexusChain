# NexusChain 合约引擎分阶段实现计划

> 设计日期: 2026-08-05
> 当前状态: ContractRegistry（注册）✅ | WASM VM 集成（执行）❌
> 预估总工作量: **~10 人日**

---

## 现状

```
注册(✅) → 部署(❌) → 执行(❌) → 调用(❌) → Explorer展示(部分✅)
```

| 已有 | 缺失 |
|------|------|
| `ContractRegistry` 内存+LevelDB 双层 | WASM 合约上传/部署到链上 |
| `nexus_getContractList` RPC | WASM 字节码加载到 VM 执行 |
| `nexus_getContract` RPC | 合约方法调用的 RPC 端点 |
| `nexus_registerContract` RPC（手动） | Gas metering / 资源计量 |
| `ContractStatus` 状态机 | 合约执行 sandbox 隔离 |
| Explorer 合约列表/详情路由（空数据） | Explorer 合约页真实数据 |

---

## Phase A: WASM VM 基础集成（3.5 人日）

### 前置条件
确认 Wasmer JNI 可用（`nexus-consortium/wasmer/` 目录存在，`TestCommunicationTool.java` 可编译）。

### A1: Wasmer JNI 适配层 — 1.5 人日

**新增**: `nexus-core/.../core/contract/WasmEngine.java`

```java
public interface WasmEngine {
    /** 从 WASM 字节码实例化合约 */
    WasmInstance instantiate(byte[] wasmCode, GasMeter gasMeter);

    /** 从已注册合约地址加载实例（缓存） */
    WasmInstance load(String contractAddress);
}

public interface WasmInstance {
    /** 调用合约方法，返回结果字节 */
    byte[] call(String methodName, byte[] args);
    /** 获取该实例已消耗的 gas */
    long getGasUsed();
}
```

**sandbox 配置**:
| WASI 能力 | 状态 |
|-----------|------|
| 文件系统 | ❌ 禁用 |
| 网络 | ❌ 禁用 |
| 时钟 | ✅ 允许（只读） |
| 随机数 | ✅ 允许（只读） |
| 环境变量 | ❌ 禁用 |
| 最大内存 | 64 MB |
| Fuel metering | ✅ 开启 |

### A2: Gas 计量模型 — 1.0 人日

**新增**: `nexus-core/.../core/contract/GasMeter.java`

| 操作 | Gas 消耗 |
|------|---------|
| WASM 指令（基础） | 1 gas / 条 |
| 内存访问 | 1 gas / 4KB |
| 存储读 | 100 gas / 次 |
| 存储写 | 500 gas / 次 |
| 合约部署（基础费） | 100,000 gas |
| 合约部署（per byte wasmCode） | 10 gas / byte |

Gas 上限: 1,000,000 / 交易。超出上限 → 交易回滚（状态不变），gas 不退。

### A3: 合约部署交易类型 — 1.0 人日

**修改**:
- `Transaction.java`: 新增 `Transaction.Type.DEPLOY_CONTRACT = 7`
- `TransactionValidator.java`: 新增 `DeployContractRule` 验证规则
- `ProposersState.updateTransaction()`: 增加 DEPLOY_CONTRACT 分支 → `WasmEngine.instantiate()` → `ContractRegistry.register()`
- **关键**: 部署后自动注册，不再依赖手动 `nexus_registerContract` RPC

```
DEPLOY_CONTRACT tx 入块流程:
1. 验证 wasmCode 非空 & 格式合法
2. WasmEngine.instantiate(wasmCode) → 检查编译错误
3. ContractRegistry.register(address, name, codeHash, wasmCode)
4. GasMeter 扣费
5. 状态写入区块
```

---

## Phase B: 合约执行引擎（3.5 人日）

### B1: 合约调用交易 + 状态 sandbox — 2.0 人日

**新增**: `Transaction.Type.CALL_CONTRACT = 8`

交易 payload 结构:
```
contractAddress (20 bytes) || methodName (UTF-8) || args (RLP encoded)
```

**新增**: `nexus-core/.../core/contract/ContractContext.java`
```java
public class ContractContext {
    /** 只读访问当前区块的 account 状态 */
    Account readAccount(byte[] address);
    /** 写入操作累积到 StateDiff */
    void writeStorage(String key, byte[] value);
    /** 当前区块高度 */
    long getBlockHeight();
}
```

执行流程:
```
1. load contract wasmCode from ContractRegistry
2. WasmEngine.instantiate(code, gasMeter)
3. instance.call(methodName, args) with ContractContext sandbox
   → 所有写操作进入 StateDiff
4. 检查 gas 未超限
5. StateDiff 原子提交到区块状态树
```

### B2: JSON-RPC 调用端点 — 0.5 人日

**新增**: `JsonRpcController` 中的两个 RPC 方法:

| 方法 | 用途 | 产生交易 |
|------|------|---------|
| `nexus_callContract` | 只读调用（不上链），返回结果 | ❌ |
| `nexus_sendContractTransaction` | 发起合约调用交易并广播 | ✅ |

### B3: 合约 ABI 事件日志 — 1.0 人日

合约调用时 emit 的事件写入交易收据:
```java
public class ContractEvent {
    String contractAddress;
    String eventName;
    byte[] data;        // ABI 编码的事件参数
    String[] topics;    // 索引字段（用于过滤）
}
```

支持 `nexus_getContractEvents` RPC: 按合约地址 + 事件名 + 区块范围过滤。

---

## Phase C: Explorer 集成 + 安全审计（3.0 人日）

### C1: Explorer 合约页升级 — 1.5 人日

| 页面 | 当前 | Phase C 后 |
|------|------|-----------|
| `/contracts` | 空列表（"诚实空"） | 真实注册合约列表，含名称/创建者/状态/时间 |
| `/contracts/:addr` | 404 | 详情页：ABI 方法签名 + 字节码大小 + 调用统计 + 事件日志 |
| 搜索栏 | 无合约搜索 | 支持按合约地址搜索 |

### C2: WASM sandbox 安全审计 — 1.0 人日

| 审计项 | 方法 |
|--------|------|
| WASI 能力矩阵 | 确认禁用的能力确实不可访问 |
| 内存限制 | 超过 64MB 的分配请求应失败 |
| Fuel metering | 耗尽 gas 时交易回滚，状态不变 |
| Reentrancy | 同一交易中重复调用同一合约的方法——当前阶段拒绝 |
| 恶意 WASM | 无效操作码、无限循环（fuel 耗尽）、OOM 攻击 |

### C3: 端到端测试 — 0.5 人日

```
测试链上:
1. 部署合约 (DEPLOY_CONTRACT tx) → 确认注册成功
2. nexus_getContractList → 返回该合约
3. 调用合约 (CALL_CONTRACT tx) → 确认执行结果正确
4. nexus_getContractEvents → 返回事件日志
5. Gas 耗尽 → 交易回滚
6. Explorer /contracts 页 → 显示合约
```

---

## 依赖图

```
A1 Wasmer 适配 ──→ B1 CALL_CONTRACT tx
                   ↘ B2 RPC 端点
A2 Gas 模型 ────→ A3 DEPLOY_CONTRACT tx
                → B1 CALL_CONTRACT tx
A3 DEPLOY_CONTRACT tx → B1 CALL_CONTRACT tx → B3 事件日志 → C1 Explorer
                                                              C2 安全审计
                                                              C3 端到端测试
```

---

## 工作量汇总

| Phase | 任务 | 人日 |
|-------|------|------|
| A1 | Wasmer JNI 适配层 | 1.5 |
| A2 | Gas 计量模型 | 1.0 |
| A3 | 合约部署交易类型 | 1.0 |
| B1 | 合约调用 + 状态 sandbox | 2.0 |
| B2 | JSON-RPC 调用端点 | 0.5 |
| B3 | ABI 事件日志 | 1.0 |
| C1 | Explorer 合约页升级 | 1.5 |
| C2 | WASM sandbox 安全审计 | 1.0 |
| C3 | 端到端测试 + 文档 | 0.5 |
| | **合计** | **10.0 人日** |