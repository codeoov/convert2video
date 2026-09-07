# Forbid previous-app domain terminology leaking into convert2video sources
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

# Word-boundary-ish checks; androidx.activity is allowlisted via negative lookbehind-style filters below.
$forbiddenTerms = @(
    @{ Name = 'gathering';   Pattern = '(?i)\bgathering\b' },
    @{ Name = 'booking';     Pattern = '(?i)\bbooking\b' },
    @{ Name = 'meetup';      Pattern = '(?i)\bmeetup\b' },
    @{ Name = 'reservation'; Pattern = '(?i)\breservation\b' },
    @{ Name = 'my_info';     Pattern = '(?i)\bmy_info\b' },
    @{ Name = 'my_page';     Pattern = '(?i)\bmy_page\b' },
    @{ Name = 'happy_v12';   Pattern = '(?i)\bhappy_v12\b' }
)

$violations = @()
foreach ($root in $scanRoots) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    $files = Get-ChildItem -Path $root -Recurse -Include *.kt, *.kts, *.xml -File -ErrorAction SilentlyContinue
    foreach ($file in $files) {
        $lines = Get-Content -LiteralPath $file.FullName -ErrorAction SilentlyContinue
        $lineNo = 0
        foreach ($line in $lines) {
            $lineNo++
            # Allow framework Activity imports / class names
            $normalized = $line -replace 'androidx\.activity', '' -replace 'ComponentActivity', '' -replace 'ActivityResult', ''
            foreach ($term in $forbiddenTerms) {
                if ($normalized -match $term.Pattern) {
                    $rel = $file.FullName.Substring($repoRoot.Length).TrimStart('\', '/')
                    $violations += "${rel}:${lineNo}: [$($term.Name)] $line"
                }
            }
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host '[FAIL] check-terminology-forbidden.ps1'
    $violations | ForEach-Object { Write-Host "  $_" }
    exit 2
}

Write-Host '[OK] check-terminology-forbidden.ps1'
exit 0
