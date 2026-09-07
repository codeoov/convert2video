# .claude/hooks/session-report.ps1
# Saves a token/cost snapshot of the current Claude Code session at every turn end.
# Uses ccusage (local JSONL analysis). Falls back to a plain notice if not installed.
# Always exits 0 so it never triggers the compile auto-fix loop.

param()
$ErrorActionPreference = 'SilentlyContinue'

$projectDir = $env:CLAUDE_PROJECT_DIR
if (-not $projectDir) {
    $projectDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
}

$reportDir = Join-Path $projectDir ".claude\session-reports"
if (-not (Test-Path $reportDir)) {
    New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
}

# current-session.txt: overwritten every turn -> always the latest snapshot
$snapshotFile = Join-Path $reportDir "current-session.txt"

# daily accumulation file (one file per calendar day)
$dailyFile = Join-Path $reportDir "daily_$(Get-Date -Format 'yyyyMMdd').txt"

$timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
$header = @(
    "=== Claude Code Session Report ===",
    "Updated : $timestamp",
    "Project : $projectDir",
    ""
)

# Try ccusage (global install first, then npx fallback)
$stats = $null
$source = $null

if (Get-Command ccusage -ErrorAction SilentlyContinue) {
    $stats = & ccusage --no-color 2>&1
    $source = "ccusage (global)"
} elseif (Get-Command npx -ErrorAction SilentlyContinue) {
    $stats = & npx --yes ccusage@latest --no-color 2>&1
    $source = "ccusage (npx)"
}

if ($stats -and $LASTEXITCODE -eq 0) {
    $body = $stats
    $sourceNote = "Source  : $source"
} else {
    $body = @(
        "Install ccusage for automatic token/cost tracking:",
        "  npm install -g ccusage",
        "",
        "Or use the /usage command inside Claude Code to view current session stats."
    )
    $sourceNote = "Source  : ccusage not installed"
}

$content = ($header + @($sourceNote, "") + $body) -join "`n"

# overwrite snapshot
$content | Out-File -Encoding utf8 $snapshotFile

# append to daily file with a separator line
@("", ("-" * 60), $content) | Out-File -Encoding utf8 -Append $dailyFile

# print a brief summary to the terminal (only when ccusage succeeded)
if ($stats -and $LASTEXITCODE -eq 0) {
    $costLine = $stats | Select-String 'cost|Cost|total|Total' | Select-Object -First 1
    if ($costLine) {
        Write-Host "[session-report] $($costLine.Line.Trim()) -> $snapshotFile"
    } else {
        Write-Host "[session-report] Snapshot saved -> $snapshotFile"
    }
}

exit 0
