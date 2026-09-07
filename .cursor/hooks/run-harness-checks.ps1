# run-harness-checks.ps1 — evaluator 전용 검증 래퍼 (compile 제외)
# evaluator가 compile 별도 실행 후 이 스크립트로 forbidden checks 통합 실행.
# exit 0: 통과 / exit 2: 위반 감지
$ErrorActionPreference = 'Continue'

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\run-stop-checks.ps1"
exit $LASTEXITCODE
