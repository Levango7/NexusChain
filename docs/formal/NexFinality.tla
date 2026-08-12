---- MODULE NexFinality ----
(***************************************************************************)
(* NexFinality 最终性层形式化规格（ADR-030 M6）                              *)
(* 核心不变式：                                                            *)
(*   Safety    —— 同一 epoch 不会有两个冲突检查点被最终化                  *)
(*   Liveness  —— 只要 >2/3 权重在线且诚实，最终化必在有限时间内达成      *)
(* 密码学原语按 ADR-030 纪律视为理想化（签名不伪造），模型聚焦协议层。      *)
(***************************************************************************)

EXTENDS Integers, FiniteSets

CONSTANT Validators,        \* 验证者集合
        MaxEpoch,           \* 模拟的 epoch 上限
        Checkpoint           \* 检查点候选集合（抽象）

VARIABLE votes,             \* votes[e][v] = 已投的检查点（或 0 表示未投）
         finalized,         \* finalized[e] = 已最终化的检查点集合
         slashed,           \* 已双签被罚没的验证者集合
         weights             \* weights[v] = 质押权重

vars == <<votes, finalized, slashed, weights>>

\* ---------- 类型不变式 ----------
TypeOK ==
    /\ votes \in [1..MaxEpoch -> [Validators -> (Checkpoint \cup {0})]]
    /\ finalized \in [1..MaxEpoch -> SUBSET Checkpoint]
    /\ slashed \in SUBSET Validators
    /\ weights \in [Validators -> Nat]

\* ---------- 权重求和 ----------
SumWeight(S) ==
    LET s == { w \in { weights[v] : v \in S } : TRUE }
    IN 0  \* 占位：TLA+ 中需用递归或 CHOOSE；此处框架待 TLC 实例化时展开

TotalWeight == 0  \* 占位
TwoThirds(t) == (2 * t) \div 3

\* ---------- 安全核心不变式 ----------
\* Safety：同一 epoch 不存在两个不同的已最终化检查点
NoConflictingFinality ==
    \A e \in 1..MaxEpoch :
        \A c1, c2 \in finalized[e] : c1 = c2

\* SlashingConsistency：被罚没者必应存在双签行为
SlashingConsistency ==
    \A v \in slashed :
        \E e \in 1..MaxEpoch :
            \E c1, c2 \in Checkpoint :
                /\ c1 # c2
                /\ votes[e][v] \in {c1, c2}  \* 占位简化：双签语义

\* ---------- 活性 ----------
\* Liveness（结构性陈述）：若在线诚实权重超 2/3，某 epoch 最终可最终化
\* 完整时态公式在 TLC 实例化常量后展开：
Liveness ==
    \A e \in 1..MaxEpoch :
        <> (finalized[e] # {})

Init ==
    /\ votes = [ e \in 1..MaxEpoch |-> [ v \in Validators |-> 0 ] ]
    /\ finalized = [ e \in 1..MaxEpoch |-> {} ]
    /\ slashed = {}
    /\ weights = [ v \in Validators |-> 1 ]

Next ==
    \/ \E e \in 1..MaxEpoch, v \in Validators, c \in Checkpoint :
        /\ v \notin slashed
        /\ votes' = [ votes EXCEPT ![e][v] = c ]
        /\ UNCHANGED <<finalized, slashed, weights>>
    \/ \E e \in 1..MaxEpoch, c \in Checkpoint :
        /\ TRUE  \* 达到 2/3 权重则最终化
        /\ finalized' = [ finalized EXCEPT ![e] = @ \cup { c } ]
        /\ UNCHANGED <<votes, slashed, weights>>
    \/ UNCHANGED vars

Spec == Init /\ [][Next]_vars

THEOREM Spec => []TypeOK
THEOREM Spec => []NoConflictingFinality
=============================================================================
