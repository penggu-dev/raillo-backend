---
name: branch
description: 이슈 설명을 받아서 팀의 브랜치 네이밍 컨벤션에 맞춰 브랜치명을 자동으로 생성합니다.
context: inline
allowed-tools: []
---

# Git Branch Naming Convention

## 핵심 원칙

- **하나의 Issue = 하나의 Branch**
- 형식: `label/issue-number[-description]`
- 소문자 + 하이픈만 사용 (언더스코어/점/공백 금지)

## 브랜치 라벨 → 커밋 타입 매핑 (단일 소스)

이 표는 `/commit` skill도 참조한다.

| 브랜치 라벨 | 커밋 타입 | 용도 |
|-----------|---------|------|
| feature | feat | 새 기능 추가 |
| bug | bug | 버그 수정 |
| refactor | refactor | 리팩터링 |
| test | test | 테스트 작성/수정 |
| chore | chore | 빌드, 설정, 의존성 |
| docs | docs | 문서 작성/수정 |

## 형식 규칙

1. **label**: 위 6종 중 하나 (소문자)
2. **issue-number**: 숫자만 (예: 1, 45, 102)
3. **description** (선택): 영문 소문자 + 하이픈, 3~4 단어 권장

## 올바른 예시

```
feature/1
feature/1-add-layout
bug/45-fix-login-error
refactor/102-optimize-user-query
test/51-add-payment-tests
chore/76-update-dependencies
docs/130-write-api-documentation
```

## 잘못된 예시

```
Feature/1                   ❌ 대문자
feature_1                   ❌ 언더스코어
feature/1.add-layout        ❌ 점
feature 1                   ❌ 공백
feature/add-layout          ❌ 이슈 번호 누락
1-add-layout                ❌ 라벨 누락
```

## Workflow

1. 사용자 입력에서 다음을 추출:
   - 작업 타입 (FEATURE/BUG/REFACTOR/TEST/CHORE/DOCS)
   - 이슈 번호
   - 설명(선택)
2. 설명이 길면 핵심 키워드 2~4개로 간결화 (예: "사용자가 로그인할 때 이메일과 비밀번호로 JWT 받는 플로우" → `add-login-auth`)
3. 형식에 맞춰 브랜치명 생성
4. 브랜치 생성 명령어를 함께 출력 (사용자가 복사해서 실행)

## 출력 형식

```
✅ 추천 브랜치명:
{label}/{issue-number}-{description}

📋 브랜치 생성 명령어:
git checkout -b {label}/{issue-number}-{description}
```

## 사용 예시

### 예시 1: 태그 + 이슈 번호 + 설명

입력:
```
FEATURE 사용자 로그인 기능 추가 45 브랜치명 만들어줘
```

출력:
```
✅ 추천 브랜치명:
feature/45-add-login

📋 브랜치 생성 명령어:
git checkout -b feature/45-add-login
```

### 예시 2: 한글 설명 압축

입력:
```
REFACTOR: SeatBooking 엔티티를 역정규화하고 좌석 조회 쿼리를 최적화하려고. 이슈 122번.
```

출력:
```
✅ 추천 브랜치명:
refactor/122-seatbooking-query
```

## 예외 처리

### 1. 이슈 번호가 없는 경우

```
⚠️ 이슈 번호가 필요합니다.
GitHub 이슈를 먼저 생성하고 번호와 함께 다시 요청해주세요.

예시: "FEATURE 사용자 로그인 기능 45 브랜치명 만들어줘"
```

### 2. 타입을 알 수 없는 경우

```
⚠️ 작업 타입을 명확히 알려주세요.

사용 가능한 타입: FEATURE, BUG, REFACTOR, TEST, CHORE, DOCS
```
