# harness-arm-on-prompt.ps1 — beforeSubmitPrompt hook
# Arms Harness when user prompt contains sprint trigger keywords.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. "$PSScriptRoot\harness-lib.ps1"

try { $rawInput = [Console]::In.ReadToEnd() } catch { $rawInput = "{}" }
if ([string]::IsNullOrWhiteSpace($rawInput)) { exit 0 }

try {
    $data   = $rawInput | ConvertFrom-Json
    $prompt = [string]$data.prompt
} catch { exit 0 }

if ([string]::IsNullOrWhiteSpace($prompt)) { exit 0 }

if (Test-ExemptPrompt $prompt) {
    Invoke-HarnessDisarm
    exit 0
}

if (Test-SprintPrompt $prompt) {
    Invoke-HarnessArm $prompt

    # main 역할 규칙 번들 사전 생성 (메인 에이전트용 fallback)
    $repoRoot    = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
    $bundleScript = Join-Path $repoRoot ".cursor\hooks\harness-bundle-rules.ps1"
    if (Test-Path $bundleScript) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $bundleScript -Role main 2>$null | Out-Null
    }

    $context = "HARNESS ARMED - sprint-harness-gate.mdc`n첫 답변에 Harness Gate 표(5단계)를 넣으세요.`n메인 에이전트는 app/** domain/** data/** feature/** core/** Write/Edit 금지.`n구현: Task(generator). 검증: Task(evaluator) 최소 3라운드, Round 3+ PASS 전 완료 금지.`n예외: 사용자가 「하네스 생략」을 명시한 경우만.`n규칙 번들: .cursor/harness/cache/latest-<role>.md (각 Task에 자동 주입됨)"
    $eventName = Get-HookEventNameFromInput $data
    if ($eventName -eq "UserPromptSubmit") {
        $out = [PSCustomObject]@{
            systemMessage      = "Harness가 활성화됐습니다. 메인 에이전트는 Task(planner→plan_evaluator→generator↔evaluator)만 사용하세요."
            hookSpecificOutput = [PSCustomObject]@{
                hookEventName     = "UserPromptSubmit"
                additionalContext = $context
            }
        }
    } else {
        $out = [PSCustomObject]@{
            continue           = $true
            user_message       = "Harness가 활성화됐습니다. 메인 에이전트는 Task(planner→plan_evaluator→generator↔evaluator)만 사용하세요."
            additional_context = $context
        }
    }
    Write-Output ($out | ConvertTo-Json -Depth 3 -Compress)
    exit 0
}

exit 0
