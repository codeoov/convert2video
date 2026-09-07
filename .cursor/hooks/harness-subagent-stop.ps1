# harness-subagent-stop.ps1 - subagentStop hook
# Tracks evaluator rounds; sets evaluator_pass on Round 3+ explicit PASS; injects followup on FAIL.
# ASCII-only source (UTF-8 no-BOM safe on Windows PS 5.1). Korean via \u / [char].
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

# plan_evaluator: do not touch evaluator_round / evaluator_pass (start clears generator_active)
if ($subagentType -eq "plan_evaluator") {
    exit 0
}

if ($subagentType -ne "evaluator") { exit 0 }

$state.evaluator_round  = [int]$state.evaluator_round + 1
$state.generator_active = $false

$full = Get-SubagentResultText $data

# Explicit verdict only - bare \bPASS\b is NOT enough (avoids false PASS from prose)
# \uD310\uC815 = Korean panjeong (same patterns as before; encoding-safe)
$hasExplicitPass = ($full -match '(?im)^\s*\*\*?\uD310\uC815:\s*PASS\b') `
    -or ($full -match '(?im)\bVERDICT:\s*PASS\b') `
    -or ($full -match '(?im)\b\uD310\uC815:\s*PASS\b')

# Explicit FAIL lines only - bare \bFAIL\b in prose (e.g. "Round1 FAIL fixed") must NOT block PASS
$hasFailPollution = ($full -match '(?im)^\s*\*\*?\uD310\uC815:\s*FAIL\b') `
    -or ($full -match '(?im)\b\uD310\uC815:\s*FAIL\b') `
    -or ($full -match '(?im)\bVERDICT:\s*FAIL\b')

if ($hasExplicitPass -and -not $hasFailPollution -and [int]$state.evaluator_round -ge 3) {
    $state.evaluator_pass = $true
    Save-HarnessState $state
    exit 0
}

Save-HarnessState $state

$round = [int]$state.evaluator_round
# Round1 golden followup via [char] (ASCII source / WinPS no-BOM safe).
# Must include Hangul phrase: miwanryo + ttoneun + PASS + anim (not truncated miwanryo+anim).
$miwan = -join [char[]](0xBBF8, 0xC644, 0xB8CC)               # miwanryo
$ttoneun = -join [char[]](0xB610, 0xB294)                      # ttoneun
$anim = -join [char[]](0xC544, 0xB2D8)                         # anim
$sujeong = -join [char[]](0xC218, 0xC815)                      # sujeong
$hu = [string][char]0xD6C4                                    # hu
$jaegeom = -join [char[]](0xC7AC, 0xAC80, 0xC99D)              # jaegeomjeung
$neun = [string][char]0xB294                                  # neun (PASS + neun)
$eseo = -join [char[]](0xC5D0, 0xC11C)                         # eseo
$man = [string][char]0xB9CC                                   # man
$jeonje = -join [char[]](0xC804, 0xC81C)                       # jeonje
$followup = "Harness: evaluator Round $round $miwan $ttoneun PASS $anim. generator $sujeong $hu evaluator $jaegeom. PASS$neun Round 3+ $eseo$man. Round 1-2 FAIL $jeonje."
$eventName = Get-HookEventNameFromInput $data
if ($eventName -eq "SubagentStop") {
    $msg = [PSCustomObject]@{
        decision = "block"
        reason   = $followup
    }
} else {
    $msg = [PSCustomObject]@{
        followup_message = $followup
    }
}
[Console]::Out.WriteLine(($msg | ConvertTo-Json -Compress))
exit 0
