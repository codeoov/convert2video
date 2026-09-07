---
name: detect-error-cycle
enabled: true
event: stop
action: warn
conditions:
  - field: transcript
    operator: regex_match
    pattern: (Compilation failed|compileDebugKotlin FAILED|error:.*\.kt).*(Compilation failed|compileDebugKotlin FAILED|error:.*\.kt).*(Compilation failed|compileDebugKotlin FAILED|error:.*\.kt)
---

🔄 **컴파일 에러 반복 사이클 감지 (3회 이상)**

동일한 빌드 오류가 반복되고 있습니다. 현재 접근 방식이 효과적이지 않을 수 있습니다.

**권장 행동:**
1. 현재 편집 중인 파일을 모두 읽고 전체 컨텍스트를 재파악하세요.
2. 에러 메시지의 **근본 원인**을 분석하세요 (증상이 아닌 원인).
3. 필요하다면 사용자에게 현재 상황을 보고하고 방향을 물어보세요.
4. `do-not-repeat-kotlin.mdc`를 열어 이미 있는 유틸/헬퍼가 있는지 확인하세요.

반복 수정보다 **한 번의 정확한 수정**이 훨씬 저렴합니다.
