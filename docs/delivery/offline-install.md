# 离线安装 SOP（私有化交付包）

> **适用**：把 NexusChain 交付给客户/持牌方，在**目标环境**（无外网）完成安装与自证。
> **配套**：`docs/delivery/acceptance-checklist.md`（逐条验收）、`docs/delivery/demo-runbook.md`（30 分钟演示）、
> `scripts/build-delivery-bundle.sh`（生成交付包）、`scripts/doctor.sh`（装完先体检）。

---

## 0. 先定形态：交付包是「自包含」还是「被纳管」

本交付包按**自包含**设计：客户用自己的 k8s / compose 即可安装，**不需要**先装任何我们的平台。
如果最终采用「由运维平台统一纳管」的形态（同一厂商已有运维/部署控制台的情况），
本包仍然适用——它提供的是**可被纳管的部署输入**：

| 形态 | 本包怎么用 | 谁来做 |
|---|---|---|
| **A 自包含交付** | 客户环境直接 `helm install` / `docker compose up` | 客户运维或交付方 |
| **B 被运维平台纳管** | 平台侧消费 `charts/nexus-chain-*.tgz` + `images.lock` + `dependencies.lock`，按纳管参数下发；健康探针走 `/actuator/health`，指标走 `/actuator/prometheus`（需 token），日志走 stdout | 平台侧实施 |

> **形态 B 的前提**（本包不承诺）：平台侧需支持「按镜像清单拉取/导入镜像 + 应用 helm chart +
> 注入参数 + 采集健康/指标/日志」。**本仓库不集成、不依赖任何特定运维平台**——
> 避免把一个交付包绑死在某个平台上。是否用某个平台是**商务/交付形态的决定**，不是本仓库的技术前提。

---

## 1. 交付包内容

```
delivery-bundle-<时间戳>/
├── charts/nexus-chain-2.77.0.tgz   # helm umbrella chart（8 个子 chart，含 nexus-core / mpc-engine）
├── jars/nexus-gateway-*.jar        # 预构建 jar（--with-jars）——裸进程安装路径，**不需源码与 Gradle**
├── scripts/                        # 全量自检/验收/演练/纳管入口（105 个）——交付方拿到包即可自证
├── evidence/                       # LICENSE/NOTICE + .trivyignore + 安全基线 + 依赖清单（7 份）
├── images/*.tar                    # 镜像 tar（仅 --with-images 且本地已有镜像）
├── .env.prod.example               # 19 个强制变量模板（根级，交付方填值）
├── docker-compose.prod.yml         # compose 路径清单（根级）
├── images.lock                     # 镜像清单（image<TAB>来源），19 条
├── jars.lock                       # jar 清单（jar<TAB>模块<TAB>sha256）
├── dependencies.lock               # 机器可读依赖清单（版本/JDK/清单入口/镜像/jar）
├── helm-render.yaml                # 以 values-prod 渲染出的**实际** manifests
├── manifest.json                   # 交付清单（版本、提交、镜像、SKIP 项）
└── SHA256SUMS                      # 逐文件校验和
```

> **为什么必须带 `jars/`**：`docker-compose.prod.yml` 里 nexus-* 服务全是 `build:`（**从源码构建**），
> 离线客户机若没有源码树 + Gradle/Rust 缓存就装不出来 ⇒ 交付包必须给**预构建产物**。
> 这也是「jar 裸进程路径」存在的原因（§4.3）。
>
> **为什么必须带 `evidence/`**：私有化交付必问「许可怎么声明、你们豁免了什么漏洞」——
> LICENSE/NOTICE 与 `.trivyignore`/基线必须在包里，不能只在仓库里。

## 1.1 交付包生成

```bash
# 只出 chart + 清单 + 校验和（不需要 Docker）
bash scripts/build-delivery-bundle.sh --out /tmp/delivery-bundle

# 连预构建 jar 一起出（需要本机已构建过 jar）
bash scripts/build-delivery-bundle.sh --out /tmp/delivery-bundle --with-jars

# 连镜像 tar 一起出（需要本机已有这些镜像；脚本不联网）
bash scripts/build-delivery-bundle.sh --out /tmp/delivery-bundle --with-images --with-jars
```

生成后会打印产物清单与 **SKIP 项**（如「本地缺 N 个镜像 ⇒ 离线包不完整」）——
**SKIP 不等于通过**，必须按清单补齐后再交付。

## 2. 在有网环境生成

```bash
# 只出 chart + 清单 + 校验和（不需要 Docker）
bash scripts/build-delivery-bundle.sh --out /tmp/delivery-bundle

# 连镜像一起出（需要本机已有这些镜像；脚本不联网）
bash scripts/build-delivery-bundle.sh --out /tmp/delivery-bundle --with-images
```

生成后会打印产物清单与 **SKIP 项**（如「本地缺 N 个镜像 ⇒ 离线包不完整」）——
**SKIP 不等于通过**，必须按清单补齐后再交付。

## 3. 校验与转运

```bash
cd delivery-bundle
sha256sum -c SHA256SUMS        # 逐文件比对；任一 mismatch 即停止
```

整目录拷贝到目标环境（tar/zip 均可，注意 `images/` 可能很大：8 个 nexuschain 镜像 + 基础设施镜像）。

## 2.1 离线整链演练（发包前在本机自证，2026-11-12 实测全绿）

```bash
bash scripts/ops/offline-drill.sh /tmp/delivery-bundle \
  [--old-jar <上一版本.jar>]      # 给了才会跑升级/回滚那一段
```

它只用**包里的 jar + 包里的脚本**走完整链路：

| 步骤 | 实测（2026-11-12，包 v2.77.0 + v2.76.0 旧 jar） |
|---|---|
| `SHA256SUMS` 逐文件校验 | **PASS** |
| 包内 `doctor.sh` 体检 | **PASS**（PASS 2 / FAIL 0 / SKIP 12；SKIP 的是**依赖源码树**的项） |
| 包内 jar 起 gateway | **PASS**（已监听） |
| 商户准备 | **PASS** |
| 包内端到端验收 | **PASS 4 / FAIL 0** |
| 包内升级/回滚演练 | **PASS 11 / FAIL 0** |
| 合计 | **PASS 6 / FAIL 0 / SKIP 0** |

> **包内 doctor 为什么大部分是 SKIP**：版本口径/卫生棘轮/调度锁/事件接线/ddl-auto 等检查需要
> **源码树与 git 历史**，交付包里没有 ⇒ 记 SKIP（缺前置），不是 FAIL。
> 完整体检请在源码环境跑 `scripts/doctor.sh`（本机实测 PASS 12 / FAIL 0 / SKIP 2）。

> **这条演练没覆盖什么**：**真断网**未模拟（脚本本身不联网，但宿主机网络未断）——
> 需交付方在真断网机复测；compose/helm 安装路径也未覆盖（本机 Docker daemon 不可用）。

## 4. 离线安装（形态 A）

### 4.1 Helm 路径（推荐）

前置：目标集群已有 helm、已准备好以下**外部依赖**（本 chart **不**包含它们，只注入指向它们的地址）：
Nacos / Sentinel / Seata / Zipkin / PostgreSQL(或 MySQL) / Redis。

```bash
# 1) 导入镜像（离线环境）
for f in images/*.tar; do docker load -i "$f"; done

# 2) 参数：19 个强制变量（清单见 .env.prod.example，compose 里是 ${VAR:?} 形式）
cp .env.prod.example .env.prod     # 由交付方填值（地址、密钥、token）
#    校验清单一致性（交付方本机即可跑）
bash scripts/check-prod-env.sh

# 3) 安装
helm upgrade --install nexus-chain ./charts/nexus-chain-2.77.0.tgz \
  -f deploy/helm/values-prod.yaml \
  --set imagePullPolicy=IfNotPresent
```

### 4.2 Compose 路径（单机/演示）

```bash
cp .env.prod.example .env.prod && 填值
bash scripts/check-prod-env.sh
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

> compose 路径的镜像来自 `images.lock` 的 `compose:` 来源行；helm 路径来自 `helm:` 来源行。

### 4.3 裸进程路径（jar，客户机无 Docker / 无 Gradle）

适用：客户机没有容器运行时，或不便离线构建镜像。交付包里的 `jars/` 就是为这条路径准备的。

```bash
# 1) 起网关（文件型 H2，便于重启留存；生产换 MySQL/PG 的连接串）
JWT_SECRET='<≥32 字节随机串>' NEX_SETTLEMENT_ENABLED=false \
java -jar jars/nexus-gateway-2.77.0.jar \
  --spring.profiles.active=sandbox --server.port=8080 \
  --spring.jpa.hibernate.ddl-auto=update \
  --spring.datasource.url='jdbc:h2:file:/var/lib/nexus/gateway;MODE=MySQL'

# 2) 体检与验收都直接用包内脚本
bash scripts/doctor.sh
NEX_API_KEY=<key> NEX_MERCHANT_ID=<id> bash scripts/acceptance-e2e.sh
```

> 这条路径在本机已整链实测（§2.1：**PASS 6 / FAIL 0 / SKIP 0**）。
> 注意 `JWT_SECRET` 必须显式给：缺省时网关启动期随机生成，外部自签 ADMIN token 会 403。

## 5. 装完先体检（不要跳这一步）

```bash
bash scripts/doctor.sh          # 期望 exit 0；SKIP 项看说明
```

体检覆盖 14 项仓库自检（版本口径 / env 清单 / 许可头 / 卫生棘轮 / 打包一致性 / 调度锁 /
事件接线 / ddl-auto 门禁 / 许可策略 / 豁免时效 / 迁移号段 / 板面门禁 / 主树卫生 / MPC 分层），
**离线可跑、不联网**。

## 6. 端到端自证（证明能收款、能对账）

```bash
# 按 docs/delivery/demo-runbook.md §1~§3 起沙箱网关并准备商户，然后：
NEX_API_KEY=<key> NEX_MERCHANT_ID=<id> bash scripts/acceptance-e2e.sh
# 期望：PASS 4 / FAIL 0
```

## 7. 指标与告警面

```bash
curl -H "Authorization: Bearer $NEXUS_METRICS_TOKEN" http://<host>:<port>/actuator/prometheus
# 期望：200 + nexus_* 指标（22 个）；无 token / 错 token 一律 401（fail-closed）
```

## 8. 升级 / 回滚

- 应用层口径与本机实跑记录：`docs/runbooks/upgrade-rollback.md`（第七节 + 第十一节）；
- 集群路径（`helm rollback` / `kubectl rollout undo` / 快照恢复）**需在目标环境实跑**——
  仓库侧只提供脚本与口径（`scripts/upgrade-rollback-drill.sh` 覆盖应用层，PASS 11 / FAIL 0）。

## 9. 失败排查

| 现象 | 常见原因 | 处置 |
|---|---|---|
| `helm install` 报缺 values | 未用 `values-prod.yaml` | 按 §4.1 补 `-f` |
| Pod CrashLoop / 启动即抛「平台出款地址未配置」 | 结算开关与地址未配 | 显式 `nexus.settlement.enabled=false`（沙箱/演示）或下发真实地址（生产） |
| 镜像拉取失败（离线） | 未 `docker load` | 按 §4.1 导入；核对 `images.lock` 是否齐全 |
| 验收第 4 步 `SKIP` | 未传 `NEX_MERCHANT_ID` | 显式传；**SKIP ≠ 通过** |
| 指标端点 401 | token 未注入或传错 | 见 §7；口径 `docs/runbooks/metrics-auth.md` |
| `doctor.sh` 报 SKIP | 缺本地产物（Rust 子工程 / Trivy 扫描件） | SKIP 不阻断；CI 内会补齐 |

## 10. 本包的已知缺口（诚实标注）

1. **不产出 SBOM / CVE 状态**：仓库侧有 Trivy 门禁与基线（`config/trivy-baseline.json`），
   交付包会带上**豁免账与基线**（`evidence/`），但**不带逐镜像漏洞报告**——
   需要的话由交付方在有网侧跑扫描后随包提供。
2. **不包含外部依赖**：PostgreSQL/MySQL、Redis、Nacos/Sentinel/Seata/Zipkin 不在包内，
   需按 §4.1 前提自行准备（这是有意为之：把数据库选型留给客户，不绑架）。
3. **不保证形态 B 的平台侧实现**：本包只提供输入，纳管逻辑属平台侧。
4. **真断网机未实测**：交付包生成 + **jar 裸进程整链**已在本机实测（§2.1，PASS 6 / FAIL 0 / SKIP 0），
   但「宿主机完全断网」与「compose/helm 安装路径」两段**未实测**——
   前者需交付方断网环境，后者需 Docker（本机 daemon 不可用）。
5. **其他模块的 jar 未随包**：本地只构建了 `nexus-gateway` 一个 jar，
   其余 5 个模块（core / signing / wallet / bridge / api-gateway）在生成时记 SKIP；
   发包前需在构建机补齐（`./gradlew :nexus-core:bootJar …`）后重新生成包。
