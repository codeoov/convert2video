# harness-subagent-start.ps1 - subagentStart hook
# Marks generator subagent as active (unlocks Write); clears on evaluator start.
# stdout: none by design (encoding N/A for stdout smoke - state-only side effects).
# ASCII-only source (UTF-8 no-BOM safe on Windows PS 5.1).
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8NoBom
[Console]::InputEncoding  = $utf8NoBom
. "$PSScriptRoot\harness-lib.ps1"

$state = Get-HarnessState
if ($null -eq $state -or -not $state.armed) { exit 0 }

$reader = $null
try {
    # leaveOpen=$true: do not close Console stdin on Dispose
    $reader = [System.IO.StreamReader]::new(
        [Console]::OpenStandardInput(),
        $utf8NoBom,
        $false,
        1024,
        $true
    )
    $rawInput = $reader.ReadToEnd()
    $data = $rawInput | ConvertFrom-Json
} catch { exit 0 }
finally {
    if ($null -ne $reader) { $reader.Dispose() }
}

$subagentType = Get-SubagentTypeFromHookInput $data

if ($subagentType -eq "generator") {
    $state.generator_active = $true
} elseif ($subagentType -eq "evaluator" -or $subagentType -eq "plan_evaluator") {
    $state.generator_active = $false
}

Save-HarnessState $state
exit 0
