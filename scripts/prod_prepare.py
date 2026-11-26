#!/usr/bin/env python3
"""Generate and validate NexusChain production preparation artifacts."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import stat
import subprocess
import sys
import time
from pathlib import Path

REQUIRED_RE = re.compile(r"\$\{([A-Z_][A-Z0-9_]*):\?")
ENV_RE = re.compile(r"^([A-Z_][A-Z0-9_]*)=(.*)$")
HEX64_RE = re.compile(r"^[0-9a-fA-F]{64}$")
NODE_NAMES = ("node-A", "node-B", "node-C")
SIGNING_CLIENT = "client-signing"
DEV_STORAGE_KEY = "0123456789abcdef" * 4
PLACEHOLDERS = (
    "CHANGE_ME",
    "CHANGE-IN-PROD",
    "REPLACE_ME",
    "dev-mpc-engine-token-change-in-prod".upper(),
    DEV_STORAGE_KEY.upper(),
)
NON_SECRET_KEYS = {
    "GRAFANA_ADMIN_USER",
    "MYSQL_USER",
    "NACOS_USERNAME",
    "POSTGRES_USER",
    "SENTINEL_USERNAME",
}
WINDOWS = os.name == "nt" or sys.platform.startswith(("msys", "cygwin"))


class ProdError(Exception):
    def __init__(self, message: str, code: int = 1):
        super().__init__(message)
        self.code = code


def required_keys(compose: Path) -> list[str]:
    if not compose.is_file():
        raise ProdError(f"缺少 compose 文件：{compose}", 2)
    return sorted(set(REQUIRED_RE.findall(compose.read_text(encoding="utf-8"))))


def parse_env(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ProdError(f"缺少环境文件：{path}", 2)
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = ENV_RE.match(line)
        if not match:
            raise ProdError(f"环境文件第 {number} 行不是 KEY=VALUE", 1)
        key, value = match.groups()
        if key in values:
            raise ProdError(f"环境文件重复声明 {key}", 1)
        value = value.strip()
        if value.startswith('"'):
            try:
                value = json.loads(value)
            except json.JSONDecodeError as exc:
                raise ProdError(f"环境变量 {key} 的双引号值无效", 1) from exc
        elif value.startswith("'"):
            if len(value) < 2 or not value.endswith("'"):
                raise ProdError(f"环境变量 {key} 的单引号值未闭合", 1)
            value = value[1:-1].replace("\\'", "'")
        if not isinstance(value, str):
            raise ProdError(f"环境变量 {key} 必须是字符串", 1)
        values[key] = value
    return values


def jwt_secret(app_id: str, app_secret: str) -> bytes:
    combined = (app_id + app_secret).encode("ascii")
    padded = combined + b"=" * (-len(combined) % 4)
    try:
        decoded = base64.b64decode(padded, validate=True)
    except (ValueError, UnicodeEncodeError) as exc:
        raise ProdError("NEXUS_JWT_APP_ID + NEXUS_JWT_APP_SECRET 不是合法 Base64", 1) from exc
    if len(decoded) < 32:
        decoded += bytes(32 - len(decoded))
    return decoded


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def generate_rpc_token(app_id: str, app_secret: str, ttl_days: int) -> tuple[str, int]:
    now = int(time.time())
    expiry = now + ttl_days * 86400
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(
        json.dumps(
            {"jti": "1", "iat": now, "sub": "JWTToken", "iss": "admin", "exp": expiry},
            separators=(",", ":"),
        ).encode()
    )
    signed = f"{header}.{payload}".encode("ascii")
    signature = b64url(hmac.new(jwt_secret(app_id, app_secret), signed, hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}", expiry


def verify_rpc_token(token: str, app_id: str, app_secret: str) -> None:
    parts = token.split(".")
    if len(parts) != 3:
        raise ProdError("NEXUS_RPC_TOKEN 不是 compact JWT", 1)
    try:
        header_raw = base64.urlsafe_b64decode(parts[0] + "=" * (-len(parts[0]) % 4))
        payload_raw = base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4))
        signature = base64.urlsafe_b64decode(parts[2] + "=" * (-len(parts[2]) % 4))
        header = json.loads(header_raw)
        payload = json.loads(payload_raw)
    except (ValueError, json.JSONDecodeError) as exc:
        raise ProdError("NEXUS_RPC_TOKEN 编码或 JSON 无效", 1) from exc
    if header.get("alg") != "HS256":
        raise ProdError("NEXUS_RPC_TOKEN 必须使用 HS256", 1)
    expected = hmac.new(jwt_secret(app_id, app_secret), f"{parts[0]}.{parts[1]}".encode("ascii"), hashlib.sha256).digest()
    if not hmac.compare_digest(signature, expected):
        raise ProdError("NEXUS_RPC_TOKEN 签名与 core JWT 配置不匹配", 1)
    expiry = payload.get("exp")
    if not isinstance(expiry, (int, float)) or expiry <= time.time() + 86400:
        raise ProdError("NEXUS_RPC_TOKEN 已过期或有效期不足 24 小时", 1)


def validate_env(values: dict[str, str], required: list[str]) -> None:
    errors: list[str] = []
    for key in required:
        value = values.get(key, "")
        if not value:
            errors.append(f"{key} 为空")
            continue
        upper = value.upper()
        if any(marker in upper for marker in PLACEHOLDERS):
            errors.append(f"{key} 仍是占位值")
            continue
        if key not in NON_SECRET_KEYS and len(value) < 16:
            errors.append(f"{key} 长度不足 16 字符")
    if "MPC_STORAGE_KEY" in values:
        key = values["MPC_STORAGE_KEY"]
        if not HEX64_RE.fullmatch(key):
            errors.append("MPC_STORAGE_KEY 必须是 64 位 hex")
        elif int(key, 16) == 0:
            errors.append("MPC_STORAGE_KEY 不允许全零")
    if "JWT_SECRET" in values and len(values["JWT_SECRET"].encode("utf-8")) < 32:
        errors.append("JWT_SECRET 至少 32 字节")
    if errors:
        raise ProdError("环境变量校验失败：" + "；".join(errors), 1)
    app_id = values.get("NEXUS_JWT_APP_ID", "")
    app_secret = values.get("NEXUS_JWT_APP_SECRET", "")
    token = values.get("NEXUS_RPC_TOKEN", "")
    if app_id and app_secret and token:
        verify_rpc_token(token, app_id, app_secret)


def generate_values(required: list[str], ttl_days: int) -> dict[str, str]:
    app_id = secrets.token_hex(16)
    app_secret = secrets.token_hex(32)
    rpc_token, _ = generate_rpc_token(app_id, app_secret, ttl_days)
    generated = {
        "MPC_STORAGE_KEY": secrets.token_hex(32),
        "MPC_AUTH_TOKEN": secrets.token_urlsafe(32),
        "JWT_SECRET": secrets.token_urlsafe(32),
        "NEXUS_JWT_APP_ID": app_id,
        "NEXUS_JWT_APP_SECRET": app_secret,
        "NEXUS_RPC_TOKEN": rpc_token,
        "NACOS_AUTH_IDENTITY_KEY": secrets.token_hex(16),
        "NACOS_AUTH_IDENTITY_VALUE": secrets.token_urlsafe(24),
        "NACOS_AUTH_TOKEN": secrets.token_urlsafe(32),
        "NACOS_USERNAME": "nexus",
        "NACOS_PASSWORD": secrets.token_urlsafe(24),
        "SEATA_CONFIG_KEY": secrets.token_hex(16),
        "SENTINEL_USERNAME": "nexus",
        "SENTINEL_PASSWORD": secrets.token_urlsafe(24),
        "POSTGRES_USER": "nexus",
        "POSTGRES_PASSWORD": secrets.token_urlsafe(24),
        "MYSQL_ROOT_PASSWORD": secrets.token_urlsafe(24),
        "MYSQL_PASSWORD": secrets.token_urlsafe(24),
        "REDIS_PASSWORD": secrets.token_urlsafe(24),
        "GRAFANA_ADMIN_USER": "nexus",
        "GRAFANA_ADMIN_PASSWORD": secrets.token_urlsafe(24),
        "NEXUS_METRICS_TOKEN": secrets.token_urlsafe(32),
    }
    missing = [key for key in required if key not in generated]
    if missing:
        raise ProdError("尚未定义生成规则：" + ", ".join(missing), 1)
    return generated


def atomic_create(path: Path, content: str) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(path, flags, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        path.unlink(missing_ok=True)
        raise
    os.chmod(path, 0o600)


def prepare_env(root: Path, output: Path, ttl_days: int) -> int:
    compose = root / "docker-compose.prod.yml"
    example = root / ".env.prod.example"
    required = required_keys(compose)
    if not example.is_file():
        raise ProdError(f"缺少变量模板：{example}", 2)
    if output.exists():
        values = parse_env(output)
        validate_env(values, required)
        print(f"[PASS] 环境文件已存在且通过校验，未修改：{output}")
        return 0
    if not output.parent.is_dir():
        raise ProdError(f"输出目录不存在：{output.parent}", 2)
    values = generate_values(required, ttl_days)
    order: list[str] = []
    for raw in example.read_text(encoding="utf-8").splitlines():
        match = ENV_RE.match(raw.strip())
        if match and match.group(1) not in order:
            order.append(match.group(1))
    for key in required:
        if key not in order:
            order.append(key)
    lines = ["# Generated by scripts/prod_prepare.py; contains secrets and must not be committed."]
    for key in order:
        value = values.get(key, "")
        lines.append(f"{key}={value}")
    atomic_create(output, "\n".join(lines) + "\n")
    print(f"[PASS] 已生成环境文件：{output}（{len(required)} 个强制变量）")
    print(f"[PASS] NEXUS_RPC_TOKEN 有效期：{ttl_days} 天")
    return 0


def run_openssl(args: list[str]) -> str:
    try:
        result = subprocess.run(
            ["openssl", *args],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError as exc:
        raise ProdError("无法执行 openssl", 2) from exc
    if result.returncode != 0:
        detail = result.stderr.strip().splitlines()
        suffix = f"：{detail[-1]}" if detail else ""
        raise ProdError(f"openssl 检查失败：{' '.join(args[:2])}{suffix}", 1)
    return result.stdout


def windows_mount(path: Path) -> bool:
    return WINDOWS or bool(re.match(r"^/mnt/[A-Za-z]/", str(path.resolve())))


def check_private_mode(path: Path) -> None:
    if not windows_mount(path):
        mode = stat.S_IMODE(path.stat().st_mode)
        if mode & 0o077:
            raise ProdError(f"私钥权限过宽：{path}", 1)


def runtime_uid(variable: str = "NEXUS_MPC_RUNTIME_UID") -> int:
    raw = os.environ.get(variable, "65532" if variable == "NEXUS_SIGNING_RUNTIME_UID" else "1000")
    try:
        uid = int(raw)
    except ValueError as exc:
        raise ProdError(f"{variable} 必须是非负整数", 2) from exc
    if uid < 0:
        raise ProdError(f"{variable} 必须是非负整数", 2)
    return uid


def check_runtime_owner(
    path: Path,
    variable: str = "NEXUS_MPC_RUNTIME_UID",
    role: str = "MPC 非 root",
) -> None:
    if windows_mount(path):
        return
    expected_uid = runtime_uid(variable)
    uid = path.stat().st_uid
    if uid != expected_uid:
        raise ProdError(
            f"{role}运行 UID {expected_uid} 无法读取私有挂载文件：{path}（当前属主 UID {uid}）；"
            "请在部署主机用 chown 或等效 ACL 授权",
            1,
        )


def validate_certificates(certs_dir: Path, skip_openssl: bool) -> None:
    ca = certs_dir / "ca" / "CA.pem"
    if not ca.is_file():
        raise ProdError(f"缺少 MPC CA 证书：{ca}", 2)
    for node in (*NODE_NAMES, SIGNING_CLIENT):
        for name in ("cert.pem", "key.pem"):
            path = certs_dir / node / name
            if not path.is_file():
                raise ProdError(f"缺少 MPC 文件：{path}", 2)
        key = certs_dir / node / "key.pem"
        check_private_mode(key)
        if node == SIGNING_CLIENT:
            check_runtime_owner(key, "NEXUS_SIGNING_RUNTIME_UID", "签名服务非 root")
        else:
            check_runtime_owner(key)
    if skip_openssl:
        print("[PASS] MPC 证书文件存在（openssl 深度检查已显式跳过）")
        return
    if not shutil.which("openssl"):
        raise ProdError("缺少 openssl，无法验证 MPC 证书链、SAN/EKU 与私钥匹配", 2)
    run_openssl(["x509", "-in", str(ca), "-noout", "-checkend", "86400"])
    run_openssl(["verify", "-CAfile", str(ca), str(ca)])
    for node in (*NODE_NAMES, SIGNING_CLIENT):
        cert = certs_dir / node / "cert.pem"
        key = certs_dir / node / "key.pem"
        run_openssl(["x509", "-in", str(cert), "-noout", "-checkend", "86400"])
        run_openssl(["verify", "-CAfile", str(ca), "-purpose", "sslserver", str(cert)])
        run_openssl(["verify", "-CAfile", str(ca), "-purpose", "sslclient", str(cert)])
        san = run_openssl(["x509", "-in", str(cert), "-noout", "-ext", "subjectAltName"])
        if not re.search(r"(?:^|[,\s])DNS:localhost(?:[,\s]|$)", san) or not re.search(
            r"(?:^|[,\s])IP(?: Address)?:127\.0\.0\.1(?:[,\s]|$)", san
        ):
            raise ProdError(f"{node} 证书缺少 DNS:localhost 或 IP:127.0.0.1", 1)
        eku = run_openssl(["x509", "-in", str(cert), "-noout", "-ext", "extendedKeyUsage"])
        if "TLS Web Server Authentication" not in eku or "TLS Web Client Authentication" not in eku:
            raise ProdError(f"{node} 证书缺少 serverAuth/clientAuth EKU", 1)
        cert_pub = run_openssl(["x509", "-in", str(cert), "-pubkey", "-noout"]).strip()
        key_pub = run_openssl(["pkey", "-in", str(key), "-pubout"]).strip()
        if cert_pub != key_pub:
            raise ProdError(f"{node} 证书与私钥不匹配", 1)
    print("[PASS] MPC CA、三节点与签名客户端证书链、SAN/EKU、有效期与私钥匹配")


def expected_config(index: int, node: str) -> dict:
    peers = [
        {"party_index": other, "party_id": f"party-{other}", "endpoint": f"https://mpc-engine-{other}:50051"}
        for other in range(3)
        if other != index
    ]
    return {
        "party_index": index,
        "party_id": f"party-{index}",
        "listen_addr": "0.0.0.0:50051",
        "peers": peers,
        "storage_key": "",
        "storage_key_version": 1,
        "storage_key_source": "env",
        "tls_cert": f"/etc/mpc/certs/{node}/cert.pem",
        "tls_key": f"/etc/mpc/certs/{node}/key.pem",
        "tls_ca": "/etc/mpc/certs/ca/CA.pem",
    }


def validate_configs(config_dir: Path) -> None:
    for index, node in enumerate(NODE_NAMES):
        path = config_dir / f"node{index}.json"
        if not path.is_file():
            raise ProdError(f"缺少 MPC 配置：{path}", 2)
        check_private_mode(path)
        check_runtime_owner(path)
        try:
            actual = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ProdError(f"MPC 配置不是合法 JSON：{path}", 1) from exc
        expected = expected_config(index, node)
        if actual != expected:
            raise ProdError(f"MPC 配置与生产三方拓扑或 env 主钥模式不一致：{path}", 1)
    print("[PASS] MPC node0/1/2 配置为三方拓扑，storage_key_source=env 且无明文主钥")


def validate_compose(root: Path) -> None:
    compose = root / "docker-compose.prod.yml"
    text = compose.read_text(encoding="utf-8")
    if text.count("NEXUS_MPC_STORAGE_KEY=${MPC_STORAGE_KEY:?") < 3:
        raise ProdError("production compose 未给三个 MPC 引擎注入 NEXUS_MPC_STORAGE_KEY", 1)
    if "mpc-certs:/etc/mpc/certs" in text or "CA.key" in text:
        raise ProdError("production compose 不得挂载共享 mpc-certs 卷或 CA.key", 1)
    required_mounts = (
        "./mpc-certs/ca/CA.pem:/etc/mpc/certs/ca/CA.pem:ro",
        "./mpc-certs/node-A/cert.pem:/etc/mpc/certs/node-A/cert.pem:ro",
        "./mpc-certs/node-A/key.pem:/etc/mpc/certs/node-A/key.pem:ro",
        "./mpc-certs/node-B/cert.pem:/etc/mpc/certs/node-B/cert.pem:ro",
        "./mpc-certs/node-B/key.pem:/etc/mpc/certs/node-B/key.pem:ro",
        "./mpc-certs/node-C/cert.pem:/etc/mpc/certs/node-C/cert.pem:ro",
        "./mpc-certs/node-C/key.pem:/etc/mpc/certs/node-C/key.pem:ro",
        "./mpc-certs/client-signing/cert.pem:/etc/mpc/certs/client-signing/cert.pem:ro",
        "./mpc-certs/client-signing/key.pem:/etc/mpc/certs/client-signing/key.pem:ro",
    )
    missing = [mount for mount in required_mounts if mount not in text]
    if missing:
        raise ProdError("production compose 缺少最小权限证书 bind mount", 1)
    signing_block = text.split("  nexus-signing-service:", 1)[1].split("\n  nexus-wallet-service:", 1)[0]
    if "node-A/key.pem" in signing_block or "client-signing/key.pem" not in signing_block:
        raise ProdError("签名服务必须使用独立 client-signing 身份，不得复用 MPC 节点私钥", 1)
    print("[PASS] production compose 使用最小权限证书 bind mount，未挂载 CA.key 或签名服务复用节点私钥")


def check(root: Path, env_file: Path, certs_dir: Path, config_dir: Path, skip_openssl: bool) -> int:
    required = required_keys(root / "docker-compose.prod.yml")
    values = parse_env(env_file)
    validate_env(values, required)
    if not windows_mount(env_file) and stat.S_IMODE(env_file.stat().st_mode) & 0o077:
        raise ProdError(f"环境文件权限过宽：{env_file}", 1)
    print(f"[PASS] 环境文件通过 {len(required)} 项强制变量与 JWT 语义校验")
    validate_configs(config_dir)
    validate_certificates(certs_dir, skip_openssl)
    validate_compose(root)
    if windows_mount(env_file):
        print("[WARN] Windows 无法用 POSIX mode 位证明 ACL；生产部署应在 POSIX 主机复核权限")
    print("[PASS] production preparation 预检通过")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="NexusChain production environment and MPC preparation")
    sub = parser.add_subparsers(dest="command", required=True)

    prepare = sub.add_parser("prepare-env", help="create .env.prod once; validate without overwriting if it exists")
    prepare.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    prepare.add_argument("--output", type=Path)
    prepare.add_argument("--rpc-token-ttl-days", type=int, default=90)

    verify = sub.add_parser("check", help="read-only production preparation preflight")
    verify.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    verify.add_argument("--env-file", type=Path)
    verify.add_argument("--certs-dir", type=Path)
    verify.add_argument("--config-dir", type=Path)
    verify.add_argument("--skip-openssl", action="store_true")

    args = parser.parse_args()
    root = args.root.resolve()
    try:
        if args.command == "prepare-env":
            if args.rpc_token_ttl_days < 2:
                raise ProdError("RPC token 有效期至少 2 天", 1)
            output = args.output.resolve() if args.output else root / ".env.prod"
            return prepare_env(root, output, args.rpc_token_ttl_days)
        env_file = args.env_file.resolve() if args.env_file else root / ".env.prod"
        certs_dir = args.certs_dir.resolve() if args.certs_dir else root / "mpc-certs"
        config_dir = args.config_dir.resolve() if args.config_dir else root / "mpc-certs" / "config-prod"
        return check(root, env_file, certs_dir, config_dir, args.skip_openssl)
    except ProdError as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        return exc.code
    except OSError as exc:
        print(f"[FAIL] 文件操作失败：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
