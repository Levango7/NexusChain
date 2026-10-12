#!/bin/sh
# =============================================================================
# NexusChain 生产数据库初始化（MySQL 8.0）
# =============================================================================
#
# 用途（两种模式，同一份脚本）：
#   1. **compose 自动执行**：mysql:8.0 镜像的 docker-entrypoint-initdb.d 在
#      **数据目录为空**（首次启动）时执行本脚本，环境变量已注入。
#   2. **客户自建实例手工执行**：把 MYSQL_* 三个变量设好后
#      `sh init-mysql-dbs.sh`（或把 % 换成客户网段后交给客户 DBA 执行）。
#
# 设计口径：
#   · 五个库：四个业务库（nexus_gateway / nexus_wallet / nexus_signing / nexus_bridge）
#     + seata（分布式事务 TC 的库；**表**由 Seata 首次启动自建，不在此脚本内）。
#     core 不需要库：它是链节点（LevelDB 存链数据），零 JPA 实体、零 Flyway。
#   · 每库一个**专用账号**：最小权限（只授权本库），密码同源 MYSQL_PASSWORD。
#     后续可按服务轮换（改 NEX_DB_PASSWORD 与本脚本对应行即可）。
#   · 字符集 utf8mb4：中文备注与审计明细必须（utf8mb3 存不下 emoji/部分生僻字）。
#   · 全部 IF NOT EXISTS：可重复执行，不破坏已有数据。
#
# 环境变量：MYSQL_ROOT_PASSWORD（容器内 root）、MYSQL_PASSWORD（业务账号口令）
# =============================================================================
set -eu

: "${MYSQL_ROOT_PASSWORD:?需要 MYSQL_ROOT_PASSWORD}"
: "${MYSQL_PASSWORD:?需要 MYSQL_PASSWORD}"

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS nexus_gateway CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nexus_wallet  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nexus_signing CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nexus_bridge  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seata         CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'nexus_gateway'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
CREATE USER IF NOT EXISTS 'nexus_wallet'@'%'  IDENTIFIED BY '${MYSQL_PASSWORD}';
CREATE USER IF NOT EXISTS 'nexus_signing'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
CREATE USER IF NOT EXISTS 'nexus_bridge'@'%'  IDENTIFIED BY '${MYSQL_PASSWORD}';
CREATE USER IF NOT EXISTS 'nexus'@'%'         IDENTIFIED BY '${MYSQL_PASSWORD}';

GRANT ALL PRIVILEGES ON nexus_gateway.* TO 'nexus_gateway'@'%';
GRANT ALL PRIVILEGES ON nexus_wallet.*  TO 'nexus_wallet'@'%';
GRANT ALL PRIVILEGES ON nexus_signing.* TO 'nexus_signing'@'%';
GRANT ALL PRIVILEGES ON nexus_bridge.*  TO 'nexus_bridge'@'%';
-- nexus 通用账号：Seata TC 与运维（备份/巡检）使用；仅五库权限，不给全局权限
GRANT ALL PRIVILEGES ON nexus_gateway.* TO 'nexus'@'%';
GRANT ALL PRIVILEGES ON nexus_wallet.*  TO 'nexus'@'%';
GRANT ALL PRIVILEGES ON nexus_signing.* TO 'nexus'@'%';
GRANT ALL PRIVILEGES ON nexus_bridge.*  TO 'nexus'@'%';
GRANT ALL PRIVILEGES ON seata.*         TO 'nexus'@'%';
-- 监控导出器需要全局 PROCESS/REPLICATION CLIENT（mysqld-exporter 的部分指标依赖）
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'nexus'@'%';

FLUSH PRIVILEGES;
SQL

echo "[init-mysql-dbs] 五个库与专用账号已就绪（gateway/wallet/signing/bridge/seata）"
