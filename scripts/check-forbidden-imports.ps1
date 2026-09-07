# Forbidden imports / packages for convert2video (local-only app)
# exit 0: ok  /  exit 2: violation

$ErrorActionPreference = 'Continue'
$repoRoot = if ($env:CLAUDE_PROJECT_DIR) { $env:CLAUDE_PROJECT_DIR } else {
    (Get-Item $PSScriptRoot).Parent.FullName
}
Set-Location -LiteralPath $repoRoot

$scanRoots = @(
    (Join-Path $repoRoot 'app\src\main'),
    (Join-Path $repoRoot 'app\src\test'),
    (Join-Path $repoRoot 'app\src\androidTest')
)

# Network / DI / previous-app stacks must not sneak in without an explicit product decision.
# okhttp3 is not in the forbidden list below; no path allowlist check here — youtube/·drive/ OkHttp policy (ktor-server-core/cio not in use there) is enforced by review and CLAUDE.md §1 only.
# io.ktor.* is not in the forbidden list below; no path allowlist check here — desktopsync/ ktor-server-core/cio policy is enforced by CLAUDE.md §1 Phase 1 한정 예외 and review only.
$forbiddenImportPatterns = @(
    'import\s+retrofit2\.',
    'import\s+dagger\.hilt\.',
    'import\s+com\.google\.gson\.',
    'import\s+com\.example\.happy_v12\.',
    'import\s+io\.flutter\.'
)

$violations = @()
foreach ($root in $scanRoots) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    $files = Get-ChildItem -Path $root -Recurse -Include *.kt, *.kts -File -ErrorAction SilentlyContinue
    foreach ($file in $files) {
        $lines = Get-Content -LiteralPath $file.FullName -ErrorAction SilentlyContinue
        $lineNo = 0
        foreach ($line in $lines) {
            $lineNo++
            foreach ($pat in $forbiddenImportPatterns) {
                if ($line -match $pat) {
                    $rel = $file.FullName.Substring($repoRoot.Length).TrimStart('\', '/')
                    $violations += "${rel}:${lineNo}: $line"
                }
            }
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host '[FAIL] check-forbidden-imports.ps1'
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 2
}

Write-Host '[OK] check-forbidden-imports.ps1'
exit 0
