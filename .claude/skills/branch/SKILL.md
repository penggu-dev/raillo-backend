---
name: branch
description: 이슈 설명을 받아서 팀의 브랜치 네이밍 컨벤션에 맞춰 브랜치명을 자동으로 생성합니다.
context: inline
allowed-tools: []
---

# Git Branch Naming Convention Guide

팀의 브랜치 네이밍 컨벤션을 정의합니다. 모든 브랜치는 이 가이드를 따라야 합니다.

## 핵심 원칙

하나의 Issue당 하나의 Branch를 원칙으로 합니다.

## 브랜치 네이밍 형식
```
label/issue-number[-description]
```

### 형식 상세 설명

1. label: 브랜치의 용도를 나타내는 라벨 (소문자)
    - feature: 새 기능 추가
    - bug: 버그 수정
    - refactor: 리팩터링
    - test: 테스트 코드 추가/수정
    - chore: 빌드, 패키지 매니저 관련
    - docs: 문서 작성/수정

2. issue-number: 관련 이슈 번호 (숫자만)
   예: 1, 45, 102

3. description (선택사항): 이슈를 간단히 설명 (소문자, 하이픈만 사용)

### 형식 규칙

- 소문자만 사용
- 단어 구분은 하이픈 사용
- 특수문자(언더스코어, 점 등)는 불가
- 띄어쓰기는 절대 금지
- 대문자 사용 금지
- 언더스코어 사용 금지

## 브랜치 라벨 정의

| 라벨 | 설명 | 대응 커밋 타입 | 예시 |
|------|------|--------------|------|
| feature | 새로운 기능 추가 | feat | feature/45, feature/45-add-login |
| bug | 버그 수정 | bug | bug/88, bug/88-fix-cache-issue |
| refactor | 코드 리팩터링 | refactor | refactor/102, refactor/102-optimize-query |
| test | 테스트 작성/수정 | test | test/51, test/51-add-payment-tests |
| chore | 빌드, 패키지 관리 | chore | chore/76, chore/76-update-dependencies |
| docs | 문서 작성/수정 | docs | docs/130, docs/130-write-api-docs |

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
Feature/1 (대문자 사용)
feature_1 (언더스코어 사용)
feature/1_add_layout (언더스코어 사용)
feature/1.add-layout (점 사용)
feature 1 (띄어쓰기)
feature/add-layout (이슈 번호 없음)
1-add-layout (라벨 없음)
```

## 사용 방법

### 이슈 설명을 받아서 브랜치명 자동 생성

이슈의 내용과 이슈 번호를 말하면, 자동으로 올바른 브랜치명을 생성합니다.

입력 예시:
```
REFACTOR SeatBooking 역정규화로 인한 좌석 조회 쿼리 수정 122 해당 이슈 작업하려고해 브랜치명 추천해줘
```

Claude의 처리 과정:
1. 이슈 설명에서 작업 타입 인식: REFACTOR → refactor
2. 이슈 번호 추출: 122
3. 이슈 제목 분석: SeatBooking 역정규화로 인한 좌석 조회 쿼리 수정
4. 간결한 설명 생성: seatbooking-query

출력:
```
refactor/122-seatbooking-query
```

## 상세 사용 예시

### 예시 1: 태그가 있는 이슈 설명

입력:
```
FEATURE 사용자 로그인 기능 추가 45 해당 이슈 작업하려고해 브랜치명 만들어줄 수 있어?
```

Claude의 처리:
- 타입 인식: FEATURE → feature
- 이슈 번호: 45
- 설명 추출: 사용자 로그인 기능 추가 → add-login

출력:
```
feature/45-add-login
```

### 예시 2: 간단한 요청 (이슈 번호만)

입력:
```
feature 45번 브랜치명
```

Claude의 처리:
- 타입: feature
- 이슈 번호: 45
- 설명 없음

출력:
```
feature/45
```

### 예시 3: 복잡한 한글 설명

입력:
```
REFACTOR: 예약 시스템의 SeatBooking 엔티티를 역정규화하고, 이로 인해 변경된 좌석 조회 쿼리를 최적화하는 작업입니다. 이슈 번호는 122번이에요.
```

Claude의 처리:
- 타입: REFACTOR → refactor
- 이슈 번호: 122
- 핵심만 추출: seatbooking-query

출력:
```
refactor/122-seatbooking-query
```

## 예외 상황 처리

### 이슈 번호가 없는 경우

입력:
```
사용자 로그인 기능 추가 브랜치명 만들어줘
```

출력:
```
⚠️ 이슈 번호가 필요합니다.
GitHub 이슈를 먼저 생성하고, 이슈 번호와 함께 다시 요청해주세요.

예시: "FEATURE 사용자 로그인 기능 추가 45 브랜치명 만들어줘"
```

### 타입을 알 수 없는 경우

입력:
```
뭔가 수정 45 브랜치명
```

출력:
```
⚠️ 작업 타입을 명확히 알려주세요.

사용 가능한 타입:
- FEATURE: 새 기능 추가
- BUG: 버그 수정
- REFACTOR: 리팩터링
- TEST: 테스트 작성
- CHORE: 빌드/의존성 관련
- DOCS: 문서 작업

예시: "FEATURE 사용자 로그인 기능 45 브랜치명 만들어줘"
```

### 설명이 너무 긴 경우

입력:
```
FEATURE 사용자가 로그인할 때 이메일과 비밀번호를 입력하고 JWT 토큰을 받아서 인증하는 전체 플로우를 구현하는 기능 45
```

Claude의 처리:
- 핵심만 추출하여 간결하게 만듦

출력:
```
feature/45-add-login-auth

💡 설명이 간결하게 조정되었습니다.
원본: 사용자가 로그인할 때 이메일과 비밀번호를 입력하고...
간결화: add-login-auth
```

## 출력 형식

브랜치명을 생성할 때는 다음 형식으로 출력합니다:

**기본 출력:**
```
refactor/122-seatbooking-query
```

**복사 가능한 명령어 포함:**
```
✅ 추천 브랜치명:
refactor/122-seatbooking-query

📋 브랜치 생성 명령어:
git checkout -b refactor/122-seatbooking-query
```

## 팀 컨벤션

이 가이드는 우리 팀이 다음을 달성하기 위해 정의되었습니다:

- 브랜치의 목적을 한눈에 파악
- 이슈와 브랜치를 자동으로 연결
- Git 히스토리의 일관성 유지
- 팀원들의 협업 효율성 증대

모든 팀 멤버는 이 컨벤션을 따라야 합니다.

## 참고사항

- description 부분은 선택사항이지만, 브랜치의 목적을 명확히 하기 위해 권장됩니다
- description은 최대 3-4개 단어로 제한하는 것이 좋습니다
- 이슈 번호는 반드시 필요하며, GitHub 이슈와 1:1 매칭되어야 합니다
- 브랜치명은 영문 소문자와 하이픈만 사용합니다 (한글 불가)
