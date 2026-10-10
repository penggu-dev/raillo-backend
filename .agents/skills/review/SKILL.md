---
name: review
description: 현재 브랜치(또는 지정 범위)의 변경을 대화 맥락이 없는 서브에이전트들이 관점별로 병렬 리뷰하고, 별도 검증 에이전트가 오탐을 걸러 확인된 지적만 보고한다. Use when the user says "/review", "리뷰해줘", "중간 리뷰", or when `/implement` finishes all steps. Do not auto-invoke after ordinary edits.
argument-hint: "[비우면 origin/develop...HEAD | --staged | --working | <base>..<head> | <경로>]"
allowed-tools: Bash(git diff *) Bash(git log *) Bash(git status *) Bash(git branch *) Bash(git merge-base *) Bash(gh issue view *)
---

# Review

## 현재 상태
- 브랜치: !`git branch --show-current`
- 인자: `$ARGUMENTS`

## 원칙

- **메인 세션은 리뷰하지 않고 조율만 한다.** 코드를 쓴 세션은 의도를 알아서 확증 편향이 생긴다.
- 서브에이전트에게는 **diff 범위, 이슈 원문, 담당 체크리스트만** 넘긴다. 구현 의도나 계획 파일은 넘기지 않는다.
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

변경 파일 목록(`--stat`, `--name-only`)과 이슈(`gh issue view N --json title,body`, 실패하면 생략)를 모은다. diff가 비면 종료한다.

## 2. 병렬 리뷰

[reviewers.md](reviewers.md)의 **공통 지침 + 리뷰어 섹션**으로 프롬프트를 만들고 자리표시자를 채운다. diff 본문은 넣지 않는다. 에이전트가 직접 읽는다.

| 리뷰어 | 관점 |
|---|---|
| A | 컨벤션 (규칙 원문 인용 필수) |
| B | 정확성 (로직, 트랜잭션, 동시성, Redis/Lua) |
| C | 테스트 (누락 분기, 약한 검증) |

세 개를 `general-purpose` 서브에이전트로 **한 메시지에서 동시에** 띄운다. 변경이 파일 3개 이하, 200줄 미만이면 하나로 합친다.

## 3. 검증

1. 결과를 합치고 `confidence < 50`은 버린다. 같은 `file:line` + 같은 원인은 하나로 합친다.
2. 남은 것이 있으면 reviewers.md의 **검증자** 프롬프트로 새 서브에이전트에 넘긴다.
3. `CONFIRMED`만 남긴다.

## 4. 보고

검증 결과를 더하거나 빼거나 완화하지 않고 정리만 한다.

```
🔎 리뷰 — {범위} (파일 n개, +a/-b) · 후보 {x}건 → 확인 {y}건

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
