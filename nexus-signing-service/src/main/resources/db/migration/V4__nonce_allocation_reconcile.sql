-- V4: 给 nonce 分配表加"上次校正时间"列（#48 第二半：水位向前校正的限流位）
--
-- 为什么需要：#114 把 nonce 发放改成共享表原子认领后，水位只随"本分配器发出"前进。
--   若发生**分配器之外**的消耗（人工补发、外部系统用同一平台地址发交易、或 DB 被回滚到旧值），
--   链上的 account.nonce 会**超过**我们的水位，此后发放的每个 nonce 都会被节点
--   （`PackageMiner:74` 要求 tx.nonce > account.nonce）当重复号丢弃 ⇒ 转账持续失败。
--   节点侧 account.nonce 存的是「上一笔已应用」值（`MerkleRule` 应用时 setNonce(tran.nonce)），
--   所以"下一个可用"= chain+1，校正就是 `next_nonce = max(next_nonce, chain+1)`——只前进、绝不后退
--   （水位高于链是正常的：有在飞交易尚未上链）。
--
-- 为什么是一列而不是配置缓存：校正的 RPC 必须**全局限流**，不能每个认领都打链
--   （那会把 #33 省掉的 RPC 开销原样加回来）。用行上的 last_reconciled_at 做**租约**：
--   条件 UPDATE 只有把该行从"到期"翻到"未到期"的那一个副本才去打 RPC，其余副本直接走认领路径。
--
-- TIMESTAMP(6) + NULL：与 V1/V3 同口径（H2 MODE=MySQL 与 MySQL8 双兼容，便于 CI 的
--   flyway-migration-check 用真 MySQL 8.0 顺序应用）。
ALTER TABLE nonce_allocation
    ADD COLUMN last_reconciled_at TIMESTAMP(6) NULL;
