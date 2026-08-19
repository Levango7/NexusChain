#!/usr/bin/env pwsh
<#
.SYNOPSIS
    NexusChain k6 性能测试 smoke test 运行脚本
.DESCRIPTION
    使用本地安装的 k6 运行 4 个压测场景的 smoke test（低负载快速验证）。
    可选启动 Python mock 服务器模拟 API 端点。
.PARAMETER Scenario
    指定场景：payment-create | payment-query | bridge-lock | webhook-delivery | all
.PARAMETER WithMock
    启动 mock 服务器（默认不启动，k6 会尝试连接 localhost:8080）
.EXAMPLE
    .\run-k6-smoke.ps1 -Scenario payment-create
    .\run-k6-smoke.ps1 -Scenario all -WithMock
#>
param(
    [string]$Scenario = "all",
    [switch]$WithMock
)

$k6Exe = "$PSScriptRoot\..\..\.tools\k6\k6-v0.54.0-windows-amd64\k6.exe"
if (! (Test-Path $k6Exe)) {
    Write-Error "k6 not found at $k6Exe. Download from https://github.com/grafana/k6/releases"
    exit 1
}

# 环境变量
$env:API_KEY = "perf-test-api-key"
$env:SIGNING_SECRET = "perf-test-signing-secret"
$env:BASE_URL_GATEWAY = "http://localhost:8080"
$env:BASE_URL_BRIDGE = "http://localhost:8084"
$env:MERCHANT_ID = "1"

$scenarios = @("payment-create", "payment-query", "bridge-lock", "webhook-delivery")
if ($Scenario -ne "all") { $scenarios = @($Scenario) }

# 可选 mock 服务器
$mockProc = $null
if ($WithMock) {
    $mockProc = Start-Process -FilePath "python" -ArgumentList "$PSScriptRoot\mock_server.py" -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 2
    Write-Host "Mock server started (PID: $($mockProc.Id))" -ForegroundColor Green
}

try {
    foreach ($s in $scenarios) {
        $script = "$PSScriptRoot\$s.js"
        Write-Host "`n=== $s ===" -ForegroundColor Cyan
        & $k6Exe run $script --vus 5 --duration 10s 2>&1
    }
} finally {
    if ($mockProc) { Stop-Process -Id $mockProc.Id -Force -ErrorAction SilentlyContinue }
}