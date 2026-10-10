---
name: pr
description: 현재 브랜치의 변경과 관련 이슈로 팀 템플릿에 맞춘 PR을 작성하고, 사용자 승인 후 생성한다. Use when the user says "/pr", "PR 올려줘", "PR 만들어줘", or when `/implement` finishes.
argument-hint: "[이슈번호]"
allowed-tools: Bash(git *) Bash(gh issue view *) Bash(gh pr list *) Bash(gh pr create *)
---

# Pull Request

## 규칙

- **승인 전에는 생성하지 않는다.** base, 제목, 본문을 먼저 보여주고 "올려줘", "진행해" 같은 명시적 승인 후에만 push와 `gh pr create`를 실행한다.
- **제목은 이슈 제목과 같다.**
- 본문은 한국어 "-습니다" 완결형이다.

## 절차

1. **정보 수집** (병렬)
   - 이슈 번호: 인자 > 브랜치명 `label/N-...`. 없으면 묻는다.
   - `gh issue view {N} --json title --jq .title`
   - `git fetch origin` 후 `git log origin/{base}..HEAD --oneline`, `git diff origin/{base}...HEAD --stat`
2. **base 결정**: 기본은 `develop`이다. 열린 PR의 head 브랜치 중 현재 브랜치의 조상이면서 develop에 머지되지 않은 것이 있으면 스택이다. 그 브랜치를 base로 제안한다.

   ```bash
   gh pr list --state open --json number,headRefName
   git merge-base --is-ancestor origin/{후보} HEAD
   ```

3. **본문 작성**

   ```markdown
   ## 관련 Issue (필수)
   - close #{N}

   ## 주요 변경 사항 (필수)
   - {변경 1}

   ## 리뷰어 참고 사항
   {없으면 "없음"}

   ## 추가 정보
   {없으면 "없음"}

   ## PR 작성 체크리스트 (필수)
   - [x] 제목이 Issue와 동일함을 확인했습니다.
   ```

   - 커밋 메시지를 복사하지 않고 **목적과 의미**를 쓴다. 리뷰어가 코드를 보기 전에 맥락을 잡을 수 있어야 한다.
   - 에러 코드, API 스펙, 설정값처럼 표가 나은 내용은 표로 쓴다.
   - 스택이면 리뷰어 참고 사항에 앞 PR 번호를 적는다.

4. **검수 요청**하고 멈춘다.

   ```
   📋 PR 생성 전 검수 요청

   Base: {base}
   제목: {이슈 제목}

   본문:
   ---
   {본문}
   ---

   이대로 생성할까요?
   ```

5. **생성** (승인 후)

   ```bash
   git push -u origin {branch}
   gh pr create --base {base} --title "{이슈 제목}" --body "$(cat <<'EOF'
   {본문}
   EOF
   )"
   ```

   생성 후 PR URL을 알린다.

## 예외

- 이슈 번호를 찾을 수 없음 → `/pr 193`처럼 번호를 달라고 한다.
- base 대비 커밋 없음 → 커밋을 먼저 하라고 안내한다.
- 이슈 조회 실패 → 번호를 확인해 달라고 한다.
