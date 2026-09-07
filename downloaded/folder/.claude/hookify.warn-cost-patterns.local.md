---
name: warn-cost-patterns
enabled: true
event: bash
pattern: gradlew(\.bat)?\s+--rerun-tasks|gradlew(\.bat)?\s+clean\b|find\s+\.\s+-name\s+"\*\.kt"|Get-ChildItem\s+-Recurse.*\.kt
action: warn
---

💸 **비용 낭비 가능성 있는 명령 감지**

다음 중 하나에 해당합니다:

| 패턴 | 더 나은 대안 |
|------|------------|
| `gradlew clean` | 꼭 필요할 때만, 확인 후 실행 |
| `gradlew --rerun-tasks` | 캐시를 무효화 — 필수 시만 사용 |
| `find . -name "*.kt"` | Claude의 `Glob` 도구 사용 (토큰 절약) |
| `Get-ChildItem -Recurse *.kt` | Claude의 `Glob` 도구 사용 (토큰 절약) |

Bash 셸 명령으로 파일을 검색하면 결과가 모두 컨텍스트에 올라와 토큰을 낭비합니다.
