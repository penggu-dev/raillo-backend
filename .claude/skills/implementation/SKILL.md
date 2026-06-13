---
name: planner
description: "issue를 기반으로 구현 가이드 문서를 /docs에 생성하는 skill"
---

# Issue planner Guide Generator

## Overview

이 skill은 issue를 입력받아 구조화된 구현 가이드 문서를 생성합니다.
issue는 사용자가 직접 입력합니다.

### 입력 예시

```
[BUG] PendingBooking이 출발 시간 이후까지 유효한 문제 #147
- PendingBooking TTL이 10분 고정
- 출발 2분 전 예약 시 출발 후 8분까지 노출
```

```
[FEATURE] 통합 열차 조회 API 캐시 적용 #144
- 통합 열차 조회 API에 Redis 캐싱 적용
- DB 조회 성능 향상
```

```
[REFACTOR] Ticket 도메인 리팩토링 #117
- 빌더 패턴 → 정적 팩토리 메서드 변경
- SeatBooking 생성시 Ticket 생성 로직 추가
```

## 필수 사전 단계

**중요: 문서 작성 전 반드시 CLAUDE.md 파일을 먼저 확인하세요.**

```bash
# 프로젝트 루트에서 CLAUDE.md 확인
cat CLAUDE.md
```

CLAUDE.md에서 확인할 사항:
- 프로젝트 아키텍처 및 디렉토리 구조
- 코딩 컨벤션 (빌더 vs 정적 팩토리 메서드 등)
- 테스트 전략 및 도구
- 기술 스택

## 문서 목차 구조

생성되는 문서는 다음 구조를 따릅니다:

```markdown
# [Issue 제목]

## 개요
- Issue 요약
- 문제점/목표 설명
- 관련 도메인/모듈

## 구현 순서

### Phase 1: [단계명]
- 작업 내용
- 수정 대상 파일
- 주의사항

### Phase 2: [단계명]
...

### Phase N: [단계명]
...

## 테스트 코드 작성

### 테스트 케이스 1: [케이스명]
- 테스트 목적
- Given/When/Then
- 예상 결과

### 테스트 케이스 2: [케이스명]
...

## 최종 구현 결과 설명
- 변경 사항 요약
- 영향 범위
- 추가 고려사항
```

## Issue 유형별 가이드

### BUG 이슈
1. 버그 원인 분석 → 수정 범위 파악
2. Phase는 "원인 파악 → 수정 → 사이드이펙트 확인" 순서
3. 테스트 케이스에 "버그 재현 케이스" 필수 포함

### FEATURE 이슈
1. 기능 요구사항 분석
2. Phase는 "설계 → 핵심 구현 → 통합 → 최적화" 순서
3. 테스트 케이스에 "정상 케이스 + 엣지 케이스" 포함

### REFACTOR 이슈
1. 리팩토링 대상 및 목표 파악
2. Phase는 "기존 코드 분석 → 점진적 변경 → 검증" 순서
3. 테스트 케이스에 "기존 동작 보장 테스트" 필수

### TEST 이슈
1. 테스트 대상 및 범위 파악
2. Phase는 "환경 설정 → 테스트 작성 → 검증" 순서
3. 테스트 케이스 상세 명세 필수

## 출력 위치

모든 구현 가이드 문서는 `/docs` 디렉토리에 저장합니다.

파일명 규칙: `{issue-number}-{간단한-제목}.md`
예시: `147-pending-booking-ttl-fix.md`

## 작성 예시

### 입력 Issue

```
[BUG] PendingBooking이 출발 시간 이후까지 유효한 문제 #147
- PendingBooking TTL이 10분 고정
- 출발 2분 전 예약 시 출발 후 8분까지 노출
```

### 출력 문서 (147-pending-booking-ttl-fix.md)

```markdown
# [BUG] PendingBooking TTL 수정 #147

## 개요

### 문제 상황
현재 PendingBooking TTL이 10분으로 고정되어 있어, 출발 시간이 10분 미만 남은 경우에도 
10분 동안 유효합니다.

### 문제점
- 출발 2분 전 예약 생성 → TTL 10분 → 출발 후 8분까지 "내 예약"에 노출
- 이미 출발한 열차의 PendingBooking이 사용자에게 보임
- 해당 PendingBooking으로 결제 시도 가능

### 관련 도메인
- PendingBooking
- Hold 키 관리

## 구현 순서

### Phase 1: TTL 계산 로직 수정
- **작업 내용**: PendingBooking 생성 시 TTL을 `min(기본TTL, 출발시간까지 남은 시간)`으로 계산
- **수정 파일**: `PendingBookingService.java`, `PendingBooking.java`
- **주의사항**: 기존 TTL 상수는 유지하고, 계산 로직만 추가

### Phase 2: Hold 키 TTL 동기화
- **작업 내용**: Hold 키 TTL도 PendingBooking TTL 기준으로 조정
- **수정 파일**: `HoldKeyManager.java`
- **주의사항**: 현재 PendingBooking TTL + 1분 규칙 유지

### Phase 3: 검증 및 정리
- **작업 내용**: 전체 플로우 테스트 및 코드 정리
- **확인 사항**: 출발 직전 예약 시나리오 검증

## 테스트 코드 작성

### 테스트 케이스 1: 출발 시간이 기본 TTL보다 긴 경우
- **목적**: 기존 동작 유지 확인
- **Given**: 출발까지 30분 남음, 기본 TTL 10분
- **When**: PendingBooking 생성
- **Then**: TTL = 10분

### 테스트 케이스 2: 출발 시간이 기본 TTL보다 짧은 경우
- **목적**: 버그 수정 확인
- **Given**: 출발까지 5분 남음, 기본 TTL 10분
- **When**: PendingBooking 생성
- **Then**: TTL = 5분

### 테스트 케이스 3: Hold 키 TTL 연동 확인
- **목적**: Hold 키가 PendingBooking보다 1분 길게 설정되는지 확인
- **Given**: 출발까지 5분 남음
- **When**: PendingBooking 및 Hold 키 생성
- **Then**: Hold 키 TTL = 6분

## 최종 구현 결과 설명

### 변경 사항
- PendingBooking TTL 계산 로직 추가
- Hold 키 TTL 연동 로직 수정

### 영향 범위
- 예약 생성 플로우
- Redis TTL 설정

### 추가 고려사항
- 기존 PendingBooking 데이터는 자연 만료 대기
- 모니터링: 출발 시간 이후 PendingBooking 조회 시도 로깅 추가 고려
```

## 체크리스트

문서 작성 전:
- [ ] CLAUDE.md 파일 확인 완료
- [ ] Issue 유형 파악 (BUG/FEATURE/REFACTOR/TEST)
- [ ] 관련 도메인/모듈 식별

문서 작성 시:
- [ ] 개요에 문제/목표 명확히 기술
- [ ] Phase별 수정 대상 파일 명시
- [ ] 테스트 케이스에 Given/When/Then 포함
- [ ] 최종 결과에 영향 범위 기술

문서 작성 후:
- [ ] /docs 디렉토리에 저장
- [ ] 파일명 규칙 준수
