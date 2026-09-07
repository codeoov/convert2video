# harness-stop-gate.ps1 — stop hook gate
# Prevents agent stop when Harness is armed but evaluator has not PASSed.
. "$PSScriptRoot\harness-lib.ps1"

$rawInput = ""
try { $rawInput = [Console]::In.ReadToEnd() } catch { $rawInput = "{}" }
if ([string]::IsNullOrWhiteSpace($rawInput)) { $rawInput = "{}" }

$data = $null
try { $data = $rawInput | ConvertFrom-Json } catch { $data = $null }

$state = Get-HarnessState
if ($null -eq $state -or -not $state.armed) { exit 0 }
if ($state.evaluator_pass) { exit 0 }

$rounds = [int]$state.evaluator_round
$reason = @(
    "Harness Gate incomplete - agent stop blocked.",
    "evaluator PASS: no (Round $rounds).",
    "Required: Task(planner) -> Task(plan_evaluator) -> Task(generator) <-> Task(evaluator).",
    "evaluator Round 1-2 FAIL -> Round 3+ PASS.",
    "Run Task(evaluator) now, or confirm harness skip with the user."
) -join " "

$eventName = Get-HookEventNameFromInput $data
if ($eventName -eq "Stop") {
    $out = [PSCustomObject]@{
        decision = "block"
        reason   = $reason
    }
    [Console]::Out.WriteLine(($out | ConvertTo-Json -Compress))
    exit 0
}

Write-Host ""
Write-Host "Harness Gate incomplete - agent stop blocked"
Write-Host ""
Write-Host "  evaluator PASS: no (Round $rounds)"
Write-Host "  Do not finish the sprint with main-agent direct coding."
Write-Host ""
Write-Host "Required order:"
Write-Host "  1. Harness Gate table (first reply)"
Write-Host "  2. Task(planner) -> Task(plan_evaluator) -> Task(generator) <-> Task(evaluator)"
Write-Host "  3. evaluator Round 1-2 FAIL -> Round 3+ PASS"
Write-Host ""
Write-Host "Run Task(evaluator) now, or confirm harness skip with the user."
Write-Host ""
exit 2
