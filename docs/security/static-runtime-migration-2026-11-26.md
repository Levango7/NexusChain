# MPC / ZK 最小静态运行时迁移（2026-11-26）

## 背景

#783 post-merge 的 ZK/MPC 扫描命中 `CVE-2026-124851 / libzstd1`。两个服务当前 cc 镜像的 dpkg 状态仍为 `1.5.7+dfsg-1`，但修复要求 `1.5.7+dfsg-1+deb13u1`。最新 cc digest 也没有包含修复，因此不能只 bump digest 或登记无修复版例外。

实际 ELF 依赖证明两个 Rust 二进制仅需要 `libgcc_s.so.1`、`libm.so.6`、`libc.so.6` 与动态加载器，OpenSSL 和 libzstd 都不是运行必需项。选择移除未使用包，而不是豁免。

## 实现

- MPC：`gcr.io/distroless/static-debian13:latest@sha256:4c2cd1dee442c9a99924a378c7c126fac8fef14771579ef567c3f2bdd7a5122a`
- ZK：`gcr.io/distroless/static-debian13:nonroot@sha256:8ce6dd87e9823c5b48dcfb2c8604714ccb351ce59607327bcc4957448da279b5`
- 动态加载器、`libc.so.6`、`libm.so.6`、`libgcc_s.so.1` 从各自 Rust builder 原样复制，保证与编译环境 glibc 一致。
- `LD_LIBRARY_PATH=/runtime-libs`；MPC UID 1000、ZK UID 65532、会话目录、busybox 健康检查保持不变。
- 旧 libssl 例外已从 `config/trivy-baseline.json` 删除，未来重新引入该包时必须重新评估。

## 实测

1. 两个真实 Dockerfile 均构建成功：
   - `mpc-static-test`：26.3 MB
   - `zk-static-test`：22.8 MB
2. 实际 ELF loader `--list` 均只解析到 `/runtime-libs` 下的三项动态库与 `/lib64/ld-linux-x86-64.so.2`。
3. `docker image inspect`：MPC=`1000:1000`，ZK=`65532`。
4. 使用 #783 Security run `45578541697` 的共享 Trivy DB（`2026-11-25T07:11:44Z`）扫描：
   - `mpc-static-trivy.json`：HIGH/CRITICAL 0
   - `zk-static-trivy.json`：HIGH/CRITICAL 0
5. 静态探针 `static-runtime-trivy.json` 同样为 0。

## 边界

这些证据只覆盖本机构建产物、运行依赖和当前漏洞库，不等于真实集群、生产 HA 或长期安全保证。后续若新增动态库、OpenSSL/QUIC、压缩数据入口或改变 builder，必须重新检查运行依赖和漏洞可达性。
