#requires -version 5.1
<#
.SYNOPSIS
  Bootstraps a local .env for CacheRelay from .env.docker.example with generated secrets.
.DESCRIPTION
  Copies the compose template and fills every hard-required secret with a locally generated
  random value (hex, via System.Security.Cryptography.RandomNumberGenerator). No external
  tools, no network access, no paid services. Provider API keys are intentionally left blank:
  the gateway boots without them and the free local path uses Ollama (see README).
  Never commit the generated .env (it is gitignored).
.PARAMETER Source
  Template path relative to the repository root (default: .env.docker.example).
.PARAMETER OutFile
  Output path (default: .env in the repository root). Relative paths are resolved against the repo root.
.PARAMETER Force
  Overwrite an existing .env.
.EXAMPLE
  .\scripts\init-env.ps1
.EXAMPLE
  .\scripts\init-env.ps1 -OutFile "$env:TEMP\cacherelay-env-test" -Force
#>
[CmdletBinding()]
param(
    [string]$Source = ".env.docker.example",
    [string]$OutFile = ".env",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$sourcePath = if ([System.IO.Path]::IsPathRooted($Source)) { $Source } else { Join-Path $repoRoot $Source }
$outPath = if ([System.IO.Path]::IsPathRooted($OutFile)) { $OutFile } else { Join-Path $repoRoot $OutFile }

if (-not (Test-Path -LiteralPath $sourcePath)) {
    Write-Error "Template not found: $sourcePath (expected the repository's .env.docker.example)"
    exit 1
}
if ((Test-Path -LiteralPath $outPath) -and -not $Force) {
    Write-Error "$outPath already exists. Pass -Force to overwrite it (your current file will be replaced)."
    exit 1
}

function New-HexSecret {
    param([int]$ByteCount)
    $bytes = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString("x2") })
}

# Every value below is hex, so it is safe unquoted in .env and compose interpolation.
$generated = [ordered]@{
    "POSTGRES_PASSWORD"            = New-HexSecret 32
    "POSTGRES_EXPORTER_PASSWORD"   = New-HexSecret 32
    "REDIS_PASSWORD"               = New-HexSecret 32
    "REDIS_CACHE_PASSWORD"         = New-HexSecret 32
    "GATEWAY_ADMIN_MASTERKEY"      = New-HexSecret 32
    "GATEWAY_AUTH_JWT_SECRET"      = New-HexSecret 32
    "GATEWAY_MCP_HITL_SECRET"      = New-HexSecret 32
    "GRAFANA_ADMIN_PASSWORD"       = New-HexSecret 16
}

$content = Get-Content -LiteralPath $sourcePath -Raw
$content = $content -replace "`r`n", "`n"

foreach ($name in $generated.Keys) {
    $pattern = "(?m)^" + [regex]::Escape($name) + "=.*$"
    if ($content -notmatch $pattern) {
        Write-Error "Template is missing required variable '$name' in $sourcePath; update the template first."
        exit 1
    }
    $content = [regex]::Replace($content, $pattern, "$name=" + $generated[$name])
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
if ($Force) {
    [System.IO.File]::WriteAllText($outPath, $content, $utf8NoBom)
} else {
    # Atomic create: closes the TOCTOU window between the existence check and the write.
    try {
        $stream = [System.IO.File]::Open($outPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            $writer = New-Object System.IO.StreamWriter($stream, $utf8NoBom)
            try { $writer.Write($content) } finally { $writer.Dispose() }
        } finally { $stream.Dispose() }
    } catch [System.IO.IOException] {
        Write-Error "$outPath already exists. Pass -Force to overwrite it (your current file will be replaced)."
        exit 1
    }
}
# Best-effort privacy: strip inherited ACLs and grant only the current user. Localized/domain
# setups may refuse; the file still works, so this never fails the bootstrap.
try {
    & icacls.exe $outPath /inheritance:r /grant:r "$($env:USERDOMAIN)\$($env:USERNAME):(R,W)" | Out-Null
} catch {
    Write-Host "Note: could not restrict .env permissions automatically; keep this file private." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Created $outPath" -ForegroundColor Green
Write-Host "Generated local secrets (values not shown): $($generated.Keys -join ', ')"
Write-Host "Provider API keys were left blank: the gateway boots without them; use local Ollama for a free stack."
Write-Host "Keep this file private - it is gitignored. Next: docker compose --profile deps up -d"
