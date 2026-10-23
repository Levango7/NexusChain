-- V5: bridge_messages 跨链消息落库表（PLAN-026 桥持久化）。
--
-- 背景：MessageStore 此前只有 InMemoryMessageStore（接口注释明确
-- "生产环境应使用 Redis / 数据库实现"）。消息中继的去重（messageId）、
-- 顺序保证（per 源链 nonce）与执行追溯（executionTxHash）都依赖该状态
-- 跨重启存在；本表为 JdbcMessageStore 提供存储。
--
-- 与实体的关系：与 nexus-bridge 其它迁移一致，本模块运行时 schema 由
-- Hibernate ddl-auto=update 维护，本脚本是 CI Flyway 预检用的"未接线"
-- 历史文件（见 V0 头注），字段必须与 BridgeMessageRecord 实体一致。
--
-- 语法说明：
--   * CREATE TABLE IF NOT EXISTS 兼容 MySQL 8.0 / H2（同 V0）。
--   * CREATE INDEX 不带 IF NOT EXISTS（MySQL 8.0 不支持，Flyway schema
--     history 保证单次执行，同 V1/V3 的做法）。
--   * payload 用 (type, data, encoded_data) 三列而不是 JSON —— MessagePayload
--     的 type/data 是 final 字段，三列存储避免传输类与 Jackson 耦合。
--   * signatures 为 hex 签名的换行符连接（hex 不含换行，无损可逆）。

CREATE TABLE IF NOT EXISTS bridge_messages (
    message_id           VARCHAR(128)  NOT NULL,
    source_chain         VARCHAR(64)   NOT NULL,
    target_chain         VARCHAR(64)   NOT NULL,
    source_contract      VARCHAR(128),
    target_contract      VARCHAR(128),
    payload_type         VARCHAR(32),
    payload_data         TEXT,
    payload_encoded_data TEXT,
    nonce                BIGINT        NOT NULL,
    message_timestamp    BIGINT        NOT NULL,
    signatures           TEXT,
    status               VARCHAR(32)   NOT NULL,
    execution_tx_hash    VARCHAR(128),
    created_at           TIMESTAMP     NOT NULL,
    updated_at           TIMESTAMP     NOT NULL,
    version              BIGINT,
    CONSTRAINT pk_bridge_messages PRIMARY KEY (message_id)
);

-- 顺序保证查询路径：按源链取 max(nonce)
CREATE INDEX idx_bridge_messages_source_chain_nonce
    ON bridge_messages (source_chain, nonce);
