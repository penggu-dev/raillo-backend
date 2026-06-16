---
name: issue
description: 작업 내용을 받아 팀 GitHub Issue 컨벤션에 맞춘 제목·본문·라벨을 텍스트로 생성합니다. Use when the user asks for an issue draft, like "이슈 작성해줘", "/issue [FEATURE] xxx", "버그 이슈 만들어줘".
---

# GitHub Issue Generator

작업 내용을 받아 팀 컨벤션에 맞춘 Issue 텍스트를 생성한다. **gh CLI는 사용하지 않는다** — 출력 텍스트를 사용자가 직접 GitHub UI에 붙여넣는다.

## 절대 규칙

- 모든 텍스트는 **한국어**로 작성
- 제목 형식: `[CATEGORY] 설명` (반드시 대괄호 + 공백)
- 라벨은 **정확히 1개**만 지정 (CATEGORY와 일치)
- 본문에 3개 섹션 헤더 필수 (Bug는 4개)
- 섹션 헤더에 이모지 사용 (`📄`, `📈`, `🖥️`, `👍🏻`)

## CATEGORY → 라벨 매핑

| CATEGORY | 라벨 | 용도 |
|---------|------|------|
| FEATURE | feature | 새 기능 |
| BUG | bug | 버그 |
| REFACTOR | refactor | 리팩터링 |
| CHORE | chore | 빌드/설정/의존성 |
| TEST | test | 테스트 작성/수정 |
| DOCS | docs | 문서 |

## 본문 템플릿

### FEATURE / REFACTOR / CHORE / TEST / DOCS

```markdown
### 📄 작업 설명
{1~3 문장으로 무엇을 왜 하는지 명확히}

### 📈 진행 체크리스트
- [ ] {작업 항목 1}
- [ ] {작업 항목 2}
- [ ] {작업 항목 3}

### 👍🏻 추가 정보
{관련 이슈/PR 링크 또는 _No response_}
```

### BUG (4 섹션)

```markdown
### 🖥️ 발생 환경
- {예: Spring Boot 3.5.0}
- {예: dev 프로필, 로컬 환경}

### 📄 버그 설명
{현상 + 기대 동작과의 차이}

### 📈 재현 절차
1. {단계 1}
2. {단계 2}
3. {기대 결과 vs 실제 결과}

### 👍🏻 추가 정보
{관련 이슈/PR 링크 또는 _No response_}
```

## Workflow

1. 사용자 입력에서 CATEGORY를 식별 (명시 안 됐으면 묻는다)
2. 제목을 `[CATEGORY] {짧은 한국어 설명}` 형식으로 생성
3. 입력 내용을 본문 템플릿에 매핑:
   - 작업 항목이 명시되어 있으면 그대로 체크리스트로
   - 없으면 placeholder 2~3개 제시 (사용자가 채우도록)
4. 매핑되는 라벨 1개를 명시
5. 출력 끝에 "GitHub UI에 붙여넣으세요" 안내

## 출력 형식

```
✅ Issue 초안

📌 제목:
[FEATURE] 통합 열차 조회 API 캐시 적용

🏷️ 라벨: feature

📝 본문:
---
### 📄 작업 설명
통합 열차 조회 API에 Redis 캐싱을 적용해 DB 부하를 줄이고 응답 속도를 개선한다.

### 📈 진행 체크리스트
- [ ] 캐시 키 설계 (열차번호 + 운행일)
- [ ] TrainSearchService에 캐시 어노테이션 적용
- [ ] 캐시 무효화 정책 결정 (TTL/이벤트)
- [ ] 캐시 hit/miss 메트릭 추가

### 👍🏻 추가 정보
_No response_
---

💡 위 내용을 GitHub Issue 작성 페이지에 복사·붙여넣기 하세요.
```

## 사용 예시

### 예시 1: FEATURE (체크리스트 명시)

입력:
```
/issue FEATURE 열차 조회 캐시 적용. 작업: 캐시 키 설계, 어노테이션 적용, 무효화 정책
```

→ 위 출력 형식대로 생성. 체크리스트는 입력 그대로.

### 예시 2: BUG

입력:
```
/issue BUG PendingBooking이 출발 시간 이후까지 유효함. 환경: dev. 재현: 출발 2분 전 예약 → 출발 후 8분까지 노출
```

출력 (본문만):
```
### 🖥️ 발생 환경
- Spring Boot 3.5.0
- dev 프로필, 로컬 환경

### 📄 버그 설명
PendingBooking TTL이 10분 고정이라 출발 시간이 지나도 사용자 화면에 노출된다.

### 📈 재현 절차
1. 출발 2분 전 예약 생성
2. 출발 시간 도달
3. 기대: PendingBooking이 사라짐 / 실제: 출발 후 8분까지 "내 예약"에 노출

### 👍🏻 추가 정보
_No response_
```

## 예외 처리

### CATEGORY 미지정

```
⚠️ CATEGORY를 명시해주세요.

사용 가능: FEATURE, BUG, REFACTOR, CHORE, TEST, DOCS

예시: /issue FEATURE 사용자 로그인
```

### 본문 정보 부족

체크리스트 항목이나 버그 재현 단계가 추출 불가능하면 placeholder를 채워 출력하고 사용자에게 보완 안내:

```
💡 placeholder({1}, {2})는 GitHub UI에 붙여넣기 전에 채워주세요.
```
