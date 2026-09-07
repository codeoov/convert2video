# Cursor / Claude Code / Codex Stop hook — forbidden-import, logging, terminology checks (Windows)
# Canonical path: .cursor/hooks/run-stop-checks.ps1 (shared by .cursor/hooks.json and .codex/hooks.json)
# Cursor (plain payload, no hook_event_name): exit 0 passed / exit 2 violation.
# Claude Code / Codex (hookSpecificOutput schema): additionally emits {decision:"block",reason} + exit 0 on violation.

$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\harness-lib.ps1"

$rawInput = ""
try { $rawInput = [Console]::In.ReadToEnd() } catch { $rawInput = "" }
$hookData = $null
if (-not [string]::IsNullOrWhiteSpace($rawInput)) {
    try { $hookData = $rawInput | ConvertFrom-Json } catch { $hookData = $null }
}
$useHookSpecificOutput = Test-HookUsesHookSpecificOutput $hookData

$repoRoot = if ($env:CLAUDE_PROJECT_DIR) {
    $env:CLAUDE_PROJECT_DIR
} else {
    (Get-Item $PSScriptRoot).Parent.Parent.FullName
}
Set-Location -LiteralPath $repoRoot

$scripts = @(
    'scripts\check-forbidden-imports.ps1',
    'scripts\check-logging-forbidden.ps1',
    'scripts\check-terminology-forbidden.ps1',
    'scripts\check-callbackflow-close-handlers.ps1'
)

$failedScripts = @()
foreach ($script in $scripts) {
    $fullPath = Join-Path $repoRoot $script
    if (-not (Test-Path -LiteralPath $fullPath)) {
        Write-Error "[FAIL] $script - required check file not found"
        $failedScripts += $script
        continue
    }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fullPath
    if ($LASTEXITCODE -ne 0) {
        Write-Error "[FAIL] $script violation detected - please fix and retry"
        $failedScripts += $script
    }
}

if ($failedScripts.Count -eq 0) { exit 0 }

if ($useHookSpecificOutput) {
    $reason = "Harness: forbidden-import/logging/terminology check failed (" + ($failedScripts -join ', ') + "). Fix and retry before stopping."
    $out = [PSCustomObject]@{
        decision = "block"
        reason   = $reason
    }
    [Console]::Out.WriteLine(($out | ConvertTo-Json -Compress))
    exit 0
}

exit 2
