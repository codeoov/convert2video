# my-patterns 가이드 인덱스 — convert2video

> **이 앱의 단일 규칙은 `.cursor/rules/` 이다.**  
> Claude Code / Cursor 모두 `convert2video-core.mdc` + `rules-index-kotlin.mdc`를 먼저 연다.

## 이 앱에서 쓸 것

| 우선 | 경로 |
|------|------|
| 핵심 | `.cursor/rules/convert2video-core.mdc` |
| 구조 | `.cursor/rules/structure-kotlin.mdc` |
| 플로우 | `.cursor/rules/project-scope-kotlin.mdc` |
| 재발방지 | `.cursor/rules/do-not-repeat-kotlin.mdc` |
| 용어 | `.cursor/rules/terminology-glossary.mdc` |
| 스프린트 | `.claude/commands/sprint-run.md` + `.claude/agents/*` |

## 레거시 문서 (부분 보류)

| 파일 | 상태 |
|------|------|
| `project-structure.md` | happy_v12 구조 문서 그대로 남아있음 — **Part B에서 convert2video 구조로 전면 재작성 예정.** 그 전까지 참고하지 말 것 |
| `list-pagination-guide.md` | §8 빈 상태 UI 패턴만 참고 가치 있음 (파일 내 콜아웃 참조). 나머지(페이지네이션·네트워크)는 해당 없음 |

그 외 happy_v12 전용 문서(인증/네트워크/마이페이지/이미지업로드/스타터템플릿)는 삭제됨. 필요하면 `downloaded/folder/.claude/my-patterns/`에 원본 존재.
