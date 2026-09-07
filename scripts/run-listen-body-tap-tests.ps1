# Listen body-tap / no-convert instrumented tests (Round 3 evidence script).
# Usage: powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\run-listen-body-tap-tests.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

Write-Host "=== adb devices ==="
$adbOut = & adb devices 2>&1 | Out-String
Write-Host $adbOut

$connected = @(
    ($adbOut -split "`n") |
        Where-Object { $_ -match "^\S+\s+device$" }
).Count

if ($connected -eq 0) {
    Write-Host "[SKIP] No connected Android device/emulator. Start one and re-run."
    Write-Host "  Example: emulator -avd <name>   OR   connect physical device with USB debugging"
    exit 2
}

$testClass = @(
    "com.example.convert2video.ui.screens.recordings_list.RecordingsListScreenTest",
    "com.example.convert2video.ui.shared.RecordingActiveUiAndroidTest#success_listenBodyTap_staysOnHomeNoConvert_integration",
    "com.example.convert2video.ui.shared.RecordingActiveUiAndroidTest#success_dualListenEntryConvert_preservesSharedViewModelState_integration"
) -join ","

Write-Host "=== connectedDebugAndroidTest (Listen body-tap subset) ==="
& .\gradlew.bat ":app:connectedDebugAndroidTest" `
    "-Pandroid.testInstrumentationRunnerArguments.class=$testClass" `
    --no-daemon

exit $LASTEXITCODE
