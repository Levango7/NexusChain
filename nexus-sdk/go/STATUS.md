# Go SDK — EXPERIMENTAL SKELETON（规划中，不可集成）

> **状态声明（2026-08-31 交付前审计）**
>
> 本目录为早期骨架占位（module 名残留旧品牌 `conpay`）。以下核心能力**未实现**：
> - `transaction.Sign / BuildContractCall`（返回 "not yet implemented"）
> - ⚠️ **`Broadcast` 调用 core 节点不存在的 `nexus_sendRawTransaction` 方法——调用必然失败**（不要集成）
>
> 生产集成请使用 **Java SDK**（`nexus-sdk/java/`，全能力可用）。
> 补全计划见 v2.2.0+ 路线；品牌残留（conpay/CPAY → nexus/NEX）将随补全一并修正。
