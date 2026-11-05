# Java 腿基础镜像 OS 层根治调研（2026-11-05）

认领：板 `20261105-0640-zcode-claim-remaining-items` 项三。
动机：nexus-core / nexus-gateway / nexus-bridge / nexus-compliance /
nexus-analytics / nexus-oracle / nexus-api-gateway / nexus-settlement /
nexus-signing-service / nexus-wallet-service 共 10 个 Java 腿的运行时统一为
`gcr.io/distroless/java17-debian13:nonroot`。本调研回答：**OS 层 CVE 红项能否
从基线上根治（而不是继续豁免）**，以及换基线的代价与风险。

## 1. 现状根因（实测取证）

本机 Trivy v0.76（容器形态，DB 2026-11-05，`--severity HIGH,CRITICAL`，
方法见附录 A）实扫现基线镜像：

```
gcr.io/distroless/java17-debian13:nonroot
  OS: debian 13.7    OS 包: 25    镜像: 233MB
  Total: 7 (HIGH: 7, CRITICAL: 0) —— 全部 affected（无修复版本可得）
    libexpat1 ×3: CVE-2026-113351 / CVE-2026-117610 / CVE-2026-118370
    libuuid1  ×4: CVE-2026-76642 / CVE-2026-78408 / CVE-2026-78409 /
                   CVE-2026-78410
```

这 7 条**全部在 `.trivyignore` 内**（逐条核对过），因此 CI 的 Trivy 腿当前
靠豁免保持绿色——实证：docs-only PR（#499）上 `Trivy Base Image Scan
(PR light, gcr.io/distroless/java17-debian13:nonroot)` 直接 pass，而同一
workflow 的 `node:24-alpine` 腿按 #495 的基线红项 fail。

结论：**当前 Java 腿的 OS 层是「纪律性豁免下的绿」，不是「基线本身干净」。**
豁免是上游 debian13 对 libexpat/libuuid 的 will-not-fix 状态，不会随时间
自愈；根治路径只有换基线镜像线。这正是本调研的输入假设，已被实测证实。

## 2. 候选实扫对比表

同一台机器、同一 DB、同一严重度口径（HIGH/CRITICAL，OS 层 `--pkg-types os`
+ 全量扫描两种都跑过，JRE 模块不被 Trivy 的 jar 分析器识别，两口径数字一致）：

| # | 镜像（runtime 基线） | OS / 版本 | OS 包数 | 镜像大小 | HIGH/CRIT（实扫） | JDK | 备注 |
|---|---|---|---|---|---|---|---|
| 0 | `gcr.io/distroless/java17-debian13:nonroot`（**现基线**） | debian 13.7 | 25 | 233MB | **7**（全 affected） | Temurin 17.0.21 | 7 条全在 .trivyignore；nonroot UID=65532 |
| 1 | `eclipse-temurin:17-jre-alpine` | alpine 3.24.3 | 73 | 257MB | **0** | Temurin 17.0.21 | Adoptium 官方；musl；默认 root，nonroot 需自建 |
| 2 | 自制 jlink（temurin 17.0.21 → `alpine:3.24`，原型见 §3.1） | alpine 3.24.3 | **16** | **120MB** | **0** | 17.0.21（模块自选） | 最小、模块表可控；无包管理器 |
| 3 | `cgr.dev/chainguard/jre:latest` | wolfi | 43 | 355MB | **0** | openjdk（latest 新线） | 无 shell；公开 **JDK17 免费线停在 2023** |
| 4 | `cgr.dev/chainguard/jre:openjdk-17.0.9.5-r1` | wolfi（旧） | 22 | 377MB | **52** | 17.0.9.5 | 公开 JDK17 最新 tag；glibc 2.38 系旧 CVE 堆积 |
| 5 | 自制 chisel（ubuntu 24.04 slices → scratch，原型见 §3.2） | **Trivy 不可识别** | 182 文件 | 226MB | **不可扫** | 17.0.20.1 | 无 dpkg status → OS 层门禁语义失效 |
| 6 | （旁证）`ubuntu:25.04` 裸底 | ubuntu 25.04 | 87 | 118MB | 1（gpgv，**有**修复版） | — | 25.04 已 EOL：chisel 直接拒切（unmaintained） |

Chainguard 注册表补充事实：`cgr.dev/chainguard/jre-17`（JDK17 专用线）
返回 **Forbidden**（付费订阅）；公开 `chainguard/jre` 的 JDK17 tag 最高只
到 `openjdk-17.0.9.5-r1`（2023 年构建）。即 Chainguard 的「现 JDK17 免费可
用、0 CVE」组合不存在——要么旧 tag（52 CVE），要么 latest（新 JDK 线，
等于捆绑一次大版本升级）。

## 3. 原型 Dockerfile（验证可跑性，非生产路径）

### 3.1 jlink 原型（实测 `java -version` 通过，120MB）

```dockerfile
# jlink 原型：从 temurin JDK 17 抽最小 JRE，落到裸 alpine
FROM eclipse-temurin:17-jdk-alpine AS jre-build

RUN jlink \
  --no-header-files --no-man-pages --compress=2 \
  --add-modules java.base,java.compiler,java.desktop,java.logging,java.management,java.naming,java.net.http,java.scripting,java.security.jgss,java.sql,java.transaction.xa,java.xml,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs \
  --output /jre

FROM alpine:3.24
COPY --from=jre-build /jre /jre
ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"
CMD ["java", "-version"]
```

实测：`openjdk version "17.0.21"`，OS 包 16（vs temurin 整镜像 73 vs
distroless 25），120MB。模块表是关键变量：上述清单覆盖 Spring/Netty/JDBC/
JMX/NAS 常见需求，但**项目的实际模块集需要在腿上逐一验证**（见 §4）。

### 3.2 chisel 原型（实测 `java -version` 通过，226MB）

```dockerfile
# chisel 原型：ubuntu 24.04 切片 + scratch
FROM ubuntu:25.04 AS cut

# chisel 二进制：canonical 官方 release v1.5.1（构建上下文提供）
COPY chisel /usr/local/bin/chisel
RUN chmod +x /usr/local/bin/chisel

# 切片定义：chisel-releases 的 ubuntu-24.04 分支（本机 codeload TLS 被拦截，
# 宿主机预取 tarball 进上下文，走本地 --release 目录）
COPY release /release

# openjdk-17-jre-headless_standard + ca-certificates_data
RUN mkdir -p /cut-root && chisel cut --release /release --root /cut-root \
      openjdk-17-jre-headless_standard ca-certificates_data

FROM scratch
COPY --from=cut /cut-root /
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"
CMD ["java", "-version"]
```

实测：`openjdk version "17.0.20.1"`，182 个文件（67 个在 jvm 目录下）。
**关键工程坑（均在本机复现并绕过）**：

1. `codeload.github.com` 在构建容器内被 TLS 拦截（x509 unknown authority）
   → 改为宿主机预取 release tarball、COPY 进上下文、本地 `--release`。
2. ubuntu **25.04 已 EOL**，chisel 报 "no archive has maintained maintenance
   status" 直接拒切 → 切到 24.04 LTS（这正是表格第 6 行的旁证）。
3. `chisel cut` 要求 `--root` 预先存在（否则 base-files 抽取报 "target
   directory does not exist"），需显式 `mkdir -p`。
4. archive.ubuntu.com 在构建容器内可达（proxy 放行），但偶发 truncation
   （`unexpected EOF`），构建需重试容错。

## 4. 切换风险评估

**通用迁移面（无论换到哪个候选）**

- 现有 10 个 Dockerfile 的 runtime 段都依赖 distroless 生态的两个模式：
  busybox 静态注入（healthcheck wget）与 dir-builder 中间阶段（nonroot 目录
  chown）。换基线后这两段语义不变，但 nonroot 用户需重新落地：temurin
  alpine/jlink/chainguard 默认均非 UID=65532 nonroot，要显式建用户与目录
  （alpine 上 `addgroup -S -g 65532` + `adduser`）。
- CI 的 `Trivy Base Image Scan (PR light, ...)` 腿配置要随镜像线改（当前
  硬编码 gcr.io/distroless/java17-debian13:nonroot）；master 的 docker-scan
  13 模块矩阵扫描目标同样会变（扫的是构建产物，基线换了自然跟着变）。
- `.trivyignore` 里的 libexpat/libuuid 7 条在切换成功后应清理（它们只服务
  于 debian13 基线）；清理动作属后续收口，不在本调研范围。

**逐候选风险**

| 候选 | 主要风险 | 风险等级 |
|---|---|---|
| temurin:17-jre-alpine | musl vs glibc：JNI/原生库兼容、DNS 解析行为、加密性能差异 → 需 10 腿全量回归；无 nonroot 变体 | 低（生态最成熟，JDK 与现基线同版本同发行版 17.0.21） |
| 自制 jlink | 模块表维护成本：反射访问（Spring/Jackson/Netty）需模块开放，缺模块=运行期 ClassNotFound；JDK 升级时模块表复核；jcmd/jstack 等诊断工具默认不带 | 中（收益最大：120MB、16 包、0 CVE） |
| chainguard jre:latest | 公开 JDK17 线停在 2023（52 CVE）；latest=新 JDK 线捆绑大版本升级；jre-17 仓库付费；CI 侧 cgr.dev 可达性未验证 | 中高（供应商/版本双重不确定性） |
| chisel | **Trivy 不可枚举 OS 层**（无 dpkg status，实测 "Supported files not found"）→ 违反项目「可信扫描门禁」语义；切片维护 靠 canonical 上游 | 高（不推荐主路径，价值在「最小集参照」） |

## 5. 建议（决策材料，执行需立项）

1. **优先级排序**：`temurin:17-jre-alpine`（0 CVE、同 JDK 17.0.21、迁移面
   最小）> 自制 `jlink`（收益最大、模块表维护成本中）> `chainguard`（供应商
   + 版本风险）> `chisel`（不推荐）。
2. 若采纳，**单腿试点**（建议 nexus-core：依赖面最具代表性）+ E2E 与冒烟
   全量回归（重点 musl 兼容/加密/DNS/healthcheck/nonroot）+ PR-light 扫描
   腿配置跟进。这是执行半边，需单独立项，本件只交付决策材料。
3. `jlink` 若入选，模块表先用 `jdeps --print-module-deps` 对 thin jar +
   `lib/` 依赖集合自动生成，再人工补反射用例；这本身值得作为试点的一等
   任务。
4. 与 #495 的关系：两者同属「基线红项从源头治理」族。node:24-alpine 的
   6 HIGH 上游已有修复版（等 npm ≥ 12.2.2 搭线），本件的 debian13 7 HIGH
   上游 will-not-fix——**性质不同，本件更urgent**，因为唯一出路就是换基线。

## 附录 A：本机 Trivy 实扫方法（无外网 DB 下载可用）

本机网络对 Trivy 的默认 DB 下载链路不友好（ghcr OCI pull 超时、gcr 阻断），
实证可用的离线取证配方：

```bash
# 1) 用 Docker Desktop 能走的方式拉 DB 镜像（ghcr 可达）
docker pull ghcr.io/aquasecurity/trivy-db:2
docker pull ghcr.io/aquasecurity/trivy-java-db:1   # tag 只有 1
docker save <img> -o /tmp/db.tar                   # OCI artifact，仅 tar 可解

# 2) 从 tar 抽 trivy.db / trivy-java.db（blob 是 gzip tar 层）
python -c "import tarfile,io,gzip,json; ..."       # 见提交历史里的脚本形态

# 3) 注入 trivy-cache 卷（/db/trivy.db + metadata.json，java-db/ 同构）
docker run --rm -v trivy-cache:/cache -v /tmp/extracted:/src:ro alpine \
  sh -c 'mkdir -p /cache/db /cache/java-db && cp /src/* /cache/db/ ...'
#   metadata.json 的 NextUpdate 未过有效期即可被 --skip-db-update 直接使用

# 4) 扫描（镜像走 docker save 成 tar，--input 挂载进容器；stdin 不可用——
#    trivy 需要可 seek 的文件）
docker run --rm -v trivy-cache:/root/.cache/trivy -v /tmp:/work:ro \
  aquasec/trivy:latest image --skip-db-update --skip-java-db-update \
  --scanners vuln --pkg-types os --severity HIGH,CRITICAL --format table \
  --input /work/img-<name>.tar
```

要点：`--pkg-types os`（旧名 `--vuln-type os`）可绕开 Java DB 依赖做纯 OS
层对比；`--input` 必须是卷内可 seek 文件，不能走 stdin。

## 附录 B：扫描原始证据

- `Trivy Base Image Scan (PR light, gcr.io/distroless/java17-debian13:nonroot)`
  在 docs-only PR #499 上 **pass**（与 node:24-alpine 腿的基线红形成对照）
- 本机实扫日志（节选）：distroless 7 HIGH（libexpat1×3 / libuuid1×4）、
  `chainguard/jre:openjdk-17.0.9.5-r1` 52（glibc 2.38 系）、
  `chainguard/jre:latest` 0、`temurin:17-jre-alpine` 0、
  `jlink-proto` 0（alpine 3.24.3, 16 包, 120MB）、
  `chisel-proto` 不可识别、`ubuntu:25.04` 1（gpgv 有修复版）
- 现基线 7 条 CVE 逐条在 `.trivyignore` 内（grep 核对）
