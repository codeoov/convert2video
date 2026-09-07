# harness-inject-rules.ps1 -- PreToolUse(Task/Agent) hook
# Bundles and injects role-specific rules into agent prompts before execution.
# Cursor: preToolUse matcher=Task / Claude Code: PreToolUse (Agent)
param()
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. "$PSScriptRoot\harness-lib.ps1"

$MARKER = "=== HARNESS RULE CONTEXT (auto-injected) ==="

try { $rawInput = [Console]::In.ReadToEnd() } catch { exit 0 }
if ([string]::IsNullOrWhiteSpace($rawInput)) { exit 0 }

try { $data = $rawInput | ConvertFrom-Json } catch { exit 0 }

# Runtime detection: Claude Code PreToolUse vs Cursor preToolUse
$isClaude = ($data.PSObject.Properties.Name -contains "hook_event_name") -and ($data.hook_event_name -eq "PreToolUse")

$toolName = [string]$data.tool_name
if ($toolName -notmatch "^(Task|Agent)$") { exit 0 }

# Only process when Harness is armed
$state = Get-HarnessState
if ($null -eq $state -or -not $state.armed) { exit 0 }

# Extract prompt and description
$prompt      = [string]$data.tool_input.prompt
$description = [string]$data.tool_input.description

# Skip if already injected (duplicate prevention)
if ($prompt -match [regex]::Escape($MARKER)) { exit 0 }

$blob = ($description + " " + $prompt).ToLower()

# Role detection (priority order)
$role = $null
if     ($blob -match "plan_evaluator|plan evaluator")                              { $role = "plan_evaluator" }
elseif ($blob -match "planner")                                                    { $role = "planner" }
elseif ($blob -match "evaluator|verify.*round|round.*verify|round.*[0-9].*check") { $role = "evaluator" }
elseif ($blob -match "generator|implement|round.*[0-9].*impl")                    { $role = "generator" }

if ($null -eq $role) { exit 0 }

# Extract round and type
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

# Output based on runtime
if ($isClaude) {
    $out = [PSCustomObject]@{
        hookSpecificOutput = [PSCustomObject]@{
            hookEventName      = "PreToolUse"
            permissionDecision = "allow"
            updatedInput       = [PSCustomObject]@{ prompt = $injectedPrompt }
            additionalContext  = "Harness: injected $role rules ($kChars" + "k chars)"
        }
    }
} else {
    $out = [PSCustomObject]@{
        permission    = "allow"
        agent_message = "Harness: injected $role rules ($kChars" + "k chars). If context missing, Read .cursor/harness/cache/latest-$role.md first."
        updated_input = [PSCustomObject]@{ prompt = $injectedPrompt }
    }
}

Write-Output ($out | ConvertTo-Json -Depth 10 -Compress)
