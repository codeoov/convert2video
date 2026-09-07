# One-off smoke: Codex hook JSON branches (Phase 4)
$ErrorActionPreference = 'Stop'
$repoRoot = (Get-Item $PSScriptRoot).Parent.FullName
Set-Location -LiteralPath $repoRoot

$statePath = Join-Path $repoRoot '.cursor\harness-state.json'
$state = [PSCustomObject]@{
    armed            = $true
    armed_at         = '2026-08-20T00:00:00Z'
    prompt_snippet   = 'codex-hook-smoke'
    generator_active = $false
    evaluator_round  = 0
    evaluator_pass   = $false
}
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Depth 5), $utf8NoBom)

function Invoke-HookStdinLocal {
    param(
        [Parameter(Mandatory)][string]$ScriptRel,
        [Parameter(Mandatory)][string]$Json
    )
    $scriptPath = Join-Path $repoRoot $ScriptRel
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'powershell.exe'
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`""
    $psi.WorkingDirectory = $repoRoot
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $bytes = $utf8NoBom.GetBytes($Json)
    $p.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $p.StandardInput.Close()
    $stdout = $p.StandardOutput.ReadToEnd()
    $stderr = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    return [PSCustomObject]@{
        Exit   = $p.ExitCode
        Out    = $stdout.Trim()
        Err    = $stderr.Trim()
    }
}

$failed = $false

$injectJson = '{"hook_event_name":"PreToolUse","tool_name":"spawn_agent","tool_input":{"agent_type":"generator","description":"generator: Round 2 impl","prompt":"Implement contract fixes."}}'
$inject = Invoke-HookStdinLocal -ScriptRel '.cursor\hooks\harness-inject-rules.ps1' -Json $injectJson
if ($inject.Exit -ne 0 -or $inject.Out -notmatch 'hookSpecificOutput' -or $inject.Out -notmatch 'updatedInput') {
    Write-Host '[FAIL] Codex spawn_agent inject'
    Write-Host $inject.Out
    $failed = $true
} else {
    Write-Host '[OK] Codex spawn_agent inject -> hookSpecificOutput.updatedInput'
}

$blockJson = '{"hook_event_name":"PreToolUse","tool_name":"apply_patch","tool_input":{"command":"*** Update File: app/src/main/Foo.kt\n"}}'
$block = Invoke-HookStdinLocal -ScriptRel '.cursor\hooks\harness-block-main-write.ps1' -Json $blockJson
if ($block.Exit -ne 2 -or $block.Out -notmatch 'permissionDecision"\s*:\s*"deny"') {
    Write-Host '[FAIL] Codex apply_patch deny'
    Write-Host $block.Out
    $failed = $true
} else {
    Write-Host '[OK] Codex apply_patch -> permissionDecision deny'
}

$safeJson = '{"hook_event_name":"PreToolUse","tool_name":"apply_patch","tool_input":{"command":"*** Update File: .codex/config.toml\n"}}'
$safe = Invoke-HookStdinLocal -ScriptRel '.cursor\hooks\harness-block-main-write.ps1' -Json $safeJson
if ($safe.Exit -ne 0 -or -not [string]::IsNullOrWhiteSpace($safe.Out)) {
    Write-Host '[FAIL] Codex apply_patch safe prefix should allow silently'
    Write-Host "exit=$($safe.Exit) out=$($safe.Out)"
    $failed = $true
} else {
    Write-Host '[OK] Codex apply_patch .codex/ prefix -> silent allow'
}

$stopJson = '{"hook_event_name":"SubagentStop","agent_type":"evaluator","last_assistant_message":"**판정: FAIL**"}'
$stop = Invoke-HookStdinLocal -ScriptRel '.cursor\hooks\harness-subagent-stop.ps1' -Json $stopJson
if ($stop.Exit -ne 0 -or $stop.Out -notmatch '"decision"\s*:\s*"block"') {
    Write-Host '[FAIL] Codex SubagentStop continuation'
    Write-Host $stop.Out
    $failed = $true
} else {
    Write-Host '[OK] Codex SubagentStop -> decision block'
}

$gateJson = '{"hook_event_name":"Stop","stop_hook_active":false}'
$gate = Invoke-HookStdinLocal -ScriptRel '.cursor\hooks\harness-stop-gate.ps1' -Json $gateJson
if ($gate.Exit -ne 0 -or $gate.Out -notmatch '"decision"\s*:\s*"block"') {
    Write-Host '[FAIL] Codex Stop gate'
    Write-Host $gate.Out
    $failed = $true
} else {
    Write-Host '[OK] Codex Stop gate -> decision block'
}

Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue

if ($failed) { exit 1 }
Write-Host '[OK] verify-codex-hook-smoke.ps1'
exit 0
