# Kotlin 검증 루틴 (happy_v12)

이 커맨드를 실행한 뒤, 아래를 **순서대로** 수행한다.

1. **컴파일**: 저장소 루트에서 `./gradlew :app:compileDebugKotlin` 실행. 실패하면 원인을 고치고, 성공할 때까지 필요 시 반복한다. (참고: `.cursor/hooks.json`의 `stop` 훅이 이미 컴파일을 돌렸을 수 있다. Cursor Hooks 실행 로그에 실패가 없고 방금 턴 직후라면 1번은 생략해도 된다.)
2. **린트(선택)**: 팀에서 쓰는 경우 `./gradlew :app:lintDebug` 실행하고, 결과를 짧게 요약한다(치명적 이슈 위주).
3. **셀프체크**: (a) 용어 gathering/booking/session 등 (b) `Route` 하드코딩 없음·단일 소스 (c) `stringResource` 위치 규칙 (d) 새 파일/삭제는 사용자 허락을 받았는지.
4. **답변**: `happy-core-kotlin.mdc` §3의 작업 완료 답변 형식(한 일·수정 파일·확인 방법·다음 스텝·남은 리스크)으로 한국어로 정리한다.

코드 변경이 없었어도 1번은 실행해 현재 트리가 깨지지 않았는지 확인해도 된다.
