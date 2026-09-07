---
name: "source-command-kotlin-verify"
description: "Migrated source command `kotlin-verify`"
---

# source-command-kotlin-verify

Use this skill when the user asks to run the migrated source command `kotlin-verify`.

## Command Template

# Kotlin 검증 루틴 (convert2video)

이 커맨드를 실행한 뒤, 아래를 **순서대로** 수행한다.

1. **컴파일**: 저장소 루트에서 `.\gradlew.bat :app:compileDebugKotlin` 실행. 실패하면 원인을 고치고, 성공할 때까지 필요 시 반복한다. (참고: `.cursor/hooks.json`의 `stop` 훅이 이미 컴파일을 돌렸을 수 있다. Codex Hooks 실행 로그에 실패가 없고 방금 턴 직후라면 1번은 생략해도 된다.)
2. **checks**: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".cursor\hooks\run-stop-checks.ps1"`
3. **린트(선택)**: 팀에서 쓰는 경우 `.\gradlew.bat :app:lintDebug` 실행하고, 결과를 짧게 요약한다(치명적 이슈 위주).
4. **셀프체크**: (a) 용어 background/conversion/video (b) Repository·Worker·ConversionUiState 단일 소스 (c) `stringResource` 위치 규칙 (d) 새 파일/삭제는 사용자 허락을 받았는지 (e) Retrofit/Hilt/happy_v12 용어 이식 없음.
5. **답변**: `CLAUDE.md` § Core Conventions의 작업 완료 답변 형식(한 일·수정 파일·확인 방법·다음 스텝·남은 리스크)으로 한국어로 정리한다.

코드 변경이 없었어도 1·2번은 실행해 현재 트리가 깨지지 않았는지 확인해도 된다.
