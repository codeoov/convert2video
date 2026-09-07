# harness-inject-rules.ps1 -- PreToolUse(Task/Agent) hook
# Bundles and injects role-specific rules into agent prompts before execution.
# Cursor: preToolUse matcher=Task / Claude Code: PreToolUse (Agent)
# ASCII-only source (UTF-8 no-BOM safe on Windows PS 5.1).
param()
$script:hookInputData = $null
trap {
    Write-HookPreToolUseAllow $script:hookInputData
}
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8NoBom
[Console]::InputEncoding  = $utf8NoBom
. "$PSScriptRoot\harness-lib.ps1"

$MARKER = "=== HARNESS RULE CONTEXT (auto-injected) ==="

$reader = $null
try {
    # leaveOpen=$true: do not close Console stdin (Dispose closing stdin trips trap -> false allow)
    $reader = [System.IO.StreamReader]::new(
        [Console]::OpenStandardInput(),
        $utf8NoBom,
        $false,
        1024,
        $true
    )
    $rawInput = $reader.ReadToEnd()
} catch {
    Write-HookPreToolUseAllow $script:hookInputData
} finally {
    if ($null -ne $reader) { $reader.Dispose() }
}
if ([string]::IsNullOrWhiteSpace($rawInput)) {
    Write-HookPreToolUseAllow $script:hookInputData
}

try { $data = $rawInput | ConvertFrom-Json } catch {
    Write-HookPreToolUseAllow $script:hookInputData
}
$script:hookInputData = $data

# Runtime detection: Codex/Claude PreToolUse (hookSpecificOutput) vs Cursor preToolUse
$useHookSpecificOutput = Test-HookUsesHookSpecificOutput $data

$toolName = [string]$data.tool_name
if (-not (Test-IsAgentSpawnToolName $toolName)) {
    Write-HookPreToolUseAllow $data
}

# Only process when Harness is armed
$state = Get-HarnessState
if ($null -eq $state -or -not $state.armed) {
    Write-HookPreToolUseAllow $data
}

# Extract prompt and description
$prompt      = [string]$data.tool_input.prompt
$description = [string]$data.tool_input.description

# Skip if already injected (duplicate prevention)
if ($prompt -match [regex]::Escape($MARKER)) {
    Write-HookPreToolUseAllow $data
}

# Role detection: exact match on subagent_type / agent_type only (no blob regex)
$role = $null
switch (Get-SubagentTypeFromHookInput $data) {
    "planner"        { $role = "planner" }
    "plan_evaluator" { $role = "plan_evaluator" }
    "generator"      { $role = "generator" }
    "evaluator"      { $role = "evaluator" }
}

if ($null -eq $role) {
    Write-HookPreToolUseAllow $data
}

# Extract round and type from description+prompt AFTER role is determined
$blob = ($description + " " + $prompt).ToLower()

$round = 0
if ($blob -match "round\s*(\d+)") { $round = [int]$Matches[1] }

$type = ""
if ($blob -match "\[type\s+([abcd])") { $type = $Matches[1].ToUpper() }

# Generate bundle
$repoRoot     = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$bundleScript = Join-Path $repoRoot ".cursor\hooks\harness-bundle-rules.ps1"

$psArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $bundleScript, "-Role", $role)
if ($type)  { $psArgs += @("-Type",  $type) }
if ($round) { $psArgs += @("-Round", "$round") }
if ($prompt.Length -gt 0) {
    $contractSnippet = $prompt.Substring(0, [Math]::Min(2000, $prompt.Length))
    $psArgs += @("-Contract", $contractSnippet)
}

try {
    $bundle = (& powershell.exe @psArgs 2>$null) -join "`n"
} catch {
    $bundle = $null
}

# Fallback if bundle generation failed
if ([string]::IsNullOrWhiteSpace($bundle)) {
    $bundle = "$MARKER`nFallback: Read '.cursor/harness/cache/latest-$role.md' as your FIRST action.`n=== END HARNESS RULE CONTEXT ==="
}

$injectedPrompt = $bundle.TrimEnd() + "`n`n" + $prompt
$kChars = [Math]::Round($bundle.Length / 1000, 1)

# Clone original tool_input, then replace prompt only (do not emit prompt-only object)
# Depth must match final ConvertTo-Json so nested fields are not truncated asymmetrically
$updatedInput = ($data.tool_input | ConvertTo-Json -Depth 20 -Compress) | ConvertFrom-Json
$updatedInput.prompt = $injectedPrompt

# Output based on runtime
if ($useHookSpecificOutput) {
    $out = [PSCustomObject]@{
        hookSpecificOutput = [PSCustomObject]@{
            hookEventName      = "PreToolUse"
            permissionDecision = "allow"
            updatedInput       = $updatedInput
            additionalContext  = "Harness: injected $role rules ($kChars" + "k chars)"
        }
    }
} else {
    $out = [PSCustomObject]@{
        permission    = "allow"
        agent_message = "Harness: injected $role rules ($kChars" + "k chars). If context missing, Read .cursor/harness/cache/latest-$role.md first."
        updated_input = $updatedInput
    }
}

# Success path: same UTF8 no-BOM stdout as early-exit allow paths
[Console]::Out.WriteLine(($out | ConvertTo-Json -Depth 20 -Compress))
