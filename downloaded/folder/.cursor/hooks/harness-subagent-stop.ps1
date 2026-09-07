# harness-subagent-stop.ps1 — subagentStop hook
# Tracks evaluator rounds; sets evaluator_pass on Round 3+ PASS; injects followup on FAIL.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. "$PSScriptRoot\harness-lib.ps1"

$state = Get-HarnessState
if ($null -eq $state -or -not $state.armed) { exit 0 }

try {
    $rawInput = [Console]::In.ReadToEnd()
    $data     = $rawInput | ConvertFrom-Json
} catch { exit 0 }

$blob = ([string]$data.subagent_type + " " + [string]$data.description + " " + `
         [string]$data.result + " " + [string]$data.output).ToLower()

if ($blob -notmatch "evaluator|이밸|검증|verify|채점") { exit 0 }

$state.evaluator_round  = [int]$state.evaluator_round + 1
$state.generator_active = $false

$full = [string]$data.result + [string]$data.output
if ($full -match '\bPASS\b' -and [int]$state.evaluator_round -ge 3) {
    $state.evaluator_pass = $true
    Save-HarnessState $state
    exit 0
}

Save-HarnessState $state

$round = [int]$state.evaluator_round
$msg = [PSCustomObject]@{
    followup_message = "Harness: evaluator Round $round 미완료 또는 PASS 아님. generator 수정 후 evaluator 재검증. PASS는 Round 3+ 에서만. Round 1-2 FAIL 전제."
}
Write-Output ($msg | ConvertTo-Json -Compress)
exit 0
