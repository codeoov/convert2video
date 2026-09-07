# harness-block-main-write.ps1 — preToolUse hook
# Blocks main-agent Write/Edit/StrReplace on project code while Harness is armed.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. "$PSScriptRoot\harness-lib.ps1"

$allowJson = '{"permission":"allow"}'

try { $rawInput = [Console]::In.ReadToEnd() } catch { Write-Output $allowJson; exit 0 }
if ([string]::IsNullOrWhiteSpace($rawInput))   { Write-Output $allowJson; exit 0 }

try { $data = $rawInput | ConvertFrom-Json } catch { Write-Output $allowJson; exit 0 }

# Only intercept write-type tools
$toolName = [string]$data.tool_name
if ($toolName -notin @("Write", "Edit", "StrReplace")) { Write-Output $allowJson; exit 0 }

# Extract file path
$ti       = $data.tool_input
$filePath = if ($ti.file_path) { [string]$ti.file_path } elseif ($ti.path) { [string]$ti.path } else { "" }
if ([string]::IsNullOrWhiteSpace($filePath)) { Write-Output $allowJson; exit 0 }

# Check harness state
$state = Get-HarnessState
if ($null -eq $state -or -not $state.armed) { Write-Output $allowJson; exit 0 }
if ($state.evaluator_pass)                  { Write-Output $allowJson; exit 0 }
if ($state.generator_active)                { Write-Output $allowJson; exit 0 }

# Allow safe directories
$norm = $filePath.Replace("\", "/")
$safePrefixes = @(".cursor/", ".claude/", "docs/", "scripts/")
foreach ($p in $safePrefixes) {
    if ($norm.StartsWith($p)) { Write-Output $allowJson; exit 0 }
}
# Allow root-level files (no directory separator = no slash)
if ($norm -notmatch "/") { Write-Output $allowJson; exit 0 }

# Deny everything else while armed
$deny = [PSCustomObject]@{
    permission    = "deny"
    user_message  = "Harness 위반: 스프린트 활성화 중 메인 에이전트는 앱 코드를 직접 수정할 수 없습니다. Task(generator)로 구현하고 Task(evaluator)로 검증하세요."
    agent_message = "sprint-harness-gate.mdc §2 — 메인 Write/Edit 금지. Task(generator) 사용."
}
Write-Output ($deny | ConvertTo-Json -Compress)
exit 2
