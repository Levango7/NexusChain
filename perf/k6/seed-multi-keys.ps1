# 容量实测多密钥批量签发（2026-10-03）：dev InMemoryRateLimiter 为 300 次/分钟/API key
# 硬编码——单 key 只够 5 rps。本脚本批量 seed 商户并输出逗号分隔 API_KEYS，
# 供 capacity.js 按 VU 轮换（签名不含 API key，可直接覆写头）。
param(
    [string]$GatewayBase = "http://localhost:18080",
    [string]$JwtSecret = "nexus-dev-only-jwt-secret-0123456789abcdef",
    [int]$Count = 40
)
$ErrorActionPreference = "Stop"
function ConvertTo-Base64Url([byte[]]$Bytes) {
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$headerJson = '{"alg":"HS256","typ":"JWT"}'
$payloadJson = '{"sub":"perf-admin","iat":' + $now + ',"exp":' + ($now + 1800) + ',"roles":"ADMIN"}'
$h64 = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($headerJson))
$p64 = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payloadJson))
$hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($JwtSecret))
$jwt = "$h64.$p64." + (ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$h64.$p64"))))
$hmac.Dispose()
$keys = @()
for ($i = 1; $i -le $Count; $i++) {
    $stamp = Get-Date -Format "HHmmssfff"
    $body = @{ merchantName = "perf-cap-$i-$stamp"; email = "perf-cap-$i-$stamp@invalid"; settlementAddress = "0x0000000000000000000000000000000000000000" } | ConvertTo-Json -Compress
    $m = Invoke-RestMethod -Method Post -Uri "$GatewayBase/api/v1/merchants/register" -ContentType "application/json" -Body $body
    Invoke-RestMethod -Method Post -Uri "$GatewayBase/api/v1/merchants/$($m.id)/verify" -ContentType "application/json" -Headers @{ Authorization = "Bearer $jwt" } -Body '{"status":"VERIFIED"}' | Out-Null
    $kp = Invoke-RestMethod -Method Post -Uri "$GatewayBase/api/v1/merchants/$($m.id)/api-keys" -Headers @{ Authorization = "Bearer $jwt" }
    $keys += $kp.apiKey
    if ($i % 10 -eq 0) { Write-Host "seeded $i/$Count" }
}
Write-Host ("API_KEYS=" + ($keys -join ","))
