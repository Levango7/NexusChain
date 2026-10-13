# NexusChain Seata TC（db 存储模式）薄封装镜像
# ============================================================================
# L3 预演缺陷 #13/#14 修复件。
# 官方 apache/seata-server:2.5.0 镜像不含 MySQL JDBC 驱动（许可证原因），
# STORE_MODE=db 启动即抛 `ShouldNeverHappenException: cannot find jdbc folder`
# （AbstractDataSourceProvider.validate）。docker-compose.prod.yml 的 seata
# 服务使用同一官方镜像且无驱动挂载——两条交付路径同根缺陷。
#
# 构建前置：把 mysql-connector-j 驱动 jar 放入本目录 lib/ 下（文件名任意，
# 目录整体 COPY，不提交进 git——.gitignore 已排除 lib/*.jar）。
#   下载（版本与应用侧一致，见各服务 build.gradle 的 com.mysql:mysql-connector-j）：
#     https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/<version>/
#   离线环境：从本仓应用镜像 app.jar 内 BOOT-INF/lib/mysql-connector-j-*.jar
#   解出同名 jar 复用（与应用驱动版本严格一致）。
#
# 构建（在仓库根执行）：
#   docker build -t <registry>/seata-server:2.5.0-mysqldrv deploy/seata
# 使用：deploy/k8s/31-seata.yml 的 image 字段；compose 侧同名 build 配置。
# ============================================================================
FROM apache/seata-server:2.5.0

# 驱动目录必须是根 /lib/jdbc：官方镜像启动参数为
#   -Dapp.home=/ -Dbasedir=/ -Dloader.path=/lib
# Seata 按 basedir 下的 lib/jdbc 扫描驱动 jar（实测挂载 /lib/jdbc 即从
# "cannot find jdbc folder" 转为正常加载 com.mysql.cj.jdbc 驱动）。
COPY lib/ /lib/jdbc/
