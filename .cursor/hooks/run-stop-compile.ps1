# Cursor / Claude Code / Codex Stop hook — :app:compileDebugKotlin (Windows)
# Canonical path: .cursor/hooks/run-stop-compile.ps1 (shared by .cursor/hooks.json and .codex/hooks.json)
# stdin: hook JSON (parsed to detect schema; Cursor payload has no hook_event_name).
# Cursor: stdout '{}' + exit=gradle code.
# Claude Code / Codex (hookSpecificOutput schema): on failure also emits {decision:"block",reason} + exit 0
# so the host feeds the reason back and retries instead of relying on gradle's raw (non-"2") exit code.

$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\harness-lib.ps1"

$rawInput = ""
try {
    $stdin = New-Object System.IO.StreamReader([System.Console]::OpenStandardInput(), [System.Text.Encoding]::UTF8)
    $rawInput = $stdin.ReadToEnd()
    $stdin.Dispose()
} catch {
    # stdin may be empty or unavailable; hook should still run
}
$hookData = $null
if (-not [string]::IsNullOrWhiteSpace($rawInput)) {
    try { $hookData = $rawInput | ConvertFrom-Json } catch { $hookData = $null }
}
$useHookSpecificOutput = Test-HookUsesHookSpecificOutput $hookData

$repoRoot = (Get-Item $PSScriptRoot).Parent.Parent.FullName
Set-Location -LiteralPath $repoRoot

$gradleBat = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradleBat)) {
    Write-Output '{}'
    exit 0
}

# Gradle 출력은 stdout에 섞이면 훅 JSON 파싱이 깨질 수 있어 파일로만 받는다.
$log = Join-Path ([System.IO.Path]::GetTempPath()) ("happy-cursor-hook-compile-{0}.log" -f [guid]::NewGuid().ToString('N'))
cmd /c "`"$gradleBat`" :app:compileDebugKotlin --no-daemon > `"$log`" 2>&1"
$code = $LASTEXITCODE
$tail = @()
if ($code -ne 0) {
    Write-Host "Gradle compileDebugKotlin failed (exit $code). Tail:" -ForegroundColor Red
    $tail = @(Get-Content -LiteralPath $log -Tail 80 -ErrorAction SilentlyContinue)
    $tail | ForEach-Object { Write-Host $_ }
}
Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue

if ($code -ne 0 -and $useHookSpecificOutput) {
    $reasonTail = ($tail | Select-Object -Last 40) -join "`n"
    $reason = "Harness: :app:compileDebugKotlin failed (exit $code). Fix compile errors before stopping.`n$reasonTail"
    $out = [PSCustomObject]@{
        decision = "block"
        reason   = $reason
    }
    [Console]::Out.WriteLine(($out | ConvertTo-Json -Compress))
    exit 0
}

Write-Output '{}'
exit $code
