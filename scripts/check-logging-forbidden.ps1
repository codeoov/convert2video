# Forbid direct android.util.Log in app sources — use utils/AppLogger when logging is needed
# exit 0: ok  /  exit 2: violation

$ErrorActionPreference = 'Continue'
$repoRoot = if ($env:CLAUDE_PROJECT_DIR) { $env:CLAUDE_PROJECT_DIR } else {
    (Get-Item $PSScriptRoot).Parent.FullName
}
Set-Location -LiteralPath $repoRoot

$scanRoot = Join-Path $repoRoot 'app\src\main'
$patterns = @(
    'import\s+android\.util\.Log\b',
    '\bLog\.(v|d|i|w|e|wtf)\s*\('
)

$violations = @()
if (Test-Path -LiteralPath $scanRoot) {
    $files = Get-ChildItem -Path $scanRoot -Recurse -Filter *.kt -File -ErrorAction SilentlyContinue
    foreach ($file in $files) {
        # Allow a single logger implementation file to wrap android.util.Log
        if ($file.Name -eq 'AppLogger.kt') { continue }
        $lines = Get-Content -LiteralPath $file.FullName -ErrorAction SilentlyContinue
        $lineNo = 0
        foreach ($line in $lines) {
            $lineNo++
            if ($line -match '^\s*//') { continue }
            foreach ($pat in $patterns) {
                if ($line -match $pat) {
                    $rel = $file.FullName.Substring($repoRoot.Length).TrimStart('\', '/')
                    $violations += "${rel}:${lineNo}: $line"
                }
            }
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host '[FAIL] check-logging-forbidden.ps1 — use AppLogger (or add logging only inside AppLogger.kt)'
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 2
}

Write-Host '[OK] check-logging-forbidden.ps1'
exit 0
