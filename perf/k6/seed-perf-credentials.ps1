# NexusChain k6 压测凭证自助签发（10g，2026-09-14）
# =============================================================================
# 背景：k6 业务场景需要真实商户凭证（API_KEY + SIGNING_SECRET，见 utils/auth.js）。
# 供给链 register（公开）→ verify/api-keys（ADMIN JWT）——gateway 无登录端点，
# ADMIN JWT 由 nexus.security.jwt.secret（JWT_SECRET env）离线 HS256 签发。
# dev compose 已注入确定性 dev 密钥（docker-compose.yml，10g 批）——本脚本
# 用同一密钥离线签发 ADMIN token 后走三连端点，输出 k6 -e 即用命令。
#
# 用法：
#   .\seed-perf-credentials.ps1 [-GatewayBase http://localhost:8080] [-JwtSecret <dev密钥>]
#
# 仅限本地 dev / 演练环境。生产严禁使用本脚本（生产密钥经 SealedSecret 注入，
# 不应出现已知默认值）。
# =============================================================================
param(
    [string]$GatewayBase = "http://localhost:8080",
    # 与 docker-compose.yml 的 JWT_SECRET 默认值一致（可被 NEX_DEV_JWT_SECRET 覆盖）
    [string]$JwtSecret = "nexus-dev-only-jwt-secret-0123456789abcdef"
)

$ErrorActionPreference = "Stop"

function ConvertTo-Base64Url([byte[]]$Bytes) {
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# --- 1. 离线签发 ADMIN JWT（契约对齐 JwtTokenProvider.generateToken：HS256，
#        sub/iat/exp/roles 逗号串；JwtAuthenticationFilter 映射 ROLE_<role>） ---
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$headerJson = '{"alg":"HS256","typ":"JWT"}'
$payloadJson = '{"sub":"perf-admin","iat":' + $now + ',"exp":' + ($now + 900) + ',"roles":"ADMIN"}'
$h64 = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($headerJson))
$p64 = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payloadJson))
$hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($JwtSecret))
try {
    $sig64 = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$h64.$p64")))
} finally {
    $hmac.Dispose()
}
$jwt = "$h64.$p64.$sig64"
Write-Host "[1/4] ADMIN JWT 离线签发完成（15 分钟有效）"

# --- 2. 注册压测商户（公开端点） ---
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$registerBody = @{
    merchantName      = "perf-k6-$stamp"
    email             = "perf-k6-$stamp@invalid"
    settlementAddress = "0x0000000000000000000000000000000000000000"
} | ConvertTo-Json -Compress
$merchant = Invoke-RestMethod -Method Post -Uri "$GatewayBase/api/v1/merchants/register" `
    -ContentType "application/json" -Body $registerBody
if (-not $merchant.id) { throw "register 响应缺少 id：$merchant" }
Write-Host "[2/4] 商户已注册 id=$($merchant.id) name=$($merchant.merchantName)"

# --- 3. ADMIN 核验（api-keys 前置 hygiene） ---
Invoke-RestMethod -Method Post -Uri "$GatewayBase/api/v1/merchants/$($merchant.id)/verify" `
    -ContentType "application/json" -Headers @{ Authorization = "Bearer $jwt" } `
    -Body '{"status":"VERIFIED"}' | Out-Null
Write-Host "[3/4] 商户已核验 VERIFIED"

# --- 4. 签发 API key + 签名密钥（仅此一次返回） ---
$keyPair = Invoke-RestMethod -Method Post -Uri "$GatewayBase/api/v1/merchants/$($merchant.id)/api-keys" `
    -Headers @{ Authorization = "Bearer $jwt" }
if (-not $keyPair.apiKey -or -not $keyPair.secret) { throw "api-keys 响应缺少 apiKey/secret：$keyPair" }
Write-Host "[4/4] API 凭证签发完成（merchantId=$($merchant.id)）"
Write-Host ""
Write-Host "==================== k6 凭证（妥善保管，仅此一次） ===================="
Write-Host "API_KEY        : $($keyPair.apiKey)"
Write-Host "SIGNING_SECRET : $($keyPair.secret)"
Write-Host ""
Write-Host "k6 即用命令："
Write-Host "  k6 run -e BASE_URL_GATEWAY=$GatewayBase -e API_KEY=$($keyPair.apiKey) -e SIGNING_SECRET=$($keyPair.secret) perf/k6/payment-create.js"
Write-Host "======================================================================"
