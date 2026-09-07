# harness-stop-gate.ps1 — stop hook gate
# Prevents agent stop when Harness is armed but evaluator has not PASSed.
. "$PSScriptRoot\harness-lib.ps1"

$state = Get-HarnessState
if ($null -eq $state -or -not $state.armed) { exit 0 }
if ($state.evaluator_pass) { exit 0 }

$rounds = [int]$state.evaluator_round

Write-Host ""
Write-Host "Harness Gate 미완료 - agent stop 차단"
Write-Host ""
Write-Host "  evaluator PASS: 아니오 (Round $rounds)"
Write-Host "  메인 에이전트 직접 코딩으로 스프린트를 끝내면 안 됩니다."
Write-Host ""
Write-Host "필수 순서:"
Write-Host "  1. Harness Gate 표 (첫 답)"
Write-Host "  2. Task(planner) -> Task(plan_evaluator) -> Task(generator) <-> Task(evaluator)"
Write-Host "  3. evaluator Round 1-2 FAIL -> Round 3+ PASS"
Write-Host ""
Write-Host "지금 Task(evaluator)를 돌리거나, 「하네스 생략」 확인 필요."
Write-Host ""
exit 2
