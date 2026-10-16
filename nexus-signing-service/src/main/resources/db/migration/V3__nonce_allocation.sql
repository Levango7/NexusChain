-- V3: 共享 nonce 分配表（多副本与并发请求下的 nonce 原子分配，#33）
--
-- 背景：转账的 nonce 此前由**每个 JVM 自己的** NoncePool 决定
--   （`TxController:384 getMaxNonce` / `SigningTccActionImpl:95 getMaxNonce`），而
--   `getMaxNonce` 是**读**（`NoncePool:128-133` 返回 `tmaps.lastKey()`），不摘除、不占位。
--   分配动作跨两次调用（`getMaxNonce` → 签名 → `nownonce++` → `add`），调用点没有任何同步 ⇒
--     · **同一副本内**两个并发签名会读到同一个 nonce（两笔交易同 nonce 上链，必有一笔被替换/丢弃，
--       而调用方已收到"广播成功"）；
--     · prod `nexus-signing-service.replicaCount=3`（values-prod.yaml:78-79）且无按地址亲和
--       （全仓 grep Sticky|affinity|consistentHash 零命中），三副本各持一份池 ⇒ 跨副本同 nonce；
--     · 放大项：`TxController:365-367` 要求 `fromPubkey` 恰等于**唯一**的平台 keystore 公钥
--       ⇒ 所有转账共用一个 address，即三副本争用同一个计数器。
--   LevelDB 持久化（`Leveldb.java:40` = `user.dir/leveldb`）是 pod 本地目录、且每次 add/remove
--   把**整池**重写成一个 JSON blob（`NoncePool:102-103/:116-117`）⇒ 既非共享也是最后写赢。
--
-- 语义：一行 = 一个 address 的 nonce 发放水位。`next_nonce` 表示"下一次要发出的值"，
--   认领 = 在同一事务里 `UPDATE ... next_nonce = next_nonce + 1` 后读回 `next_nonce - 1`。
--   InnoDB 下该 UPDATE 持行级排他锁到提交 ⇒ 并发认领彼此串行，不可能拿到同一个值。
--   `next_nonce = -1` 是"尚未播种"哨兵：首次认领时以链上 RPC 读到的 nonce 播种，
--   播种本身是条件 UPDATE（`WHERE next_nonce = -1`）⇒ 并发播种只有一个赢家，其余走已播种路径，
--   不会互相覆盖成同一个值。
--
-- 失败语义（与 #112 审批 store 的 fail-closed 同一口径）：DB 不可用时**分配失败**，不回退到
--   进程内计数——静默回退正好把本表要修的重复 nonce 窗口重新打开。宁可拒单一笔，不可两笔同号。
--
-- DDL 采用 H2(MODE=MySQL) 与 MySQL8 双兼容写法（与 V98/V103 同口径，便于本地/CI 用真实
--   主键与条件 UPDATE 语义做机制回归）。
CREATE TABLE nonce_allocation (
    address        VARCHAR(64)  NOT NULL,
    next_nonce     BIGINT       NOT NULL,
    updated_at     TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (address)
);
