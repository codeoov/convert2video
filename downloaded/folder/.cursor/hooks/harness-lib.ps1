# harness-lib.ps1 — Shared helpers for Harness enforcement hooks
# dot-source with: . "$PSScriptRoot\harness-lib.ps1"
# All harness hook scripts live in the same directory (.cursor\hooks\),
# so $PSScriptRoot is always .cursor\hooks\ regardless of which script calls these functions.

function Get-HarnessStatePath {
    Join-Path (Split-Path -Parent $PSScriptRoot) "harness-state.json"
}

function Get-HarnessState {
    $p = Get-HarnessStatePath
    if (-not (Test-Path $p)) { return $null }
    try { Get-Content $p -Raw -Encoding UTF8 | ConvertFrom-Json } catch { return $null }
}

function Save-HarnessState($state) {
    $state | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Get-HarnessStatePath)
}

function Test-SprintPrompt([string]$text) {
    $text -match "스프린트|루프\s*시작|하네스|sprint\s*run|/sprint-run|스프린트\s*돌|플랜.*실행|기획.*실행"
}

function Test-ExemptPrompt([string]$text) {
    $text -match "하네스\s*생략|직접\s*고쳐|빠르게만|질문만|원인\s*분석|왜\s*안"
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
