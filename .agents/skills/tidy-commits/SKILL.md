---
name: tidy-commits
description: PR 전에 현재 브랜치의 커밋 이력을 정리한다. 수정, 리뷰 반영 커밋을 원래 커밋에 합치고 메시지를 컨벤션에 맞춘다. 코드 내용은 바꾸지 않는다. Use when the user says "/tidy-commits", "커밋 정리해줘", "커밋 합쳐줘", "PR 전에 히스토리 정리".
argument-hint: "[base: 비우면 origin/develop]"
allowed-tools: Bash(git *)
---

# Tidy Commits

## 현재 상태
- 브랜치: !`git branch --show-current`
- 인자: `$ARGUMENTS`

## 왜 필요한가

작업 중에는 "오타 수정", "리뷰 반영", 같은 파일을 다시 고친 커밋이 쌓인다. 머지 커밋으로 합치므로 이 이력이 develop에 그대로 남는다. 리뷰어와 나중에 `git log`를 읽는 사람이 **논리 단위 하나 = 커밋 하나**로 읽을 수 있게 정리한다.

## 규칙

- **코드 내용은 바꾸지 않는다.** 정리 전후의 최종 트리가 같아야 한다. 다르면 되돌린다.
- **승인 전에는 rebase하지 않는다.** 정리안을 먼저 보여준다.
- 시작 전에 작업 트리가 깨끗해야 한다. 커밋하지 않은 변경이 있으면 멈춘다.
- 시작 전에 백업 ref를 만든다. 실패하거나 결과가 다르면 그 ref로 되돌린다.
- 충돌이 나면 직접 해결하지 않고 `git rebase --abort`로 되돌린 뒤 보고한다.
- 커밋 메시지는 `/commit` 형식(`type: 설명 (#N)`)이고 trailer를 넣지 않는다.
- **push하지 않는다.** 이미 원격에 올라간 브랜치면 `--force-with-lease`가 필요하다는 것만 알린다. 리뷰가 시작된 PR이면 정리하지 말자고 먼저 말한다.
- `git rebase -i`를 대화형으로 열지 않는다. 아래 방식으로 todo를 파일로 넘긴다.

## 정리 기준

| 처리 | 대상 |
|---|---|
| 합친다(fixup) | 앞 커밋을 고치는 커밋. "수정", "오타", "리뷰 반영", "누락", 같은 파일만 다시 고친 커밋 |
| 메시지를 바꾼다 | 형식이 틀렸거나, 합친 뒤 내용을 더 이상 설명하지 못하는 메시지 |
| 그대로 둔다 | 서로 다른 논리 단위. 코드와 테스트, 문서처럼 따로 리뷰할 가치가 있는 커밋 |
| 제안만 한다 | 순서 바꾸기, 커밋 쪼개기. 충돌과 실수 위험이 커서 사용자가 원할 때만 한다 |

코드 변경은 정리 후의 각 커밋에서도 빌드가 깨지지 않게 묶는다.

## 절차

1. **범위**: base는 인자, 없으면 `origin/develop`. `git fetch origin` 후 `git merge-base {base} HEAD`부터 `HEAD`까지 본다.

   ```bash
   git log --reverse --format='%h %s' {merge-base}..HEAD
   git show --stat --format='%h %s' {hash}   # 커밋마다
   ```

2. **정리안 제시** 후 멈춘다.

   ```
   🧹 커밋 정리안 — {브랜치} ({n}개 → {m}개)

   | # | 정리 후 메시지 | 원래 커밋 | 처리 |
   |---|---|---|---|
   | 1 | feat: 좌석 점유 검증 추가 (#45) | a1b2c3d, e4f5g6h(오타 수정) | 합침 |
   | 2 | test: 좌석 점유 테스트 추가 (#45) | i7j8k9l | 유지 |
   | 3 | docs: 예약 문서 갱신 (#45) | m1n2o3p(문서 수정) | 메시지 변경 |

   원격: {올라가 있지 않음 | 올라가 있음 → 정리 후 force push 필요}
   진행할까요?
   ```

3. **실행** (승인 후)

   ```bash
   GD=$(git rev-parse --absolute-git-dir)
   BACKUP=backup/{브랜치}-$(date +%Y%m%d%H%M%S)
   git branch "$BACKUP"
   ```

   todo를 `$GD/tidy-todo`에, 바꿀 메시지를 `$GD/tidy-msg-{번호}`에 쓴다. 메시지를 바꿀 커밋은 그 묶음의 마지막 줄 뒤에 `exec`를 둔다.

   ```
   pick a1b2c3d
   fixup e4f5g6h
   exec git commit --amend --only -q -F {GD}/tidy-msg-1
   pick i7j8k9l
   ```

   ```bash
   GIT_SEQUENCE_EDITOR="cp $GD/tidy-todo" GIT_EDITOR=true git rebase -i {merge-base}
   ```

4. **검증**: `git diff "$BACKUP" HEAD`가 비어 있어야 한다. 비어 있지 않거나 rebase가 멈췄으면 `git rebase --abort` 또는 `git reset --hard "$BACKUP"`로 되돌리고 보고한다.
5. **보고**: 정리 후 `git log --oneline`, 백업 ref 이름, 원격에 올라가 있으면 아래 명령을 알린다. 임시 파일(`tidy-todo`, `tidy-msg-*`)은 지운다.

   ```bash
   git push --force-with-lease origin {브랜치}
   ```

   백업 ref는 사용자가 확인한 뒤 `git branch -D {백업}`으로 지우라고 안내한다.
