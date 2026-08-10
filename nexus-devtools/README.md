# NexusChain DevTools

NexusChain 开发者工具链 — 用于 NexusChain（NEX）链上 WASM 智能合约的开发全流程。

## 功能

- **合约编译** — 将 Rust / C / AssemblyScript 源码编译为 WASM 字节码
- **合约部署** — 将编译后的 WASM 合约部署到 NexusChain 链上
- **合约调试** — 本地运行合约、追踪执行、查看存储状态
- **合约测试** — 编写并运行单元测试、集成测试
- **项目初始化** — 从模板快速创建合约项目

## 支持的合约语言

| 语言 | 编译工具链 | 模板 |
|------|-----------|------|
| Rust | rustc + wasm-pack | `templates/rust/` |
| AssemblyScript | asc (AssemblyScript) | `templates/assemblyscript/` |
| C | clang + wasi-sdk | (规划中) |

## 项目结构

```
nexus-devtools/
├── package.json
├── src/
│   ├── index.ts                    # CLI 入口
│   ├── compiler/
│   │   ├── WasmCompiler.ts         # WASM 编译器接口
│   │   ├── RustCompiler.ts         # Rust 编译器
│   │   └── AssemblyScriptCompiler.ts # AssemblyScript 编译器
│   ├── deployer/
│   │   └── ContractDeployer.ts    # 合约部署器
│   └── test/
│       └── ContractTester.ts      # 合约测试器
├── templates/
│   ├── rust/                      # Rust 合约模板
│   │   ├── Cargo.toml
│   │   └── src/lib.rs
│   └── assemblyscript/            # AS 合约模板
│       ├── package.json
│       └── assembly/index.ts
```

## 快速开始

```bash
# 全局安装 CLI
npm install -g @nexus/devtools

# 初始化新合约项目
nexus init my-contract --lang rust

# 编译合约
nexus compile ./my-contract

# 部署合约
nexus deploy ./my-contract --node http://localhost:8545

# 运行测试
nexus test ./my-contract
```

## 许可证

MIT
