# harness-subagent-start.ps1 — subagentStart hook
# Marks generator subagent as active (unlocks Write); clears on evaluator start.
. "$PSScriptRoot\harness-lib.ps1"

$state = Get-HarnessState
if ($null -eq $state -or -not $state.armed) { exit 0 }

try {
    $rawInput = [Console]::In.ReadToEnd()
    $data     = $rawInput | ConvertFrom-Json
} catch { exit 0 }

$blob = ([string]$data.subagent_type + " " + [string]$data.description + " " + [string]$data.prompt).ToLower()

if ($blob -match "generator|코드\s*구현|implement") {
    $state.generator_active = $true
} elseif ($blob -match "evaluator|이밸|검증|verify|채점") {
    $state.generator_active = $false
}

Save-HarnessState $state
exit 0
