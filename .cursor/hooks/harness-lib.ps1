# harness-lib.ps1 - Shared helpers for Harness enforcement hooks
# dot-source with: . "$PSScriptRoot\harness-lib.ps1"
# All harness hook scripts live in the same directory (.cursor\hooks\),
# so $PSScriptRoot is always .cursor\hooks\ regardless of which script calls these functions.
# NOTE: ASCII-only source (UTF-8 no-BOM safe on Windows PS 5.1). Korean via \u escapes.

function Get-HarnessStatePath {
    Join-Path (Split-Path -Parent $PSScriptRoot) "harness-state.json"
}

function Get-HarnessState {
    $p = Get-HarnessStatePath
    if (-not (Test-Path $p)) { return $null }
    try {
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        return ([System.IO.File]::ReadAllText($p, $utf8NoBom) | ConvertFrom-Json)
    } catch { return $null }
}

function Save-HarnessState($state) {
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    $json = $state | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText((Get-HarnessStatePath), $json, $utf8NoBom)
}

function Test-SprintPrompt([string]$text) {
    # Golden fixed: \u escapes == Round1 Hangul literals (see verify (g) smoke).
    $text -match "\uC2A4\uD504\uB9B0\uD2B8|\uB8E8\uD504\s*\uC2DC\uC791|\uD558\uB124\uC2A4|sprint\s*run|/sprint-run|\uC2A4\uD504\uB9B0\uD2B8\s*\uB3CC|\uD50C\uB79C.*\uC2E4\uD589|\uAE30\uD68D.*\uC2E4\uD589"
}

function Test-ExemptPrompt([string]$text) {
    # Golden fixed: \u escapes == Round1 Hangul literals (see verify (g) smoke).
    $text -match "\uD558\uB124\uC2A4\s*\uC0DD\uB7B5|\uC9C1\uC811\s*\uACE0\uCCD0|\uBE60\uB974\uAC8C\uB9CC|\uC9C8\uBB38\uB9CC|\uC6D0\uC778\s*\uBD84\uC11D|\uC65C\s*\uC548"
}

function Invoke-HarnessDisarm {
    $p = Get-HarnessStatePath
    if (Test-Path $p) { Remove-Item $p -Force }
}

function Invoke-HarnessArm([string]$snippet) {
    $state = [PSCustomObject]@{
        armed            = $true
        armed_at         = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")
        prompt_snippet   = if ($snippet.Length -gt 200) { $snippet.Substring(0, 200) } else { $snippet }
        generator_active = $false
        evaluator_round  = 0
        evaluator_pass   = $false
    }
    Save-HarnessState $state
}

function Get-HookEventNameFromInput($data) {
    if ($null -eq $data) { return $null }
    if ($data.PSObject.Properties.Name -contains "hook_event_name") {
        return [string]$data.hook_event_name
    }
    return $null
}

function Test-HookUsesHookSpecificOutput($data) {
    return ($null -ne (Get-HookEventNameFromInput $data))
}

function Get-SubagentTypeFromHookInput($data) {
    if ($null -eq $data) { return "" }
    $ti = $data.tool_input
    if ($null -ne $ti) {
        if ($ti.PSObject.Properties.Name -contains "subagent_type") {
            $v = [string]$ti.subagent_type
            if (-not [string]::IsNullOrWhiteSpace($v)) { return $v }
        }
        if ($ti.PSObject.Properties.Name -contains "agent_type") {
            $v = [string]$ti.agent_type
            if (-not [string]::IsNullOrWhiteSpace($v)) { return $v }
        }
    }
    if ($data.PSObject.Properties.Name -contains "subagent_type") {
        $v = [string]$data.subagent_type
        if (-not [string]::IsNullOrWhiteSpace($v)) { return $v }
    }
    if ($data.PSObject.Properties.Name -contains "agent_type") {
        return [string]$data.agent_type
    }
    return ""
}

function Get-SubagentResultText($data) {
    if ($null -eq $data) { return "" }
    $parts = @()
    if ($data.PSObject.Properties.Name -contains "result") { $parts += [string]$data.result }
    if ($data.PSObject.Properties.Name -contains "output") { $parts += [string]$data.output }
    if ($data.PSObject.Properties.Name -contains "last_assistant_message") {
        $parts += [string]$data.last_assistant_message
    }
    return ($parts -join "`n")
}

function Write-HookPreToolUseAllow($data) {
    if (Test-HookUsesHookSpecificOutput $data) {
        exit 0
    }
    [Console]::Out.WriteLine('{"permission":"allow"}')
    exit 0
}

function Write-HookPreToolUseDeny($data, [string]$userMessage, [string]$agentMessage) {
    if (Test-HookUsesHookSpecificOutput $data) {
        $reason = if ([string]::IsNullOrWhiteSpace($userMessage)) { $agentMessage } else { $userMessage }
        $out = [PSCustomObject]@{
            hookSpecificOutput = [PSCustomObject]@{
                hookEventName              = "PreToolUse"
                permissionDecision         = "deny"
                permissionDecisionReason   = $reason
            }
        }
        [Console]::Out.WriteLine(($out | ConvertTo-Json -Depth 5 -Compress))
        exit 2
    }
    $deny = [PSCustomObject]@{
        permission    = "deny"
        user_message  = $userMessage
        agent_message = $agentMessage
    }
    [Console]::Out.WriteLine(($deny | ConvertTo-Json -Compress))
    exit 2
}

function Get-WriteToolPathFromHookInput($data) {
    if ($null -eq $data) { return "" }
    $ti = $data.tool_input
    if ($null -eq $ti) { return "" }
    if ($ti.PSObject.Properties.Name -contains "file_path") {
        $v = [string]$ti.file_path
        if (-not [string]::IsNullOrWhiteSpace($v)) { return $v }
    }
    if ($ti.PSObject.Properties.Name -contains "path") {
        $v = [string]$ti.path
        if (-not [string]::IsNullOrWhiteSpace($v)) { return $v }
    }
    $toolName = [string]$data.tool_name
    if ($toolName -eq "apply_patch" -and ($ti.PSObject.Properties.Name -contains "command")) {
        $cmd = [string]$ti.command
        if ($cmd -match '(?m)\*\*\*\s+(?:Update|Add|Delete)\s+File:\s*(\S+)') {
            return $Matches[1]
        }
    }
    return ""
}

function Test-IsAgentSpawnToolName([string]$toolName) {
    return ($toolName -match '^(Task|Agent|spawn_agent)$')
}

function Test-IsWriteBlockToolName([string]$toolName) {
    return ($toolName -in @("Write", "Edit", "StrReplace", "apply_patch"))
}
