# Cursor / Harness — forbidden-import, logging, terminology checks (Windows)
# Canonical path: .cursor/hooks/run-stop-checks.ps1
# exit 0: all passed  /  exit 2: violation (Claude Code auto-fix loop)

$ErrorActionPreference = 'Continue'

$repoRoot = if ($env:CLAUDE_PROJECT_DIR) {
    $env:CLAUDE_PROJECT_DIR
} else {
    (Get-Item $PSScriptRoot).Parent.Parent.FullName
}
Set-Location -LiteralPath $repoRoot

$scripts = @(
    'scripts\check-forbidden-imports.ps1',
    'scripts\check-logging-forbidden.ps1',
    'scripts\check-terminology-forbidden.ps1'
)

$failed = $false
foreach ($script in $scripts) {
    $fullPath = Join-Path $repoRoot $script
    if (-not (Test-Path -LiteralPath $fullPath)) {
        Write-Host "[SKIP] $script - file not found"
        continue
    }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fullPath
    if ($LASTEXITCODE -ne 0) {
        Write-Error "[FAIL] $script violation detected - please fix and retry"
        $failed = $true
    }
}

if ($failed) { exit 2 } else { exit 0 }
