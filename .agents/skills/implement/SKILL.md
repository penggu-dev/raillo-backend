---
name: implement
description: GitHub 이슈로 구현 계획을 세우고, 커밋 단위 단계마다 "구현 → 테스트 → 검토 요청 → 승인 시 커밋"을 반복한 뒤 /review, /docs, /pr로 마무리한다. Use when the user says "/implement 123", "이슈 123 작업 시작하자", "이 이슈 구현해줘", or asks to resume an in-progress plan.
argument-hint: "[issue-number]"
disable-model-invocation: true
allowed-tools: Bash(gh issue view *) Bash(git status *) Bash(git diff *) Bash(git log *) Bash(git branch *)
---

# Implement

## 현재 상태
- 브랜치: !`git branch --show-current`
- 인자: `$ARGUMENTS`

## 흐름

```
1. 준비   이슈 읽기, 기존 계획 파일 확인
2. 계획   코드 탐색 → 단계 분해 → .claude/plans/{N}.md → 승인
3. 루프   [구현 → /test → 검증 → 검토 요청 → 승인 → /commit] × n
4. 마무리 /review → /docs → /pr
```

## 규칙

- 계획 승인 전에는 코드를 고치지 않는다.
- **단계가 끝나면 멈추고 검토를 요청한다.** 승인 없이 커밋하거나 다음 단계로 가지 않는다.
- 단계 범위를 벗어나는 수정이 필요하면 멈추고 계획 변경을 제안한다.
- 계획 파일의 상태를 실제와 항상 맞춘다.
- 코드는 [.agents/rules/code-convention.md](../../rules/code-convention.md)를 따른다.

## 1. 준비

1. 이슈 번호: 인자 > 브랜치명 `label/N-...` > 묻기. `develop`, `main`이면 `/branch`를 먼저 안내하고 멈춘다.
2. `gh issue view {N} --json title,body,comments`. 체크리스트는 단계 후보다.
3. `.claude/plans/{N}.md`가 있으면 **재개**한다. `git log origin/develop..HEAD --oneline`, `git status`와 단계 상태를 대조해 현황을 요약하고 루프로 간다.

## 2. 계획

- 이슈가 언급한 클래스와 호출 관계, 같은 도메인의 기존 패턴, 해당 도메인의 `docs/` 폴더([.agents/rules/docs.md](../../rules/docs.md) "구조")를 읽는다. 이미 있는 Validator, ErrorCode, 쿼리, 유틸을 재사용 후보로 적는다.
- 코드로 정할 수 없는 선택지는 계획 전에 묻는다.

**단계 분해**
- 한 단계 = 커밋 하나. 그 단계만 적용해도 빌드가 깨지지 않는다.
- 안쪽부터: `raillo-domain` → infrastructure → application → facade/presentation → batch, 설정.
- 기능 코드와 그 테스트는 같은 단계에 둔다.
- 이슈가 너무 길어지면 이슈를 쪼개자고 제안한다. 한 단계가 너무 커지면 단계를 쪼갠다.

**계획 파일** `.claude/plans/{N}.md` (로컬 전용, 커밋하지 않는다)

```markdown
# #{N} {이슈 제목}
- 브랜치: {branch} / 상태: planning | in-progress | review | done

## 목표
{2~3문장}

## 단계
| # | 목표 | 테스트 | 타입 | 상태 | 커밋 |
|---|---|---|---|---|---|
| 1 | ... | 필요 | feat | todo | |

### 1. {목표}
- 변경: {파일, 클래스}
- 완료 조건: {검증 가능한 형태}
- 테스트: {케이스 개요 또는 불필요 이유}

## 결정 기록
- {날짜} {결정과 이유}
```

- 상태: `todo` → `doing` → `done` (건너뛰면 `skipped` + 이유)
- 테스트: 분기, 상태 전이, 계산, 검증, 동시성이 있으면 `필요`. DTO, 설정, 단순 위임이면 `불필요` + 이유.

단계 표를 보여주고 승인을 받는다.

## 3. 단계 루프

1. 단계를 `doing`으로 바꾼다.
2. 구현한다. 주변 코드의 스타일(탭 들여쓰기, 주석 밀도, 네이밍)을 따른다.
3. 테스트가 `필요`면 `/test` 절차로 작성한다. 이 안에서는 케이스 확인 없이 바로 쓴다.
4. 검증한다. `./gradlew :{모듈}:test --tests "{FQCN}"`. `raillo-domain`을 바꿨으면 `./gradlew compileJava compileTestJava`도 돌린다. Docker를 못 쓰면 컴파일까지만 하고 `미실행 (Docker 불가)`로 적는다.
5. 검토를 요청하고 **멈춘다.**

```
🔍 단계 {k}/{n} 검토 요청 — {목표}

변경 파일
- {path} — {무엇을 왜}

핵심 변경
- {리뷰어가 먼저 볼 결정, 트레이드오프 1~3개}

테스트
- {추가 n개, 실행 명령, 결과 / 불필요 이유}

커밋 메시지 (승인 시 바로 커밋)
{type}: {description} (#{N})
```

6. 응답에 따라:
   - **승인** → `/commit`으로 이 단계 파일만 커밋 → 계획 파일에 `done` + 해시 → 다음 단계
   - **수정 요청** → 반영 후 4부터 다시
   - **계획 변경** → 단계 표와 결정 기록을 고치고 보여준 뒤 진행

2회 고쳐도 빌드나 테스트가 실패하면 원인과 선택지를 보고하고 기다린다.

## 4. 마무리

1. 상태를 `review`로 바꾸고 `/review`를 실행한다. 사용자가 고른 지적은 새 단계로 추가해 루프로 처리한다.
2. 이번 브랜치에서 문서를 고쳤으면 `/docs`로 덧붙은 내용을 정리한다.
3. 단계 표(커밋 해시 포함)를 보여주고 `/pr`을 진행할지 묻는다. PR이 생성되면 상태를 `done`으로 바꾼다.
