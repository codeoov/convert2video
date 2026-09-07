# harness-block-main-write.ps1 — preToolUse hook
# Blocks main-agent Write/Edit/StrReplace/apply_patch on project code while Harness is armed.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. "$PSScriptRoot\harness-lib.ps1"

try { $rawInput = [Console]::In.ReadToEnd() } catch { Write-HookPreToolUseAllow $null }
if ([string]::IsNullOrWhiteSpace($rawInput)) { Write-HookPreToolUseAllow $null }

try { $data = $rawInput | ConvertFrom-Json } catch { Write-HookPreToolUseAllow $null }

$toolName = [string]$data.tool_name
if (-not (Test-IsWriteBlockToolName $toolName)) { Write-HookPreToolUseAllow $data }

$filePath = Get-WriteToolPathFromHookInput $data
if ([string]::IsNullOrWhiteSpace($filePath)) { Write-HookPreToolUseAllow $data }

$state = Get-HarnessState
if ($null -eq $state -or -not $state.armed) { Write-HookPreToolUseAllow $data }
if ($state.evaluator_pass)                  { Write-HookPreToolUseAllow $data }
if ($state.generator_active)                { Write-HookPreToolUseAllow $data }

$norm = $filePath.Replace("\", "/")
$safePrefixes = @(".cursor/", ".claude/", ".codex/", ".agents/", "docs/", "scripts/")
foreach ($p in $safePrefixes) {
    if ($norm.StartsWith($p)) { Write-HookPreToolUseAllow $data }
}
if ($norm -notmatch "/") { Write-HookPreToolUseAllow $data }

$userMessage = "Harness 위반: 스프린트 활성화 중 메인 에이전트는 앱 코드를 직접 수정할 수 없습니다. Task(generator)로 구현하고 Task(evaluator)로 검증하세요."
$agentMessage = "sprint-harness-gate.mdc §2 — 메인 Write/Edit 금지. Task(generator) 사용."
Write-HookPreToolUseDeny $data $userMessage $agentMessage
