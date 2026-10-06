---
name: branch
description: 팀 네이밍 컨벤션에 맞춰 브랜치명을 정하고 최신 develop 위에 분기합니다. Use when the user asks for a branch, like "브랜치 만들어줘", "/branch 45 로그인", "이슈 102번 브랜치 파줘".
context: inline
allowed-tools: [Bash(git *), Bash(gh *)]
---

# Git Branch

브랜치명을 팀 컨벤션에 맞춰 정하고 직접 분기한다. 명령어만 출력하고 끝내지 않는다.

## 절대 규칙

- 기준은 항상 **원격의 최신 상태**다. 분기 전에 `git fetch`로 갱신하고 `origin/`으로 시작하는 ref에서 분기한다. 로컬 브랜치에서 분기하지 않는다 — 로컬은 뒤처져 있을 수 있고, 그러면 낡은 기준으로 작업이 시작된다.
- `--no-track`으로 분기한다. 지정하지 않으면 새 브랜치가 기준 브랜치를 추적해서 `git pull`이 그 브랜치를 끌어오고 `git status`가 그 브랜치와 비교된다.
- 이슈 번호 없이 분기하지 않는다.
- 커밋되지 않은 변경을 **임의로 stash하거나 커밋하지 않는다.** 분기가 거부되면 사용자가 고르게 한다.

## 이름 형식

```
{label}/{issue-number}[-{description}]
```

| 자리 | 규칙 |
|---|---|
| label | `feature` `bug` `refactor` `test` `chore` `docs` 중 하나 |
| issue-number | 숫자만 |
| description | 선택. 영문 소문자와 하이픈, 2~3 단어 |

라벨은 이 여섯 개뿐이다. `hotfix`, `fix`, `feat` 같은 다른 이름을 쓰지 않는다.

description은 세 번째 조각이다. `feature/270-payment-recovery-worker`에서 `payment-recovery-worker`가 description이다.

**한 이슈에 브랜치를 둘 이상 만들 때는 description이 필수다.** 같은 이슈 번호를 쓰므로 description이 없으면 이름이 겹쳐 만들 수 없다. #270에 브랜치가 둘 있는 것이 그 예다.

```
feature/270-payment-recovery-worker
feature/270-seat-protection-worker
```

| 올바름 | 틀림 | 이유 |
|---|---|---|
| `feature/1` | `Feature/1` | 대문자 |
| `feature/45-add-login` | `feature_45` | 언더스코어 |
| `bug/45-fix-login-error` | `feature/1.add-layout` | 점 |
| `refactor/102-optimize-query` | `feature 1` | 공백 |
| `test/51-payment-tests` | `feature/add-layout` | 이슈 번호 누락 |
| `chore/76-update-deps` | `1-add-layout` | 라벨 누락 |
| `docs/130-api-documentation` | `hotfix/45` | 없는 라벨 |

저장소에 `feature/reservation-redis`처럼 번호가 없는 브랜치와 `ogu1208/event-driven`처럼 사용자명을 쓴 브랜치가 남아 있다. 컨벤션 이전 것이다. 새로 그런 이름을 만들지 않고, 남의 브랜치를 개명하자고 제안하지도 않는다.

## 라벨과 커밋 타입

이 표가 단일 소스다. `/commit`과 `/issue`가 참조한다.

| 브랜치 라벨 | 커밋 타입 | 이슈 CATEGORY |
|---|---|---|
| feature | feat | FEATURE |
| bug | bug | BUG |
| refactor | refactor | REFACTOR |
| test | test | TEST |
| chore | chore | CHORE |
| docs | docs | DOCS |

커밋 타입은 `bug`다. `fix`가 아니다.

여기의 라벨은 브랜치명에 쓰는 소문자 단어다. GitHub 이슈에 붙는 라벨은 `✨ feature`처럼 이모지가 이름의 일부이며 다른 값이다. 그 값은 `/issue` SKILL.md에 있다.

## 분기 기준 결정

**기준은 혼자 정하지 않고 묻는다.** 기본값을 제시하고 사용자가 고르게 한다. 기본값은 `origin/develop`이다.

묻기 전에 `git fetch origin`으로 갱신하고, 현재 브랜치와 같은 이슈 번호를 쓰는 브랜치가 있는지 본다.

### 같은 이슈의 브랜치가 이미 있을 때

작업이 길어져 PR을 나누려는 상황이다. 현재 브랜치 위에 쌓을지 먼저 묻는다.

```
#270에는 이미 브랜치가 있습니다.
- feature/270-payment-recovery-worker (현재 브랜치)

이번이 #270의 두 번째 브랜치입니까? 기준을 골라주세요.

1. origin/feature/270-payment-recovery-worker 위에 쌓는다
   앞 작업을 전제하는 후속 작업일 때. PR을 나눠 리뷰 단위를 작게 만들 수 있습니다.
   PR base를 앞 브랜치로 지정해야 합니다.
2. origin/develop 에서 새로 판다
   앞 작업과 독립일 때.
```

### 새 브랜치일 때

기본은 `origin/develop`이다. 팀원이 작업 중인 브랜치 위에 올려야 할 수도 있으므로, **origin에 남아 있는 같은 라벨의 브랜치를 모아 번호로 제시한다.**

```bash
git branch -r --format='%(refname:short)' | sed 's|^origin/||' | grep "^{label}/"
```

이 필터가 `main`, `develop`, `HEAD`, 컨벤션 이전 브랜치를 자동으로 걸러낸다. 열린 PR이 있으면 `gh pr list`로 번호를 붙인다.

```
feature/45-add-login 으로 분기합니다. 기준을 골라주세요.

1. origin/develop (기본, {해시 8자리})
2. origin/feature/270-payment-recovery-worker (PR #299, open)
3. origin/feature/270-seat-protection-worker (PR #303, open)
4. origin/feature/297-reservation-read-delete (PR #301, open)
5. origin/feature/reservation-redis (PR 없음)

머지되지 않은 브랜치의 코드를 전제할 때만 1 외의 것을 고릅니다.
```

같은 라벨의 브랜치가 없으면 선택지를 만들지 않고 `origin/develop`으로 진행한다. 사용자가 "응"이나 "진행해"로만 답하면 1번을 쓴다.

### 스택은 이슈 경계를 넘을 수 있다

기준은 이슈 동일성이 아니라 코드 의존성이다. 이 저장소에는 지금 3단 스택이 있고 가운데 단은 이슈 경계를 넘는다.

```
origin/develop
  └─ feature/297-reservation-read-delete      (PR #301, base develop)
       └─ feature/270-payment-recovery-worker (PR #299, base 297 브랜치)
            └─ feature/270-seat-protection-worker (PR #303, base 270 브랜치)
```

## 분기 실행

### develop 기준

```bash
git fetch origin develop
git switch -c {label}/{issue-number}-{description} --no-track origin/develop
```

### 스택 기준

앞 브랜치를 가져와야 하므로 `develop`만이 아니라 전체를 fetch한다.

```bash
git fetch origin
git switch -c {label}/{issue-number}-{description} --no-track origin/{앞 브랜치}
```

앞 브랜치가 아직 푸시되지 않았으면 로컬 ref를 쓰되, 기준이 원격에 없다는 것과 PR을 올리기 전에 먼저 푸시해야 한다는 것을 알린다.

### 보고

분기 후 아래 형식으로 보고한다. 이 블록이 보고 형식의 기준이다.

```
{브랜치명} 으로 분기했습니다.
기준: {기준 ref} ({해시 8자리}, {커밋 제목})
```

커밋되지 않은 변경이 따라왔으면 몇 건인지 함께 적는다.

스택으로 분기했으면 기준이 develop이 아님을 함께 알린다. PR의 base는 `/pr`이 조상 관계로 알아내므로 따로 넘기지 않아도 된다.

```
{브랜치명} 으로 분기했습니다.
기준: origin/{앞 브랜치} ({해시 8자리}) — develop이 아닙니다.
PR은 앞 PR이 머지된 뒤에 올리거나, base를 앞 브랜치로 두고 올리세요.
```

## Workflow

1. 라벨, 이슈 번호, 설명을 추출한다. 알 수 없으면 묻는다.
2. `git fetch origin` — 중복 확인과 기준 선택보다 먼저다. fetch 전에 보면 방금 만들어진 브랜치가 안 보인다.
3. 중복을 확인한다. `git branch -a`로 로컬과 원격 양쪽, `git worktree list`로 점유 여부까지.
4. 기준을 묻는다.
5. 분기하고 보고한다.

## 사용 예시

```
/branch REFACTOR SeatBooking 엔티티를 역정규화하고 좌석 조회 쿼리를 최적화하려고. 이슈 122번.
```

`refactor/122-seatbooking-query`로 분기한다. 한글 설명을 영문 세 단어로 줄이고, 그 이상 늘리지 않는다.

## 예외 처리

### 이슈 번호 누락

```
이슈 번호가 필요합니다. 브랜치명과 커밋 메시지, PR이 모두 이 번호를 씁니다.

이슈가 아직 없으면 /issue로 먼저 만들까요?
있으면 번호를 알려주세요.
```

### 라벨 불명

```
작업 타입을 알려주세요.

FEATURE, BUG, REFACTOR, TEST, CHORE, DOCS 중 하나입니다.
```

### 이름 중복

이미 그 이름이 로컬이나 원격에 있으면 만들지 않는다.

```
feature/45-add-login 이 이미 있습니다. (로컬 / origin / 양쪽)

기존 브랜치로 옮길까요, 아니면 다른 description으로 만들까요?
```

다른 worktree가 그 브랜치를 점유하고 있으면 `git switch`가 `fatal: 'X' is already used by worktree at ...`로 거부한다. 이때는 전환을 제안하지 않고 그 worktree 경로를 알린다.

```
feature/270-seat-protection-worker 는 .worktrees/270-seat-protection-worker 에
체크아웃돼 있습니다. 그 디렉터리에서 작업하시거나 다른 이름으로 만드세요.
```

### 변경사항 충돌로 분기 실패

어떤 파일의 **커밋된 내용이 HEAD와 기준 커밋에서 다르고, 동시에 그 파일에 커밋되지 않은 수정이 있으면** `git switch`가 거부한다.

```
error: Your local changes to the following files would be overwritten by checkout:
	{파일}
Please commit your changes or stash them before you switch branches.
```

임의로 처리하지 않고 사용자가 고르게 한다.

```
{파일}의 커밋되지 않은 변경 때문에 분기하지 못했습니다.

1. 변경을 먼저 커밋한다
2. 브랜치만 만들어 두고 전환은 나중에 한다 (git branch는 작업 트리를 건드리지 않습니다)
3. 새 worktree에서 분기한다 (현재 작업을 그대로 두고 병행)
4. git stash로 치워두고 분기한 뒤 되돌린다
```

4번을 고르면 `git stash pop`이 새 브랜치에서 충돌할 수 있다. 충돌하면 **직접 해결하지 않고 멈춰서 보고한다.** stash는 남아 있으므로 잃지 않는다.

2번은 아래와 같다. 전환하지 않으므로 거부되지 않는다.

```bash
git branch {브랜치명} --no-track origin/develop
```

## 첫 push

`--no-track`으로 만들었으므로 upstream이 없다. 이 저장소는 `push.autoSetupRemote`가 설정돼 있지 않아 맨 `git push`는 실패한다. 첫 push는 `-u`로 upstream을 지정한다.

```bash
git push -u origin {브랜치명}
```

`/pr`이 PR을 만들 때 이 형태로 푸시하므로 `/pr`을 쓰면 따로 할 일은 없다.
