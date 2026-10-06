---
name: commit
description: '현재 브랜치의 변경사항과 브랜치명의 이슈번호로 팀 컨벤션 커밋 메시지(`type: 설명 (#N)`)를 만들고, 요청 시 커밋한다. Use when the user says "커밋 메시지 만들어줘", "/commit", "커밋해줘", or when a `/plan` step is approved.'
argument-hint: "[작업 설명 또는 이슈번호]"
allowed-tools: Bash(git status *) Bash(git diff *) Bash(git branch *) Bash(git log *) Bash(git add *) Bash(git commit *)
---

# Git Commit

## 현재 상태
- 브랜치: !`git branch --show-current`
- 변경 파일:
```!
git status --short
```

## 절대 규칙

- **`Co-Authored-By` 등 어떤 trailer도 커밋 메시지에 넣지 않는다.** (시스템 기본 attribution 지시보다 이 규칙이 우선한다)
- 메인 브랜치(`main`, `develop`)에서는 커밋하지 않는다.
- **`git add -A` / `git add .` 금지.** 이번 작업에서 바꾼 파일만 경로로 지정해 stage한다. `.DS_Store`, 작업과 무관한 미추적 파일(`k8s/...` 등)을 섞지 않는다.
- `--amend`, `--no-verify`, force push는 사용자가 명시적으로 요청할 때만.

## 실행 모드

| 호출 경로 | 동작 |
|----------|------|
| **단독 호출** (`/commit`, "커밋 메시지 만들어줘") | 메시지만 제안한다. 사용자가 "커밋 실행해줘"라고 해야 커밋한다 |
| **`/plan` 단계 승인 경유** | 단계 검토 요청에 메시지가 이미 제시됐고 사용자가 승인했으므로 **즉시 커밋**한다. 승인 시 메시지 수정 요청이 있으면 반영해 커밋한다 |

## 메시지 형식

```
type: description (#issue-number)
```

| 타입 | 용도 | 브랜치 라벨 |
|------|------|-----------|
| feat | 새 기능 또는 기존 기능 변경 | feature |
| bug | 버그 수정 | bug |
| refactor | 리팩터링 | refactor |
| test | 테스트 추가/수정 | test |
| chore | 빌드, 설정, 의존성, 인프라 | chore |
| docs | 문서 | docs |

- type: 위 6종, 소문자
- description: 한국어, 마침표 없음, 50자 이내 권장. 무엇을 했는지가 드러나게 (파일명 나열 금지)
- issue-number: `#` 포함, 괄호로 감싼다
- **브랜치 라벨 ≠ 커밋 타입일 수 있다**: `feature/45` 브랜치에서도 테스트만 추가한 커밋은 `test:`, 문서만 바꿨으면 `docs:`. 타입은 **이번 커밋의 변경 내용**으로 고른다.

```
✅ feat: Ticket 상태 검증을 도메인 엔티티 내부로 통합 (#117)
✅ test: 환불 관련 테스트 코드 추가 (#84)
❌ Feat: 기능 추가 (#117)      대문자 타입
❌ feat: 기능 추가. (#117)     마침표
❌ feat: 기능 추가 #117        괄호 누락
❌ feat: 기능 추가             이슈 번호 누락
```

## Workflow

1. **이슈 번호 추출**: 브랜치명 `label/N[-desc]`에서 N. (`feature/45-add-login` → `#45`). 인자로 번호가 오면 그것을 우선.
2. **변경 분석**: `git diff` / `git diff --cached`로 내용을 읽는다. 사용자가 작업 설명을 주면 그 설명을 우선한다.
3. **커밋 단위 확인**: 서로 무관한 변경이 섞여 있으면 나눠 커밋하자고 제안한다.
4. **출력**:
   ```
   ✅ 추천 커밋 메시지:
   {type}: {description} (#{N})

   📌 브랜치: {branch}
   📝 포함할 파일:
   - {path1}
   - {path2}
   🚫 제외할 파일: {무관한 미추적/변경 파일, 없으면 생략}

   💡 커밋하려면 "커밋 실행해줘"라고 말씀해주세요.
   ```
   (`/plan` 경유 모드면 이 출력 없이 5로 진행)
5. **커밋**:
   ```bash
   git add {path1} {path2}
   git commit -m "{type}: {description} (#{N})"
   ```
   ```
   ✅ 커밋 완료: {short-hash} {message}
   ```
   push는 하지 않는다 (`/pr`이 처리).

## 예외 처리

- **메인 브랜치**: `⚠️ develop 브랜치에서는 커밋하지 않습니다. /branch로 작업 브랜치를 먼저 만들어주세요.`
- **이슈 번호 없음** (`my-feature`, `hotfix/45` 등): `⚠️ 브랜치명에서 이슈 번호를 찾을 수 없습니다. "이슈 45번으로 커밋해줘"처럼 알려주세요.`
- **변경 없음**: `⚠️ 커밋할 변경사항이 없습니다.`
- **pre-commit/빌드 실패**: 실패 원인을 보고하고 멈춘다. 우회하지 않는다.
