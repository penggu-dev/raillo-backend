---
name: pr
description: 현재 브랜치의 변경사항을 분석하고 관련 이슈를 기반으로 PR을 자동 생성합니다.
context: inline
allowed-tools: [Bash(git *), Bash(gh *)]
---

# Pull Request 생성 가이드

현재 브랜치의 작업 내용을 분석하고, 팀 PR 템플릿에 맞춰 자동으로 PR을 생성합니다.

## 실행 절차

### 1단계: 브랜치 정보 수집

다음 명령어를 **병렬로** 실행하여 정보를 수집합니다:

```bash
# 현재 브랜치명 확인 (이슈 번호 추출용)
git branch --show-current

# develop 브랜치 대비 전체 커밋 목록
git log develop..HEAD --oneline

# develop 브랜치 대비 전체 변경사항
git diff develop...HEAD --stat

# 리모트 push 여부 확인
git status -sb
```

### 2단계: 이슈 번호 추출 및 이슈 제목 조회

브랜치명에서 이슈 번호를 추출합니다.

**브랜치명 패턴:** `{label}/{issue-number}[-description]`

```
feature/193-payment-metrics → 이슈 번호: 193
bug/45-fix-login-error → 이슈 번호: 45
```

추출한 이슈 번호로 GitHub 이슈 제목을 조회합니다:

```bash
gh issue view {issue-number} --json title --jq '.title'
```

**이 이슈 제목이 PR 제목이 됩니다.**

### 3단계: PR 본문 작성

커밋 목록과 변경사항을 분석하여 PR 본문을 작성합니다.

#### PR 템플릿

```markdown
## 관련 Issue (필수)
- close #{issue-number}

## 주요 변경 사항 (필수)
- {변경사항 1}
- {변경사항 2}
- ...

## 리뷰어 참고 사항
{리뷰 시 참고할 점이 있으면 작성, 없으면 "없음"}

## 추가 정보
{추가 정보가 있으면 작성, 없으면 "없음"}

## PR 작성 체크리스트 (필수)
- [x] 제목이 Issue와 동일함을 확인했습니다.
```

#### 주요 변경 사항 작성 규칙

- 커밋 메시지를 그대로 복사하지 않습니다
- 변경사항의 **목적과 의미**를 중심으로 작성합니다
- 리뷰어가 코드를 읽기 전에 맥락을 파악할 수 있도록 합니다
- 한국어로 작성합니다
- 표로 정리하면 이해를 도울 수 있는 항목(메트릭, API 스펙, 에러 코드 매핑, 설정값 등)이 있으면 마크다운 표로 포함합니다

### 4단계: PR 생성

```bash
# 리모트에 push (필요한 경우)
git push -u origin {branch-name}

# PR 생성
gh pr create --base develop --title "{이슈 제목}" --body "$(cat <<'EOF'
{PR 본문}
EOF
)"
```

## 인자 처리

사용자가 `/pr` 뒤에 이슈 번호를 명시할 수 있습니다:

- `/pr` → 브랜치명에서 이슈 번호 자동 추출
- `/pr 193` → 이슈 번호 193 사용

## 예외 상황

### 브랜치명에서 이슈 번호를 추출할 수 없는 경우

```
⚠️ 브랜치명에서 이슈 번호를 추출할 수 없습니다.
현재 브랜치: {branch-name}

이슈 번호를 직접 입력해주세요:
예시: /pr 193
```

### develop과 차이가 없는 경우

```
⚠️ develop 브랜치 대비 변경사항이 없습니다.
커밋을 먼저 생성해주세요.
```

### 이슈가 존재하지 않는 경우

```
⚠️ GitHub 이슈 #{issue-number}를 찾을 수 없습니다.
이슈 번호를 확인해주세요.
```

## 출력 형식

PR 생성 완료 후:

```
✅ PR이 생성되었습니다!
{PR URL}
```
