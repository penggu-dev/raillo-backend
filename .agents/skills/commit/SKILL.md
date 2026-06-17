---
name: commit
description: 현재 브랜치의 작업 내용을 읽고 브랜치명에서 이슈번호를 파악하여 자동으로 커밋 메시지를 작성합니다.
context: inline
allowed-tools: [Bash(git *)]
---

# Git Commit Convention

## 절대 규칙

- **`Co-Authored-By` 라인을 커밋 메시지에 절대 포함하지 않는다.**
- 기본 모드는 **메시지 제안만** 한다. 실제 커밋은 사용자가 명시적으로 요청할 때만 실행한다.
- 메인 브랜치(`main`, `develop`)에서는 직접 커밋하지 않는다.

## 커밋 메시지 형식

```
type: description (#issue-number)
```

### 타입 정의

| 타입 | 설명 |
|------|------|
| feat | 새로운 기능 또는 기존 기능 관련 |
| bug | 버그 수정 |
| refactor | 리팩터링 |
| test | 테스트 관련 |
| chore | 빌드, 패키지, 설정 등 운영 |
| docs | 문서 관련 |

> 브랜치 라벨(`feature`, `bug`, `refactor`, ...) → 커밋 타입 매핑은 `/branch` SKILL.md의 표를 따른다.

### 형식 규칙

1. **type**: 위 6종 중 하나 (소문자)
2. **description**: 한글, 첫 글자 대문자, **마침표 없음**, 50자 이내 권장
3. **issue-number**: `#` 포함

### 올바른 예시

```
feat: Ticket 상태 검증을 도메인 엔티티 내부로 통합 (#117)
refactor: SeatBooking 엔티티 역정규화 (#122)
bug: 예매 취소 시 상태 업데이트 실패 수정 (#105)
test: 환불 관련 테스트 코드 추가 (#84)
chore: k6 부하 테스트 스크립트 추가 (#126)
docs: 예매 API 명세 문서 작성 (#130)
```

### 잘못된 예시

```
Feat: 기능 추가 (#117)    ❌ 타입 대문자
feat 기능 추가 (#117)     ❌ 콜론 누락
feat: 기능 추가. (#117)   ❌ 마침표
feat: 기능 추가 117       ❌ # 누락
feat: 기능 추가           ❌ 이슈 번호 누락
```

## Workflow

### 1. 브랜치 정보 수집

```bash
git branch --show-current        # 현재 브랜치명
git diff --cached --name-status   # staged
git diff --name-status            # unstaged
```

### 2. 브랜치명에서 정보 추출

브랜치명 형식: `label/issue-number[-description]`

- `feature/45-add-login` → 타입 `feat`, 이슈 `#45`, 힌트 `add-login`
- `refactor/122-seatbooking-query` → 타입 `refactor`, 이슈 `#122`, 힌트 `seatbooking-query`
- `bug/88` → 타입 `bug`, 이슈 `#88`, 힌트 없음 → diff에서 유추

### 3. 메시지 생성

사용자가 작업 내용을 설명하면 그 설명을 우선 사용한다. 없으면 git diff를 분석해 description을 작성한다.

### 4. 출력 (단일 형식)

```
✅ 추천 커밋 메시지:
{type}: {description} (#{issue-number})

📌 현재 브랜치: {branch-name}
📝 변경된 파일:
- {path1}
- {path2}

💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

### 5. 커밋 실행 (사용자 요청 시)

```bash
git add {지정된 파일 또는 전체}
git commit -m "{type}: {description} (#{issue-number})"
```

실행 후:
```
✅ 커밋 완료!
커밋 해시: {hash}
📌 다음 단계: git push origin {branch}
```

## 사용 예시

### 예시 1: 자동 감지 (브랜치 + diff)

**브랜치:** `refactor/102-optimize-user-query`

입력:
```
커밋 메시지 추천해줘
```

출력:
```
✅ 추천 커밋 메시지:
refactor: 사용자 조회 쿼리 최적화 (#102)

📌 현재 브랜치: refactor/102-optimize-user-query
💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

### 예시 2: 사용자가 작업 내용 설명

**브랜치:** `refactor/122-seatbooking-query`

입력:
```
SeatBooking 엔티티 역정규화했어, 커밋 메시지 만들어줘
```

출력:
```
✅ 추천 커밋 메시지:
refactor: SeatBooking 엔티티 역정규화 (#122)
```

## 예외 처리

### 1. 메인 브랜치에 있는 경우

**브랜치:** `main` 또는 `develop`

```
⚠️ 메인 브랜치(`develop`)에서는 직접 커밋하지 않습니다.
작업 브랜치를 먼저 생성해주세요 → `/branch` skill 사용
```

### 2. 이슈 번호를 추출할 수 없는 경우

브랜치명에 이슈 번호가 없거나(`my-feature`) 알 수 없는 라벨(`hotfix/45`)이면:

```
⚠️ 브랜치명에서 이슈 번호 또는 타입을 추출할 수 없습니다.
브랜치명: {branch}

옵션:
1. 이슈 번호를 알려주세요: "이슈 45번으로 커밋해줘"
2. 올바른 브랜치명으로 재시작: feature/45-add-login
```

사용자가 이슈 번호 제공 시 그 번호로 메시지 생성.

### 3. 변경사항이 없는 경우

```
⚠️ 커밋할 변경사항이 없습니다. 파일을 수정한 후 다시 시도해주세요.
```
