---
name: warn-full-gradle-build
enabled: true
event: bash
pattern: gradlew(\.bat)?\s+(clean\s+)?build\b
action: warn
---

⚠️ **Gradle 전체 빌드 감지 — 비용 낭비 주의**

이 프로젝트에서는 전체 빌드 대신 **타깃 컴파일**을 사용하세요:

```
# ✅ 권장 (빠르고 저렴)
gradlew :app:compileDebugKotlin

# ❌ 지양 (전체 빌드 — 시간·토큰 낭비)
gradlew build
gradlew clean build
```

클린 빌드가 정말 필요하다면 먼저 사용자에게 확인을 받으세요.
