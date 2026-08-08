# ADR-001: 研究层冻结

## 状态
Accepted (2026-08-08)

## 背景
NexusChain 广度严重超出团队承载力。研究层（MPC 密码学、ZK Rollup、L2-L1 真实化、治理执行接线）消耗了大量工程预算，但不产生当前产品价值。产品价值集中在网关层（gateway + settlement + compliance）。

## 决策
冻结以下模块的进一步开发，仅维护诚实声明：
- mpc-engine（Rust gRPC MPC 引擎）：骨架实现，DKG/Sign/Aggregate 未接入真实密码学库
- nexus-core L2 ZK 证明系统：Groth16 简化版（Schnorr 协议），非完整配对
- nexus-core L2 L1 合约交互：Web3j 实现完成，但未在真实 L1 节点测试
- nexus-oracle 治理执行：PARAMETER_CHANGE 已接线，SOFTWARE_UPGRADE/TREASURY_SPEND 为占位

## 解冻条件
- 产品层（gateway/settlement/compliance）达到生产可用
- 有专门的密码学团队负责 MPC/ZK 实现
- 有真实的 L1 测试环境

## 影响
工程预算集中到：
- 网关生产化（真实 PSP connector 接入）
- 清结算对账闭环
- P0/P1 安全与架构修复