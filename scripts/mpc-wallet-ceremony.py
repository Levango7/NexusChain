#!/usr/bin/env python3
# =============================================================================
# mpc-wallet-ceremony.py — 为钱包做 CGGMP21 份额仪式（keyshare 供给）
# =============================================================================
# 背景（2026-10-08）：
#   GG20 退役后 CGGMP21 是唯一路径，而**份额只在引擎进程内**（Java 侧不持份额）。
#   一个钱包要能签名，必须先在 3 个引擎上跑一次 DKG 仪式：
#       keygen(t-of-n) → aux_info(Paillier) → assembleShare → 各引擎落盘 KeyShare
#   份额落盘键 = 引擎的 `MPC_ENGINE_SESSION_DIR`（NXC1 信封 AES-256-GCM）。
#   会话 ID 用**钱包维度稳定值**（与 Java `CggmpMpcCryptoEngine.walletSessionId`
#   逐字节一致：`cw-` + SHA-256(walletId) 前 16 字节 hex）——keygen 与后续每笔
#   签名共用同一 ID，引擎才能按 ID 找回份额。
#
#   本脚本是"keyshare 供给"这一环的唯一入口：不跑它，冷钱包签名会以
#   `key_share missing` fail-closed 报错（设计如此，不静默降级）。
#
# 协议时序（与 Java `CggmpClusterSessionDriver` 完全一致，勿改）：
#   · 各方的 Start/Pump RPC 打各自引擎；
#   · **publish/pull 全部打协调器**（--coordinator，默认 0 号引擎）——relay 池
#     宿主是协调器进程，各方共用同一池（"协调器是字节管道"）；
#   · 循环体 = 未完成方 outgoing 全部 publish → 各方按自身 index pull → pump，
#     直到全部 finished（上限 --max-rounds）。
#
# 用法示例（本机三进程 / docker-compose / K8s 均可）：
#   python3 scripts/mpc-wallet-ceremony.py --wallet cw-demo-0001 --sign-probe \
#       --endpoints 127.0.0.1:50051,127.0.0.1:50052,127.0.0.1:50053 \
#       --cacert mpc-certs/ca/CA.pem \
#       --client-cert mpc-certs/node-A/cert.pem --client-key mpc-certs/node-A/key.pem \
#       --token "$MPC_AUTH_TOKEN"
#
# 依赖：python3（标准库）+ grpcurl。
# 退出码：0 成功 / 1 参数或环境错误 / 2 连接失败 / 3 协议失败 / 4 验签失败
# =============================================================================
import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time

METHOD_PREFIX = "nexus.mpc.MpcCryptoService"
DEFAULT_TOKEN = "dev-mpc-engine-token-change-in-prod"
DEFAULT_ENDPOINTS = "127.0.0.1:50051,127.0.0.1:50052,127.0.0.1:50053"
DEFAULT_CERTS = "mpc-certs"


def die(code, msg):
    print("[ceremony] 错误: %s" % msg, file=sys.stderr)
    sys.exit(code)


def pick(obj, *names):
    """protojson 输出用 JSON 名（camelCase），proto 字段名是 snake_case——两者都取。"""
    for n in names:
        if n in obj:
            return obj[n]
    return None


def wallet_session_id(wallet_id):
    """与 Java CggmpMpcCryptoEngine.walletSessionId 逐字节一致。"""
    h = hashlib.sha256(wallet_id.encode("utf-8")).digest()
    return "cw-" + h[:16].hex()


class Engine:
    """一个引擎端点的 grpcurl 调用封装（每次调用独立进程，无长连接状态）。"""

    GRPCURL = shutil.which("grpcurl") or shutil.which("grpcurl.exe")

    def __init__(self, target, tls_args, auth_args, max_time, proto_args=None):
        self.target = target
        self.tls_args = tls_args
        self.auth_args = auth_args
        self.max_time = max_time
        self.proto_args = proto_args or []
        self.calls = 0

    def call(self, method, payload):
        self.calls += 1
        if not Engine.GRPCURL:
            die(1, "未找到 grpcurl——请安装（Windows: go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest；"
                   "Linux: apt install grpcurl）")
        # 请求体经 **stdin**（`-d @`）传入，不经命令行参数：aux 阶段载荷可达
        # 数十 KB，Windows 命令行长度上限（~32K）会直接拒绝 spawn
        # （WinError 206 文件名或扩展名太长——2026-10-08 实测）。
        cmd = ([Engine.GRPCURL, "-max-time", str(self.max_time)]
               + self.tls_args + self.auth_args + self.proto_args
               + ["-d", "@",
                  self.target, "%s/%s" % (METHOD_PREFIX, method)])
        body = json.dumps(payload)
        proc = None
        for attempt in range(3):
            try:
                proc = subprocess.run(cmd, input=body, capture_output=True,
                                      text=True, encoding="utf-8")
                break
            except OSError as e:
                if attempt == 2:
                    die(1, "调用 grpcurl 失败（重试 3 次）: %s" % e)
                time.sleep(1.0 + attempt)

        if proc.returncode != 0:
            err = (proc.stderr or proc.stdout or "").strip()
            return None, "%s @ %s 失败: %s" % (method, self.target, err[:400])
        try:
            return json.loads(proc.stdout), None
        except json.JSONDecodeError as e:
            return None, "%s @ %s 响应非 JSON: %s (%s)" % (method, self.target, proc.stdout[:200], e)

    def ok(self, method, payload, what):
        resp, err = self.call(method, payload)
        if err:
            die(2, err)
        if not resp.get("success", True):
            die(3, "%s: %s" % (what, resp.get("error") or "success=false"))
        return resp


def publish_all(coordinator, session_id, outgoing):
    for m in outgoing:
        msg = dict(m)
        msg["session_id"] = session_id
        coordinator.ok("CgRelayPublish", msg, "relay publish (sender=%s)" % m.get("senderIndex", "?"))


def pull_incoming(coordinator, session_id, party_index):
    resp = coordinator.ok("CgRelayPull",
                          {"session_id": session_id, "my_index": party_index},
                          "relay pull (party %d)" % party_index)
    return resp.get("messages", [])


def pump_loop(session_id, coordinator, engines, start_states, phase,
              pump_method, max_rounds, progress):
    """统一 publish→pull→pump 循环（对齐 Java 驱动 CggmpClusterSessionDriver）。

    start_states: [(party_idx, state)]，state 为 Start* 或上一轮 Pump 的响应——
    **Start* 的首轮 outgoing 必须来自其响应**（引擎在 Start 时已推进第一轮）。
    """
    states = list(start_states)
    for rnd in range(max_rounds):
        if all(st.get("finished") for _, st in states):
            return [st for _, st in states]
        if rnd % 20 == 0 and rnd:
            progress("[%s] 第 %d 轮（未完成 %d 方）" % (phase, rnd,
                     sum(1 for _, st in states if not st.get("finished"))))
        # 1) 未完成方 outgoing 全部发布到协调器
        published_any = False
        for _party_idx, st in states:
            if not st.get("finished") and st.get("outgoing"):
                publish_all(coordinator, session_id, st["outgoing"])
                published_any = True
        # 2) 各方 pull + 3) pump
        next_states = []
        for party_idx, st in states:
            if st.get("finished"):
                next_states.append((party_idx, st))
                continue
            incoming = pull_incoming(coordinator, session_id, party_idx)
            resp = engines[party_idx].ok(pump_method,
                                         {"session_id": session_id, "incoming": incoming},
                                         "%s pump party %d" % (phase, party_idx))
            next_states.append((party_idx, resp))
        states = next_states
        if not published_any:
            # 本轮无人发布（等对端消息）——轻微让出，避免忙等
            time.sleep(0.05)
    die(3, "%s 在 %d 轮内未完成（协议卡住；检查协调器一致性与各方 index）" % (phase, max_rounds))


def run_sign_probe(args, session_id, signers, engines, coordinator, progress,
                   result=None, step_label="[probe]"):
    """t-of-n 签名 + 引擎侧验签（信任根基：验签公钥取自会话而非调用方）。"""
    sign_counter = args.sign_counter
    if sign_counter is None:
        sign_counter = int(time.time()) & 0x7FFFFFFF
    message = ("ceremony-sign-probe:%s" % session_id).encode("utf-8")
    message_hash = hashlib.sha256(message).digest()
    progress("%s 签名探针：%d-of-%d sign(\"ceremony-sign-probe:%s\", eid=%d)..."
             % (step_label, args.t, args.n, session_id, sign_counter))
    sign_states = []
    for k, party_idx in enumerate(signers):
        resp = engines[party_idx].ok("CgStartSign",
                                     {"session_id": session_id, "counter": sign_counter,
                                      "my_index_in_signers": k,
                                      "signers_at_keygen": signers,
                                      "message_hash": base64.b64encode(message_hash).decode()},
                                     "CgStartSign party %d" % party_idx)
        sign_states.append((party_idx, resp))
    sign_results = pump_loop(session_id, coordinator, engines, sign_states, "sign",
                             "CgPumpSign", args.max_rounds, progress)
    rs = [((pick(st, "r_hex", "rHex") or ""), (pick(st, "s_hex", "sHex") or ""))
          for st in sign_results]
    if any(len(r) != 64 or len(s) != 64 for r, s in rs) or len(set(rs)) != 1:
        die(3, "签名探针失败：各方 r/s 缺失或不一致: %s" % rs)
    r_hex, s_hex = rs[0]
    progress("      签名完成 r=%s… s=%s…；引擎侧验签..." % (r_hex[:16], s_hex[:16]))
    ver = coordinator.ok("CgVerifySignature",
                         {"session_id": session_id,
                          "signature_r": base64.b64encode(bytes.fromhex(r_hex)).decode(),
                          "signature_s": base64.b64encode(bytes.fromhex(s_hex)).decode(),
                          "message_hash": base64.b64encode(message_hash).decode()},
                         "CgVerifySignature")
    if not pick(ver, "valid"):
        die(4, "引擎侧验签失败（valid=false）：%s" % ver.get("error"))
    progress("      验签 valid=true ✓")
    return {"signature": {"r": r_hex, "s": s_hex, "message_sha256": message_hash.hex(),
                          "eid": sign_counter}, "verify": True}


def main():
    ap = argparse.ArgumentParser(description="CGGMP21 钱包份额仪式（keygen→aux→assemble）",
                                 add_help=True)
    ap.add_argument("--wallet", help="钱包 ID（引擎会话按 cw-<sha256/16> 派生）")
    ap.add_argument("--session", help="直接给定引擎会话 ID（与 --wallet 二选一）")
    ap.add_argument("--endpoints", default=DEFAULT_ENDPOINTS, help="逗号分隔的引擎端点（默认本机三进程）")
    ap.add_argument("--coordinator", type=int, default=0, help="协调器索引（relay 池宿主，默认 0）")
    ap.add_argument("--n", type=int, default=3, help="参与方数 n（默认 3）")
    ap.add_argument("--t", type=int, default=2, help="阈值 t（默认 2）")
    ap.add_argument("--signers", default="0,1", help="签名方 keygen 索引（默认 0,1 = 2-of-3）")
    ap.add_argument("--counter", type=int, default=0, help="keygen/aux 的 eid 序号（仪式幂等，默认 0；勿改）")
    ap.add_argument("--sign-counter", type=int, default=None,
                    help="签名探针的 eid 序号（默认按时间派生，避免与既有执行撞 eid）")
    ap.add_argument("--sign-probe", action="store_true", help="仪式后跑一次签名 + 引擎侧验签")
    ap.add_argument("--sign-only", action="store_true",
                    help="跳过仪式，仅跑签名探针（份额须已存在；用于重启后持久化取证/日常健康探针）")
    ap.add_argument("--max-rounds", type=int, default=200, help="泵动轮上限（默认 200）")
    ap.add_argument("--plaintext", action="store_true", help="明文 gRPC（无证书环境；TLS 不可用时的显式降级）")
    ap.add_argument("--cacert", default=None, help="CA 证书（默认 <certs-dir>/ca/CA.pem）")
    ap.add_argument("--client-cert", default=None, help="客户端证书（默认 <certs-dir>/node-A/cert.pem）")
    ap.add_argument("--client-key", default=None, help="客户端私钥（默认 <certs-dir>/node-A/key.pem）")
    ap.add_argument("--certs-dir", default=DEFAULT_CERTS, help="证书目录（默认 ./mpc-certs）")
    ap.add_argument("--authority", default="localhost",
                    help="TLS 服务器名校验覆盖（证书 SAN=localhost 时用；传空串关闭）")
    ap.add_argument("--token", default=os.environ.get("MPC_AUTH_TOKEN", DEFAULT_TOKEN),
                    help="Bearer token（默认 $MPC_AUTH_TOKEN 或 dev 占位值）")
    ap.add_argument("--proto", default="mpc-engine/proto/mpc_crypto.proto",
                    help="服务定义 proto（引擎未开 gRPC 反射时必须；默认仓库内 Rust 副本）")
    ap.add_argument("--data-dirs", default=None,
                    help="逗号分隔的各引擎 session 目录（可选：仪式后核对份额文件落盘）")
    ap.add_argument("--json", action="store_true", help="以 JSON 输出结果（供自动化采集）")
    args = ap.parse_args()

    if bool(args.wallet) == bool(args.session):
        die(1, "必须且只能给 --wallet 或 --session 之一")
    if shutil.which("grpcurl") is None:
        die(1, "未找到 grpcurl（本脚本用 grpcurl 调引擎 gRPC）")

    session_id = args.session if args.session else wallet_session_id(args.wallet)
    endpoints = [e.strip() for e in args.endpoints.split(",") if e.strip()]
    if len(endpoints) != args.n:
        die(1, "--endpoints 数量(%d) 必须等于 --n(%d)" % (len(endpoints), args.n))
    if not (0 <= args.coordinator < len(endpoints)):
        die(1, "--coordinator 必须在 [0, %d)" % len(endpoints))
    signers = [int(x) for x in args.signers.split(",") if x.strip() != ""]
    if len(signers) < args.t:
        die(1, "--signers(%d 个) 少于阈值 --t(%d)" % (len(signers), args.t))
    for s in signers:
        if not (0 <= s < args.n):
            die(1, "--signers 含越界索引 %d（[0,%d)）" % (s, args.n))

    # --- gRPC 传输参数 ---
    tls_args, auth_args = [], []
    if args.plaintext:
        tls_args = ["-plaintext"]
        print("[ceremony] 警告: 明文 gRPC（--plaintext）——仅限无证书的本地调试，生产必须 mTLS", file=sys.stderr)
    else:
        cacert = args.cacert or os.path.join(args.certs_dir, "ca", "CA.pem")
        cert = args.client_cert or os.path.join(args.certs_dir, "node-A", "cert.pem")
        key = args.client_key or os.path.join(args.certs_dir, "node-A", "key.pem")
        for path, what in [(cacert, "CA"), (cert, "客户端证书"), (key, "客户端私钥")]:
            if not os.path.isfile(path):
                die(1, "缺 %s 文件: %s（先跑 bash scripts/gen-mpc-certs.sh，或用 --plaintext）" % (what, path))
        tls_args = ["-cacert", cacert, "-cert", cert, "-key", key]
        if args.authority:
            tls_args += ["-authority", args.authority]
    if args.token:
        auth_args = ["-H", "authorization: Bearer %s" % args.token]
    if not os.path.isfile(args.proto):
        die(1, "缺服务定义 proto: %s（引擎未开 gRPC 反射，须用 --proto 指定；仓库内默认路径见 README）"
               % args.proto)
    proto_args = ["-proto", args.proto]

    engines = [Engine(ep, tls_args, auth_args, max_time=600, proto_args=proto_args)
               for ep in endpoints]
    coordinator = engines[args.coordinator]

    def progress(msg):
        print("[ceremony] %s" % msg, flush=True)

    progress("会话 %s（wallet=%s） 端点=%s 协调器=%d 阈值 %d-of-%d signers=%s 传输=%s"
             % (session_id, args.wallet or "-", ",".join(endpoints), args.coordinator,
                args.t, args.n, signers, "plaintext" if args.plaintext else "mTLS"))

    # ---------- --sign-only：跳过仪式，仅签名（须已有份额） ----------
    if args.sign_only:
        if not args.sign_probe:
            die(1, "--sign-only 必须与 --sign-probe 同用（否则无事可做）")
        # 状态仅作报告：进程重启后份额是**惰性恢复**（CgStartSign 的持久化读守卫
        # 才把盘上 keyshare 载入 registry），因此冷启动进程此处 has_key_share=false
        # 属正常。真正的判据是签名结果与引擎侧验签（fail-closed，无份额即报错）。
        for party_idx in signers:
            st = engines[party_idx].ok("CgStatus", {"session_id": session_id},
                                       "CgStatus party %d" % party_idx)
            hot = pick(st, "has_key_share", "hasKeyShare")
            progress("         party %d: has_key_share=%s%s" % (
                party_idx, hot, "" if hot else "（冷启动；签名时从盘恢复）"))
        progress("--sign-only：跳过 keygen/aux/assemble，直接签名（signers=%s）" % signers)
        return run_sign_probe(args, session_id, signers, engines, coordinator, progress)

    # ---------- 1. keygen ----------
    progress("[1/4] keygen 启动（t=%d, n=%d）..." % (args.t, args.n))
    keygen_states = []
    for i in range(args.n):
        resp = engines[i].ok("CgStartKeygen",
                             {"session_id": session_id, "counter": args.counter,
                              "my_index": i, "total_parties": args.n, "threshold": args.t},
                             "CgStartKeygen party %d" % i)
        keygen_states.append((i, resp))
    keygen_results = pump_loop(session_id, coordinator, engines, keygen_states,
                               "keygen", "CgPumpKeygen", args.max_rounds, progress)
    agg_pks = [(pick(st, "aggregate_public_key", "aggregatePublicKey") or "")
               for st in keygen_results]
    if len(set(agg_pks)) != 1 or not agg_pks[0]:
        die(3, "keygen 完成但聚合公钥不一致或为空: %s" % agg_pks)
    progress("      keygen 完成，聚合公钥 = %s" % agg_pks[0])

    # ---------- 2. aux_info ----------
    progress("[2/4] aux_info 生成（Paillier 辅助数据，慢机每方可达 ~80s）...")
    aux_states = []
    for i in range(args.n):
        resp = engines[i].ok("CgStartAux",
                            {"session_id": session_id, "counter": args.counter, "my_index": i,
                             "total_parties": args.n},
                            "CgStartAux party %d" % i)
        aux_states.append((i, resp))
    aux_results = pump_loop(session_id, coordinator, engines, aux_states, "aux",
                            "CgPumpAux", args.max_rounds, progress)
    progress("      aux 完成")

    # ---------- 3. assembleShare ----------
    progress("[3/4] assembleShare（core+aux → 完整 KeyShare，落盘 NXC1 信封）...")
    for i in range(args.n):
        engines[i].ok("CgAssembleShare", {"session_id": session_id},
                      "CgAssembleShare party %d" % i)
    # 状态核对（份额确实驻留引擎）
    for i in range(args.n):
        st = engines[i].ok("CgStatus", {"session_id": session_id}, "CgStatus party %d" % i)
        if not pick(st, "has_key_share", "hasKeyShare"):
            die(3, "party %d 无完整 KeyShare（has_key_share=false）——落盘/装配失败" % i)
    progress("      三方 has_key_share=true")

    result = {"session_id": session_id, "wallet": args.wallet,
              "aggregate_public_key": agg_pks[0], "n": args.n, "t": args.t,
              "signers": signers, "verify": None, "signature": None, "data_dirs": []}

    # ---------- 4. 签名探针（可选） ----------
    if args.sign_probe:
        sig = run_sign_probe(args, session_id, signers, engines, coordinator, progress,
                             result=result, step_label="[4/4]")
        result.update(sig)
    else:
        progress("[4/4] 跳过签名探针（未给 --sign-probe）")

    # ---------- 5. 落盘核对（可选） ----------
    if args.data_dirs:
        for entry in args.data_dirs.split(","):
            entry = entry.strip()
            if not entry:
                continue
            found = []
            for root, _dirs, files in os.walk(entry):
                for fn in files:
                    p = os.path.join(root, fn)
                    try:
                        size = os.path.getsize(p)
                    except OSError:
                        size = -1
                    found.append({"path": p, "size": size})
            result["data_dirs"].append({"dir": entry, "files": found})
            progress("      落盘核对 %s：%d 个文件" % (entry, len(found)))

    progress("仪式完成：会话 %s 聚合公钥 %s" % (session_id, agg_pks[0]))
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("")
        print("=== 仪式结果 ===")
        print("钱包          : %s" % (args.wallet or "-"))
        print("引擎会话 ID   : %s" % session_id)
        print("阈值          : %d-of-%d（signers=%s）" % (args.t, args.n, signers))
        print("聚合公钥      : %s" % agg_pks[0])
        if result["verify"]:
            print("签名探针      : r=%s… s=%s… 验签=valid" % (result["signature"]["r"][:16],
                                                            result["signature"]["s"][:16]))
        print("")
        print("后续：冷钱包签名（ColdWalletMultiSigService）按同一会话 ID 找回份额；")
        print("      引擎重启后份额从 MPC_ENGINE_SESSION_DIR 恢复（无需重做仪式）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
