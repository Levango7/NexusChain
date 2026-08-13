<#
.SYNOPSIS
  Stop and remove the compose-managed nexus-pgsql container (data volume preserved).

.DESCRIPTION
  Targets ONLY the compose-defined nexus-pgsql service. Ad-hoc containers (e.g. a
  manually `docker run`'d PG) are never touched. The named volume pg-dev-data is
  kept so the database survives restarts; use `docker volume rm pg-dev-data` only
  if you explicitly want to reset the database.

.NOTES
  ASCII-only comments: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $RepoRoot 'docker-compose.yml'

Write-Host "[dev-pg] Stopping nexus-pgsql (volume pg-dev-data preserved)..." -ForegroundColor Cyan
Push-Location $RepoRoot
try {
  docker compose -f $ComposeFile stop nexus-pgsql
  if ($LASTEXITCODE -ne 0) { throw 'docker compose stop failed.' }
  docker compose -f $ComposeFile rm -f nexus-pgsql
  if ($LASTEXITCODE -ne 0) { throw 'docker compose rm failed.' }
} finally { Pop-Location }

Write-Host "[dev-pg] Done. To also delete the database volume: docker volume rm pg-dev-data" -ForegroundColor Cyan
