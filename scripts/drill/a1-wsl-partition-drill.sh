#!/usr/bin/env bash
# ============================================================================
# a1-wsl-partition-drill.sh —— S2「网络分区」场景的 Tier-2 实现（WSL 内核内）
#
# 对应文档：docs/drill/multi-node-drill-plan.md §S2（含 2026-11-16 执行结果与边界）
# 收账：roadmap #27（最终性故障注入矩阵）网络分区项
#
# 形态：**在 WSL（或其他 Linux）内核内**跑 3 个节点进程（各 1000 自报质押），
#       用 iptables 制造真分区。与 docs/drill/drill-common.sh 的 Tier-2（多机 + SSH）
#       是**同一档的另一种实现**：单内核、多进程、按进程 UID 切流。
#
# 为什么用 iptables 的 owner 匹配而不是别的手法：
#   * Windows 防火墙：建规则需管理员（实测 Access is denied）且 loopback 通常豁免；
#   * docker network disconnect（方案书 Tier-1）：需先构建应用镜像，本机未必有；
#   * **同机多进程时按「端口」写 DROP 是错的**——规则是主机级的，会把探针和其它节点
#     之间的流量一起切断（v1 实测：连 root 探针都断、A↔D 也互断 ⇒ 根本不是分区实验）。
#     ⇒ 必须用 `-m owner --uid-owner` 按**进程**切，且链只挂 OUTPUT（owner 只在该链有效）。
#     发送侧双向切断即可：A→C 由 A 的规则挡、C→A 由 C 的规则挡。
#
# 判据（预登记；详见方案书 §S2）：
#   P0 基线  : 三方各 1000（total=3000），最终性推进（lag≈2）
#   P1 隔离 C: 多数侧 A+B = 2000/3000 = 66.7% ≥ 2/3 ⇒ **仍应最终化**（活性）
#              C 单侧 = 1000/3000 = 33% ⇒ **f 必须冻结**（安全性：少数派不许自认最终）
#              （注意：C **仍会出块**——按本地链头；最终性才是保护点）
#   P2 治愈  : C 回滚自己的分叉分支后追平；最终性恢复；
#              审计端点 epoch→canonicalHash 三方一致（无分叉），且无 HASH_MISMATCH
#   指标      : nexus_finality_vote_rejected_total{reason} 采样（判据 3/5）
#
# 环境变量：
#   A1_REPO     仓库根（默认由脚本位置上溯两级的绝对路径）
#   A1_PGUSER   PG 角色（默认 zcode_a1，需先建；见 --help-notes）
#   A1_PGPASS   PG 口令（缺省则读 A1_PGPASS_FILE，默认 /tmp/a1-pgpass）
#   A1_WORK     工作目录（默认 /tmp/a1-partition）
#   A1_SKIP_PG_RESET=1  不重建数据库（复用现有库，便于快速重跑）
#
# 凭据纪律：本脚本**不含任何口令字面量**；PG 口令与 metrics token 都现场生成/从文件读。
# ============================================================================
set -u

A1_REPO="${A1_REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
CORE="$A1_REPO/nexus-core/nexus-core"
WORK="${A1_WORK:-/tmp/a1-partition}"
CHAIN=ZCODEA1
PGHOST=127.0.0.1; PGPORT="${A1_PGPORT:-5433}"
PGUSER="${A1_PGUSER:-zcode_a1}"
if [ -n "${A1_PGPASS:-}" ]; then PGPASS="$A1_PGPASS"; else PGPASS=$(cat "${A1_PGPASS_FILE:-/tmp/a1-pgpass}" 2>/dev/null || true); fi
if [ -z "$PGPASS" ]; then
  echo "ABORT: 缺 PG 口令。设 A1_PGPASS，或先建角色并写 /tmp/a1-pgpass（600）。" >&2
  echo "       角色最小形态：CREATE ROLE zcode_a1 LOGIN CREATEDB; ALTER ROLE zcode_a1 PASSWORD '<pw>';" >&2
  exit 1
fi
export PGPASSWORD="$PGPASS"

# metrics token：ActuatorPrometheusAuthFilter 未配 token 时 **fail-closed 回 503**，
# 所以必须给节点配 token 并带 Bearer 头抓取。
MTOKEN=$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')
export NEXUS_METRICS_TOKEN="$MTOKEN"

A_HTTP=19596; A_P2P=9296
B_HTTP=19597; B_P2P=9297
C_HTTP=19598; C_P2P=9298
BOOT="nexus://127.0.0.1:${A_P2P}"          # 引导节点 = A（P2P 注册表初值来自库/广播）

A_USER=a1a; B_USER=a1b; C_USER=a1c
for u in $A_USER $B_USER $C_USER; do
  id -u "$u" >/dev/null 2>&1 || { echo "ABORT: 用户 $u 不存在（useradd -M -s /usr/sbin/nologin $u）" >&2; exit 1; }
done
A_UID=$(id -u $A_USER); B_UID=$(id -u $B_USER); C_UID=$(id -u $C_USER)
A_GID=$(id -g $A_USER); B_GID=$(id -g $B_USER); C_GID=$(id -g $C_USER)

# 64-hex 私钥现场生成（与最终性投票签名的密钥面一致；不写字面量）
KA=$(printf 'a%.0s' $(seq 1 64))
KB=$(printf 'b%.0s' $(seq 1 64))
KC=$(printf 'c%.0s' $(seq 1 64))

stamp() { date '+%H:%M:%S'; }
say() { echo "$(stamp) $*"; }
LIB=$(ls "$CORE"/build/install/nexus-core/lib/*.jar 2>/dev/null | grep -v 'nexus-core-' | tr '\n' ':')
CP="$CORE/build/classes/java/main:$CORE/build/resources/main:$LIB"

psqlc() { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$1" -tAc "$2" 2>&1 | tr -d '\r'; }

probe() {
  curl -s -m 4 "http://127.0.0.1:${1}/consensus/finality" 2>/dev/null | python3 -c '
import json,sys
try: j=json.load(sys.stdin)
except Exception: print("err"); raise SystemExit
p=j.get("progress") or {}
print("h=%s f=%s lag=%s voted=%s/%s" % (j.get("height"), j.get("finalizedCheckpoints"),
      j.get("finalityLag"), p.get("votedWeight"), p.get("totalWeight")))' || echo err
}
height() { curl -s -m 3 "http://127.0.0.1:$1/consensus/status" 2>/dev/null | python3 -c '
import json,sys
try: print(json.load(sys.stdin).get("currentHeight"))
except Exception: print(-1)' || echo -1; }
audit_of() { curl -s -m 6 "http://127.0.0.1:$1/consensus/finality/checkpoints/audit" 2>/dev/null > "$2" || true; }
metrics_of() {
  local out
  out=$(curl -s -m 6 -H "Authorization: Bearer $MTOKEN" "http://127.0.0.1:$1/actuator/prometheus" 2>/dev/null \
        | grep -E '^nexus_finality_vote_rejected_total' || true)
  [ -z "$out" ] && echo "      [$2] <no samples>" || echo "$out" | sed "s/^/      [$2] /"
}
forbidden_flood() {
  curl -s -m 6 -H "Authorization: Bearer $MTOKEN" "http://127.0.0.1:$1/actuator/prometheus" 2>/dev/null \
    | grep -E '^nexus_finality_vote_rejected_total\{.*reason="(checkpoint_limit_exceeded|payload_too_large)"' \
    | awk '{s+=$NF} END{print s+0}'
}

cleanup() {
  iptables -D OUTPUT -j $CHAIN 2>/dev/null
  iptables -F $CHAIN 2>/dev/null
  iptables -X $CHAIN 2>/dev/null
  pkill -f 'org.nexus.Start' 2>/dev/null; sleep 2; pkill -9 -f 'org.nexus.Start' 2>/dev/null
}
trap cleanup EXIT

isolate_C() { # 按进程 UID 切：C→{A,B} 与 {A,B}→C 双向；A↔B 不受影响
  iptables -A $CHAIN -m owner --uid-owner $C_UID -p tcp -m multiport --dports "${A_P2P},${B_P2P},${A_HTTP},${B_HTTP}" -j DROP
  iptables -A $CHAIN -m owner --uid-owner $A_UID -p tcp -m multiport --dports "${C_P2P},${C_HTTP}" -j DROP
  iptables -A $CHAIN -m owner --uid-owner $B_UID -p tcp -m multiport --dports "${C_P2P},${C_HTTP}" -j DROP
}
heal() { iptables -F $CHAIN; }

start_node() { # $1=http $2=p2p $3=db $4=key $5=bootstrap(1/0) $6=name $7=uid $8=gid
  local args=(-server -Xss2m -Xmx1G -cp "$CP" org.nexus.Start
    --spring.profiles.active=local
    "--spring.datasource.url=jdbc:postgresql://${PGHOST}:${PGPORT}/$3"
    --spring.datasource.driver-class-name=org.postgresql.Driver
    "--spring.datasource.username=${PGUSER}" "--spring.datasource.password=${PGPASS}"
    "--server.port=$1" "--p2p.address=nexus://127.0.0.1:$2"
    "--nexus.cache-dir=$WORK/cache-$1"
    --nexus.consensus.proposer-strategy=round-robin
    "--nexus.consensus.validator-private-key=$4"
    --nexus.consensus.min-validators-to-mine=2 --nexus.consensus.enable-mining=true
    --nexus.consensus.block-interval=2 --nexus.finality.epoch-length=2
    --p2p.enable-discovery=true
    --nexus.finality.anti-entropy.enabled=true
    --nexus.finality.anti-entropy.interval-ms=5000
    --nexus.finality.anti-entropy.margin-epochs=200
    --nexus.finality.anti-entropy.max-votes=200
    --nexus.finality.anti-entropy.paging-enabled=true
    --nexus.finality.anti-entropy.paging-grace-ms=6000)
  [ "$5" = "1" ] && args+=("--p2p.bootstraps=$BOOT")
  nohup setpriv --reuid="$7" --regid="$8" --clear-groups java "${args[@]}" > "$WORK/$6.log" 2> "$WORK/$6.err.log" &
  echo $!
}

say "[0] A-1 分区钻（WSL/Linux 内核内，按 UID 切出向）"
cleanup; sleep 2
rm -rf "$WORK"; mkdir -p "$WORK"; chmod 777 "$WORK"
iptables -N $CHAIN; iptables -I OUTPUT -j $CHAIN
say "    UID: A=$A_UID($A_USER) B=$B_UID($B_USER) C=$C_UID($C_USER)；链 $CHAIN 只挂 OUTPUT"

if [ "${A1_SKIP_PG_RESET:-0}" != "1" ]; then
  say "[1] 重建三个独立库"
  for db in nexuschain_a1a nexuschain_a1b nexuschain_a1c; do
    psqlc postgres "DROP DATABASE IF EXISTS $db WITH (FORCE)" >/dev/null
    psqlc postgres "CREATE DATABASE $db OWNER $PGUSER" >/dev/null
  done
  say "    ok: $(psqlc postgres "SELECT count(*) FROM pg_database WHERE datname LIKE 'nexuschain_a1%'")"
else
  say "[1] 跳过库重建（A1_SKIP_PG_RESET=1）"
fi

say "[2] 启动 A/B/C"
PA=$(start_node $A_HTTP $A_P2P nexuschain_a1a "$KA" 0 A $A_UID $A_GID)
PB=$(start_node $B_HTTP $B_P2P nexuschain_a1b "$KB" 1 B $B_UID $B_GID)
PC=$(start_node $C_HTTP $C_P2P nexuschain_a1c "$KC" 1 C $C_UID $C_GID)
say "    A pid=$PA B pid=$PB C pid=$PC"

say "[3] 收敛 h>=60（最多 480s）"
for i in $(seq 1 48); do
  sleep 10
  ha=$(height $A_HTTP); hb=$(height $B_HTTP); hc=$(height $C_HTTP)
  [ $((i % 4)) -eq 0 ] && say "    A=$ha B=$hb C=$hc"
  if [ "$ha" -ge 60 ] 2>/dev/null && [ "$hb" -ge 60 ] 2>/dev/null && [ "$hc" -ge 60 ] 2>/dev/null; then
    say "    收敛 A=$ha B=$hb C=$hc"; break
  fi
done

say "[4] P0 基线 60s（期望 total=3000，f 推进）"
for i in 1 2 3 4 5 6; do
  sleep 10
  say "    +$((i*10))s A[$(probe $A_HTTP)] B[$(probe $B_HTTP)] C[$(probe $C_HTTP)]"
done
say "    指标基线:"; metrics_of $A_HTTP "A-base"

say "[5] P1 隔离 C（按 UID 切出向；A↔B 不受影响，root 探针不受影响）"
isolate_C
iptables -S $CHAIN | sed 's/^/    RULE: /'
for i in $(seq 1 12); do
  sleep 10
  say "    +$((i*10))s A[$(probe $A_HTTP)] B[$(probe $B_HTTP)] C[$(probe $C_HTTP)]"
done
say "    分区期指标:"; metrics_of $A_HTTP "A-partition"; metrics_of $C_HTTP "C-partition"

say "[6] P2 治愈（清规则）→ 追平与最终性恢复"
heal
for i in $(seq 1 12); do
  sleep 10
  say "    +$((i*10))s A[$(probe $A_HTTP)] B[$(probe $B_HTTP)] C[$(probe $C_HTTP)]"
done
say "    治愈后指标:"; metrics_of $A_HTTP "A-healed"; metrics_of $C_HTTP "C-healed"
say "    判据 5 计数（期望 0）: A=$(forbidden_flood $A_HTTP) B=$(forbidden_flood $B_HTTP) C=$(forbidden_flood $C_HTTP)"

say "[7] 无分叉判据：审计端点逐行对账（epoch→canonicalHash / verdict）"
audit_of $A_HTTP "$WORK/audit-A.json"; audit_of $B_HTTP "$WORK/audit-B.json"; audit_of $C_HTTP "$WORK/audit-C.json"
python3 - "$WORK" <<'PY'
import json,sys,os
w=sys.argv[1]
def load(n):
    p=os.path.join(w,f"audit-{n}.json")
    if not os.path.exists(p) or os.path.getsize(p)==0: return None
    try: return json.load(open(p))
    except Exception as e: return {"_parse_error":str(e),"verdict":"ERR"}
maps={}
for n in ("A","B","C"):
    j=load(n)
    if j is None: print(f"  {n}: <no data>"); continue
    m={}
    for r in (j.get("checks") or []):
        try: m[int(r["epoch"])]=(r.get("canonicalHash") or r.get("storedHash") or "?")
        except Exception: pass
    maps[n]=m
    print(f"  {n}: verdict={j.get('verdict')} rows={j.get('recordedRows')} maxEpoch={j.get('maxRecordedEpoch')} "
          f"lagEpochs={j.get('finalityLagEpochs')} tally={j.get('tally')}")
if len(maps)>=2:
    common=sorted(set.intersection(*[set(m) for m in maps.values()]))
    diff=[k for k in common if len({m[k] for m in maps.values()})>1]
    print(f"  共同 epoch 数={len(common)}；**哈希不一致的 epoch 数={len(diff)}**")
    for k in diff[:5]: print("   DIFF epoch",k,{n:maps[n][k][:16] for n in maps})
PY

say "[8] 证据汇总"
for db in nexuschain_a1a nexuschain_a1b nexuschain_a1c; do
  printf "    validators_synced(%s)=%s\n" "$db" "$(psqlc $db 'SELECT count(*) FROM validators_synced')"
done
say "    C 治愈后 catch-up/sync 行:"
grep -iE "blocks received|Vote-sync|full-table replay" "$WORK/C.log" 2>/dev/null | tail -4 | cut -c1-160 | sed 's/^/      C: /'
say "    iptables 残留行数（1=仅链头）: $(iptables -S $CHAIN | wc -l)"
say "[9] done a1"
