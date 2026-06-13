---
name: commit
description: 현재 브랜치의 작업 내용을 읽고 브랜치명에서 이슈번호를 파악하여 자동으로 커밋 메시지를 작성합니다.
context: inline
allowed-tools: [Bash(git *)]
---

# Git Commit Convention Guide

팀의 커밋 메시지 컨벤션을 정의합니다. 모든 커밋 메시지는 이 가이드를 따라야 합니다.

## 커밋 타입 정의

| 타입 | 설명 | 예시 |
|------|------|------|
| feat | 새로운 기능 또는 기존 기능 관련 | feat: Ticket 사용 가능한 상태인지 검증하는 도메인 메서드 추가 (#95) |
| bug | 버그 관련 | bug: 예매 취소 시 상태 업데이트 실패 (#105) |
| refactor | 리팩터링 관련 | refactor: 예약 응답으로 보내지는 DTO 네이밍 변경 (#102) |
| test | 테스트 관련 | test: 예약 관련 금액 테스트 코드 작성 (#120) |
| chore | 빌드, 패키지 매니저 관련 | chore: k6 부하 테스트 스크립트 추가 (#126) |
| docs | 문서 관련 | docs: API 엔드포인트 사용 설명서 추가 (#110) |

## 커밋 메시지 형식
```
type: description (#issue-number)
```

### 형식 규칙

1. **type**: 6가지 타입 중 하나 (소문자)
2. **description**: 명확하고 간결한 설명
    - 한글 사용
    - 첫 글자 대문자
    - 마침표 없음
    - 50자 이내 권장
3. **issue-number**: 관련 이슈 번호 (#포함)
4. **실행 모드**: 기본적으로 메시지만 제안, 명시적 요청 시 커밋 실행
5. **Co-Authored-By 금지**: 커밋 메시지에 `Co-Authored-By` 라인을 절대 포함하지 않는다

## 올바른 예시
```
feat: Ticket 상태 검증을 도메인 엔티티 내부로 통합 (#117)
refactor: SeatBooking 엔티티 역정규화 (#122)
test: 환불 관련 테스트코드 추가 (#84)
bug: 예매 취소 시 상태 업데이트 실패 수정 (#105)
chore: k6 부하 테스트 스크립트 추가 (#126)
docs: 예매 API 명세 문서 작성 (#130)
```

## 잘못된 예시
```
Feat: 기능 추가 (#117)           ❌ 타입은 소문자
feat 기능 추가 (#117)            ❌ 콜론(:) 누락
feat: 기능 추가. (#117)          ❌ 마침표 사용
feat: 기능 추가 117              ❌ # 누락
feat: 기능 추가                  ❌ 이슈 번호 누락
기능 추가 (#117)                 ❌ 타입 누락
```

## 사용 방법

### 자동 모드: 현재 브랜치 기반 커밋 메시지 생성

현재 브랜치명에서 이슈번호와 타입을 자동으로 파악하고, 작업 내용을 받아서 커밋 메시지를 생성합니다.

입력:
```
커밋 메시지 추천해줘
```

Claude의 처리 과정:
1. `git branch --show-current` 명령으로 현재 브랜치명 확인
2. 브랜치명에서 정보 추출:
    - 타입: refactor/102-optimize-query → refactor
    - 이슈번호: refactor/102-optimize-query → 102
    - 설명 힌트: optimize-query
3. 사용자의 작업 내용(또는 git diff)를 분석
4. 커밋 메시지 생성

출력:
```
✅ 추천 커밋 메시지:
refactor: 사용자 조회 쿼리 최적화 (#102)

💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

## 브랜치명에서 정보 추출

이 Skill은 브랜치명에서 다음 정보를 자동으로 추출합니다:
```
label/issue-number[-description]
```

### 브랜치 라벨 → 커밋 타입 매핑

| 브랜치 라벨 | 커밋 타입 |
|-----------|----------|
| feature | feat |
| bug | bug |
| refactor | refactor |
| test | test |
| chore | chore |
| docs | docs |

### 추출 예시
```
브랜치: feature/45-add-login
  → 타입: feat
  → 이슈: #45
  → 힌트: add-login

브랜치: refactor/122-seatbooking-query
  → 타입: refactor
  → 이슈: #122
  → 힌트: seatbooking-query

브랜치: bug/88
  → 타입: bug
  → 이슈: #88
  → 힌트: 없음
```

## 상세 사용 예시

### 예시 1: 기본 사용 (자동 브랜치 감지)

**현재 브랜치:** `refactor/102-optimize-user-query`

입력:
```
커밋 메시지 추천해줘
```

Claude의 처리:
```bash
$ git branch --show-current
refactor/102-optimize-user-query
```

출력:
```
✅ 추천 커밋 메시지:
refactor: 사용자 조회 쿼리 최적화 (#102)

📌 현재 브랜치: refactor/102-optimize-user-query
💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

### 예시 2: 작업 내용과 함께 요청

**현재 브랜치:** `feature/45-add-login`

입력:
```
로그인 기능 완성했는데 커밋 메시지 만들어줄 수 있어?
```

Claude의 처리:
- 브랜치 확인: feature/45-add-login
- 사용자 설명 반영: 로그인 기능

출력:
```
✅ 추천 커밋 메시지:
feat: 로그인 기능 추가 (#45)

📌 현재 브랜치: feature/45-add-login
💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

### 예시 3: 복잡한 작업 설명

**현재 브랜치:** `refactor/122-seatbooking-query`

입력:
```
SeatBooking 엔티티를 역정규화했고, 그에 따라 좌석 조회 쿼리를 수정했어. 커밋 메시지 만들어줘
```

Claude의 처리:
- 브랜치 확인: refactor/122-seatbooking-query
- 작업 내용 분석 및 요약

출력:
```
✅ 추천 커밋 메시지:
refactor: SeatBooking 엔티티 역정규화로 인한 좌석 조회 쿼리 수정 (#122)

📌 현재 브랜치: refactor/122-seatbooking-query
💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

### 예시 4: 짧은 요청

**현재 브랜치:** `test/51-add-payment-tests`

입력:
```
커밋
```

Claude의 처리:
- git diff로 변경사항 확인
- 자동으로 설명 생성

출력:
```
✅ 추천 커밋 메시지:
test: 결제 관련 테스트 코드 추가 (#51)

📌 현재 브랜치: test/51-add-payment-tests
💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

### 예시 5: 버그 수정

**현재 브랜치:** `bug/88-fix-cache-update`

입력:
```
캐시 업데이트 로직 수정 완료. 커밋 메시지 추천
```

출력:
```
✅ 추천 커밋 메시지:
bug: 사용자 프로필 조회 시 캐시 업데이트 오류 수정 (#88)

📌 현재 브랜치: bug/88-fix-cache-update
💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

## 커밋 실행 모드

### 메시지만 생성 (기본 모드)

기본적으로는 커밋 메시지만 제안하고 실제 커밋은 수행하지 않습니다.

입력:
```
커밋 메시지 추천해줘
```

출력:
```
✅ 추천 커밋 메시지:
refactor: SeatBooking 엔티티 역정규화 (#122)

📋 커밋 명령어:
git commit -m "refactor: SeatBooking 엔티티 역정규화 (#122)"

💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

### 커밋 실행 모드

사용자가 명시적으로 요청하면 실제 커밋을 수행합니다.

입력:
```
커밋 실행해줘
```

또는

입력:
```
커밋 메시지 만들고 바로 커밋해줘
```

Claude의 처리:
```bash
$ git add .
$ git commit -m "refactor: SeatBooking 엔티티 역정규화 (#122)"
```

출력:
```
✅ 커밋 완료!

커밋 메시지: refactor: SeatBooking 엔티티 역정규화 (#122)
브랜치: refactor/122-seatbooking-query
커밋 해시: a3f5d8c

📌 다음 단계:
git push origin refactor/122-seatbooking-query
```

### 스테이징 옵션

입력:
```
변경된 파일 전부 스테이징하고 커밋해줘
```

Claude의 처리:
```bash
$ git add -A
$ git commit -m "refactor: SeatBooking 엔티티 역정규화 (#122)"
```

입력:
```
특정 파일만 커밋: src/domain/SeatBooking.java만 커밋해줘
```

Claude의 처리:
```bash
$ git add src/domain/SeatBooking.java
$ git commit -m "refactor: SeatBooking 엔티티 역정규화 (#122)"
```

## 예외 상황 처리

### 1. 브랜치명이 컨벤션을 따르지 않는 경우

**케이스 A: main/develop 브랜치**

**현재 브랜치:** `main`

입력:
```
커밋 메시지 추천해줘
```

출력:
```
⚠️ 현재 브랜치가 main/develop입니다.

작업 브랜치를 먼저 생성해주세요.
브랜치 생성 예시:
- git checkout -b feature/123-description
- git checkout -b bug/45-fix-issue

💡 branch skill을 사용하면 브랜치명을 자동으로 생성할 수 있습니다.
```

**케이스 B: 이슈 번호가 없는 브랜치**

**현재 브랜치:** `my-feature`

입력:
```
커밋 메시지 추천해줘
```

출력:
```
⚠️ 현재 브랜치명에 이슈 번호가 없습니다.

브랜치명: my-feature

올바른 형식: label/issue-number[-description]
예시:
- feature/45-add-login
- bug/88-fix-error
- refactor/102-optimize-query

💡 GitHub 이슈를 먼저 생성하고, 올바른 브랜치명으로 다시 시작하시거나
   이슈 번호를 직접 알려주세요.
```

**케이스 C: 알 수 없는 라벨**

**현재 브랜치:** `hotfix/45-urgent-fix`

입력:
```
커밋 메시지 추천해줘
```

출력:
```
⚠️ 브랜치 라벨 'hotfix'는 정의되지 않았습니다.

사용 가능한 라벨:
- feature → feat
- bug → bug
- refactor → refactor
- test → test
- chore → chore
- docs → docs

어떤 커밋 타입으로 처리할까요?
1. bug (버그 수정)
2. chore (긴급 작업)
3. 직접 입력
```

### 2. 변경사항이 없는 경우

입력:
```
커밋 실행해줘
```

Claude의 처리:
```bash
$ git status
```

출력:
```
⚠️ 커밋할 변경사항이 없습니다.

현재 상태: 작업 트리가 깨끗합니다.

💡 파일을 수정한 후 다시 시도해주세요.
```

### 3. 병합 충돌이 있는 경우

입력:
```
커밋 실행해줘
```

출력:
```
⚠️ 병합 충돌이 감지되었습니다.

충돌 파일:
- src/domain/SeatBooking.java
- src/service/ReservationService.java

💡 충돌을 해결한 후 다시 시도해주세요.
   1. 충돌 파일을 수정
   2. git add <file>
   3. 다시 커밋 요청
```

### 4. 이슈 번호를 찾을 수 없는 경우

**현재 브랜치:** `feature/add-login` (이슈 번호 없음)

입력:
```
커밋 메시지 추천해줘
```

출력:
```
⚠️ 이슈 번호를 확인할 수 없습니다.

현재 브랜치: feature/add-login

이슈 번호를 알려주시겠어요?
예: "이슈 45번으로 커밋해줘"

또는 올바른 브랜치명으로 다시 시작:
git checkout -b feature/45-add-login
```

사용자가 이슈 번호 제공:
```
이슈 45번으로 커밋해줘
```

출력:
```
✅ 추천 커밋 메시지:
feat: 로그인 기능 추가 (#45)
```

### 5. git이 설치되지 않은 경우

출력:
```
❌ Git이 설치되지 않았거나 Git 저장소가 아닙니다.

확인 사항:
1. Git이 설치되어 있나요?
   - 설치: https://git-scm.com/downloads

2. 현재 디렉토리가 Git 저장소인가요?
   - git init 또는
   - git clone <repository-url>
```

## Git Diff 분석

작업 내용을 설명하지 않으면, git diff를 분석하여 자동으로 커밋 메시지를 생성합니다.

**현재 브랜치:** `feature/45-add-login`

**변경된 파일:**
```diff
+ src/auth/LoginService.java (새 파일)
+ src/auth/LoginController.java (새 파일)
M src/config/SecurityConfig.java (수정)
```

입력:
```
커밋 메시지 추천해줘
```

Claude의 처리:
```bash
$ git diff --cached --name-status
$ git diff --name-status
```

출력:
```
✅ 추천 커밋 메시지:
feat: 로그인 기능 추가 (#45)

📝 변경된 파일:
- src/auth/LoginService.java (신규)
- src/auth/LoginController.java (신규)
- src/config/SecurityConfig.java (수정)

💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

## 고급 기능

### 여러 커밋 메시지 제안

복잡한 변경사항의 경우, 여러 커밋 메시지를 제안할 수 있습니다.

입력:
```
여러 개로 나눠서 커밋 메시지 추천해줘
```

출력:
```
✅ 추천 커밋 메시지 (3개):

1️⃣ refactor: SeatBooking 엔티티 역정규화 (#122)
   - src/domain/SeatBooking.java

2️⃣ refactor: 좌석 조회 쿼리 최적화 (#122)
   - src/repository/SeatRepository.java
   - src/service/SeatService.java

3️⃣ test: SeatBooking 관련 테스트 업데이트 (#122)
   - src/test/SeatBookingTest.java

💡 각 커밋을 순서대로 실행하려면:
   "1번 커밋 실행해줘"
   "2번 커밋 실행해줘"
   "3번 커밋 실행해줘"
```

### 커밋 메시지 수정

제안된 메시지가 마음에 들지 않으면 수정 요청:

입력:
```
더 구체적으로 만들어줘
```

또는

입력:
```
"역정규화" 부분을 "Booking과 Seat 관계 최적화"로 바꿔줘
```

출력:
```
✅ 수정된 커밋 메시지:
refactor: SeatBooking과 Seat 관계 최적화로 인한 조회 쿼리 수정 (#122)
```

## 출력 형식

### 기본 출력
```
✅ 추천 커밋 메시지:
refactor: SeatBooking 엔티티 역정규화 (#122)

📌 현재 브랜치: refactor/122-seatbooking-query
💡 커밋을 실행하려면 "커밋 실행해줘"라고 말씀해주세요.
```

### 실행 후 출력
```
✅ 커밋 완료!

커밋 메시지: refactor: SeatBooking 엔티티 역정규화 (#122)
브랜치: refactor/122-seatbooking-query
커밋 해시: a3f5d8c

📌 다음 단계:
git push origin refactor/122-seatbooking-query
```

## 팀 컨벤션

이 가이드는 우리 팀이 일관된 커밋 히스토리를 유지하기 위해 정의되었습니다.

### 목표
- 커밋 메시지로 변경 내용을 명확히 파악
- 이슈와 커밋을 자동으로 연결
- Git 히스토리의 일관성 유지
- 코드 리뷰 효율성 증대

모든 팀 멤버는 이 컨벤션을 따라야 합니다.

## 참고사항

- 커밋 메시지는 변경사항을 명확하게 설명해야 합니다
- 한 커밋에는 하나의 논리적 변경사항만 포함하는 것이 좋습니다
- 너무 많은 파일을 한 번에 커밋하지 마세요
- 의미 있는 단위로 커밋을 분리하세요
- 이슈 번호는 반드시 포함되어야 합니다
```
