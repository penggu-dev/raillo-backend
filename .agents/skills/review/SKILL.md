---
name: review
description: 현재 브랜치(또는 지정 범위)의 변경을 대화 맥락이 없는 서브에이전트가 리뷰하고, 인용한 코드를 대조해 확인된 지적만 보고한다. 기본은 리뷰어 하나, --deep이면 관점별 셋. Use when the user says "/review", "리뷰해줘", "중간 리뷰", or when `/implement` finishes all steps. Do not auto-invoke after ordinary edits.
argument-hint: "[--deep] [비우면 origin/develop...HEAD | --staged | --working | <base>..<head> | <경로>]"
allowed-tools: Bash(git diff *) Bash(git log *) Bash(git status *) Bash(git branch *) Bash(git merge-base *) Bash(gh issue view *)
---

# Review

## 현재 상태
- 브랜치: !`git branch --show-current`
- 인자: `$ARGUMENTS`

## 원칙

- **메인 세션은 리뷰하지 않는다.** 서브에이전트를 띄우고, 지적이 인용한 줄을 대조하기만 한다. 코드를 쓴 세션은 의도를 알아서 확증 편향이 생긴다.
- 서브에이전트에게는 **diff 범위, 변경 파일 목록, 이슈 원문, 체크리스트만** 넘긴다. 구현 의도, 계획 파일, diff 본문은 넘기지 않는다.
- 확인된 지적만 보고한다. 0건도 정상이다.
- 리뷰는 제안만 한다. 코드를 고치거나 커밋하지 않는다.

## 1. 범위

| 인자 | `{DIFF_RANGE}` (`git diff {DIFF_RANGE}`로 쓴다) |
|---|---|
| 없음 | `origin/develop...HEAD` |
| `--staged` | `--cached` |
| `--working` | `HEAD` |
| `A..B`, `A...B` | 그대로 |
| 경로 | `origin/develop...HEAD -- <경로>` |

변경 파일 목록(`--stat`)과 이슈(`gh issue view N --json title,body`, 실패하면 생략)를 모은다. diff가 비면 종료한다. 문서(`*.md`)만 바뀌었으면 코드 리뷰 대신 그 사실을 알리고 종료한다.

## 2. 리뷰

[reviewers.md](reviewers.md)의 **공통 지침**에 체크리스트를 붙이고 자리표시자를 채운다. `general-purpose` 서브에이전트를 `model: sonnet`으로 띄운다.

| 모드 | 에이전트 | 체크리스트 |
|---|---|---|
| 기본 | 1개 | A 컨벤션 + B 정확성 + C 테스트 |
| `--deep` | 3개, **한 메시지에서 동시에** | 하나씩 |

`--deep`은 사용자가 요청했거나, 변경이 결제, 좌석 점유(Lua), 트랜잭션 경계에 닿고 파일이 15개를 넘을 때만 쓴다.

## 3. 대조

에이전트를 새로 띄우지 않는다. 지적마다 메인 세션이 인용된 `file:line` 주변만 읽어 확인한다.

- 인용한 코드가 실제와 다르거나, 그 줄이 이번 diff에서 바뀌지 않았으면 버린다.
- 실패 시나리오가 코드로 확인되지 않으면 버린다. 판단이 서지 않으면 버리지 않고 "미확인"으로 표시한다.
- 같은 `file:line`과 같은 원인은 하나로 합친다.

## 4. 보고

```
🔎 리뷰 — {범위} (파일 n개, +a/-b) — 후보 {x}건 → 확인 {y}건

| # | 심각도 | 분류 | 위치 | 내용 |
|---|---|---|---|---|
| 1 | 🔴 blocker | 정확성 | [Foo.java:42](path:42) | 한 문장 |

### 1. {요약}
- 근거: {규칙 인용 또는 코드}
- 실패 시나리오: {입력/상태 → 잘못된 결과}
- 수정 제안: {구체적으로}

반영할 번호를 알려주세요.
```

심각도: 🔴 blocker 잘못된 결과, 데이터 손상, 동시성 결함, 컴파일 실패 / 🟠 major 명시 규칙 위반, 핵심 분기 테스트 누락 / 🟡 minor 동작 영향 없는 개선

## 5. 후속

- `/implement`에서 호출됐으면 고른 항목을 계획 파일의 새 단계로 넣는다.
- 단독 호출이면 고른 항목을 고치고 `/commit`을 안내한다.
- 사용자가 지적에 반대하면 따른다.
