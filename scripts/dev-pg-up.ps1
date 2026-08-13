<#
.SYNOPSIS
  Bring up a containerized PostgreSQL for NexusChain core local integration (真机联调).

.DESCRIPTION
  The core persistence layer is bound to the PostgreSQL dialect, so the correct
  local-development posture is "containerized PG + core running natively" via the
  'local' Spring profile (see nexus-core/nexus-core/src/main/resources/application-local.properties).

  This script is idempotent and non-destructive:
    [1/3] Ensures the Docker engine is reachable; auto-starts Docker Desktop on Windows if needed.
    [2/3] Ensures a healthy PostgreSQL on 127.0.0.1:55432 (nexus/nexus123/nexuschain):
            - reuses an existing healthy PG container that publishes the port (never
              touches ad-hoc containers like a manually `docker run`'d nexus-pg);
            - otherwise creates the compose-managed nexus-pgsql service (docker-compose.yml);
            - fails with a clear message if the port is held by a non-PG process.
    [3/3] Optionally starts core in the foreground with the local profile (-StartCore).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\dev-pg-up.ps1
  powershell -ExecutionPolicy Bypass -File scripts\dev-pg-up.ps1 -StartCore

.NOTES
  Comments/output are intentionally ASCII-only: Windows PowerShell 5.1 decodes .ps1
  files without a BOM as ANSI, which would garble non-ASCII text.
#>
[CmdletBinding()]
param(
  # Also start core (foreground) once PG is healthy. Ctrl+C stops core; PG stays up.
  [switch]$StartCore,
  # Max seconds to wait for the Docker engine (e.g. cold Docker Desktop start).
  [int]$DockerTimeoutSec = 180
)

$ErrorActionPreference = 'Stop'

$RepoRoot     = Split-Path -Parent $PSScriptRoot
$ComposeFile  = Join-Path $RepoRoot 'docker-compose.yml'
$Port         = 55432
$DbUser       = 'nexus'
$DbName       = 'nexuschain'
$DockerDesktopPaths = @(
  (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'),
  (Join-Path ${env:ProgramFiles(x86)} 'Docker\Docker\Docker Desktop.exe')
)

function Write-Step([string]$Msg) { Write-Host "[dev-pg] $Msg" -ForegroundColor Cyan }

function Test-DockerEngine {
  docker info *> $null
  return ($LASTEXITCODE -eq 0)
}

function Wait-DockerEngine([int]$TimeoutSec) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Test-DockerEngine) { return $true }
    Start-Sleep -Seconds 5
  }
  return $false
}

function Start-DockerDesktop {
  foreach ($p in $DockerDesktopPaths) {
    if (Test-Path $p) { Start-Process -FilePath $p; return $true }
  }
  $cmd = Get-Command 'Docker Desktop' -ErrorAction SilentlyContinue
  if ($cmd) { Start-Process -FilePath $cmd.Source; return $true }
  return $false
}

function Get-PgContainerOnPort {
  # First running container that publishes $Port on the host, or $null.
  $names = docker ps --filter "publish=$Port" --format '{{.Names}}' 2>$null
  if (-not $names) { return $null }
  return ($names -split "`r?`n" | Where-Object { $_ -ne '' } | Select-Object -First 1)
}

function Test-PgReady([string]$Container) {
  docker exec $Container pg_isready -U $DbUser -d $DbName -h localhost *> $null
  return ($LASTEXITCODE -eq 0)
}

function Wait-PgContainerHealthy([string]$Container, [int]$TimeoutSec) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  $status = ''
  while ((Get-Date) -lt $deadline) {
    $status = docker inspect -f '{{.State.Health.Status}}' $Container 2>$null
    if ($status -eq 'healthy') { return $true }
    Start-Sleep -Seconds 3
  }
  Write-Host "[dev-pg] Last known health status: '$status'" -ForegroundColor Yellow
  return $false
}

# ---------- [1/3] Docker engine ----------
Write-Step "[1/3] Docker engine"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw 'Docker CLI not found. Install Docker Desktop (https://www.docker.com/products/docker-desktop/) first.'
}
if (-not (Test-DockerEngine)) {
  Write-Step "Docker daemon unreachable - starting Docker Desktop..."
  if (-not (Start-DockerDesktop)) {
    throw 'Docker Desktop executable not found in the standard locations. Start it manually, then re-run this script.'
  }
  if (-not (Wait-DockerEngine $DockerTimeoutSec)) {
    throw "Docker engine did not become reachable within ${DockerTimeoutSec}s. Check Docker Desktop (WSL2 backend) and re-run."
  }
  Write-Step "Docker engine is up."
} else {
  Write-Step "Docker engine already reachable."
}

# ---------- [2/3] PostgreSQL on 127.0.0.1:55432 ----------
Write-Step "[2/3] PostgreSQL (127.0.0.1:$Port, $DbUser/$DbName)"

$container = Get-PgContainerOnPort
if ($container) {
  # Port is held by a Docker container - retry pg_isready briefly in case it is
  # still booting, then either reuse it or fail with a clear message.
  $ready = $false
  for ($i = 0; $i -lt 10; $i++) {
    if (Test-PgReady $container) { $ready = $true; break }
    Start-Sleep -Seconds 3
  }
  if ($ready) {
    Write-Step "Reusing existing healthy PostgreSQL container: $container"
  } else {
    throw "Port $Port is occupied by container '$container' but pg_isready still fails. Inspect it: docker logs $container"
  }
} elseif (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
  throw "Port $Port is in use by a non-Docker process. Free it (or change the port convention in application-local.properties and docker-compose.yml) and re-run."
} else {
  Write-Step "Creating compose-managed nexus-pgsql (docker-compose.yml)..."
  Push-Location $RepoRoot
  try {
    docker compose -f $ComposeFile up -d nexus-pgsql
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up -d nexus-pgsql failed.' }
  } finally { Pop-Location }
  if (-not (Wait-PgContainerHealthy 'nexus-pgsql' 120)) {
    throw 'nexus-pgsql did not become healthy within 120s. Check: docker logs nexus-pgsql'
  }
  Write-Step "nexus-pgsql is healthy."
}

Write-Step "PostgreSQL ready: jdbc:postgresql://127.0.0.1:$Port/$DbName ($DbUser)"

# ---------- [3/3] Core (optional) ----------
if ($StartCore) {
  Write-Step "[3/3] Starting core with local profile (foreground). Ctrl+C to stop; PG stays up."
  Push-Location $RepoRoot
  try {
    & .\gradlew.bat :nexus-core:nexus-core:run --args="--spring.profiles.active=local"
    if ($LASTEXITCODE -ne 0) { throw "core exited with code $LASTEXITCODE" }
  } finally { Pop-Location }
}

Write-Step "Done. Tear down with scripts\dev-pg-down.ps1 (data volume preserved)."
