-- V13: merchant_keypairs 表（B-14 修复：VaultKeyManager 密钥持久化）。
--
-- 背景：VaultKeyManager 原先将 AES-256-GCM 加密后的商户密钥对仅保存在内存
-- ConcurrentHashMap 中，服务重启后所有商户密钥丢失，导致商户无法认证。
-- 本表将加密后的密钥对落库，启动时全量加载回内存，写入时同步 upsert。
--
-- 字段说明：
--   * merchant_id       商户 ID，唯一约束保证一商户一密钥对记录
--   * encrypted_keypair AES-256-GCM 加密后的密文（Base64 编码），
--                       格式 [12B IV || ciphertext || 16B GCM-tag]，主密钥不落库
--   * created_at / updated_at 审计时间戳
--
-- 语法兼容 MySQL 8 / H2（dev profile 使用本目录脚本）。
CREATE TABLE IF NOT EXISTS merchant_keypairs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    encrypted_keypair VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_merchant_keypairs_merchant_id UNIQUE (merchant_id)
);