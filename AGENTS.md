# AGENTS.md

This file provides guidance to coding agents (Claude Code, Codex 등) when working with code in this repository. `CLAUDE.md`는 이 파일에 대한 심볼릭 링크다.

## Build & Test

```bash
./gradlew build                                              # Build
./gradlew test                                               # All tests
./gradlew test --tests "com.sudo.raillo.booking.BookingServiceTest"  # Single class
./gradlew test --tests "...BookingServiceTest.method_name"   # Single method
./gradlew clean build                                        # Clean rebuild
docker-compose up -d && ./gradlew bootRun                    # Run app (MySQL + Redis required)
```

## Architecture

Spring Boot 3.5 + Java 17 + DDD. Domains:
- `auth` — JWT 인증, 이메일 인증, 토큰 관리
- `booking` — Pending bookings, 좌석 예약, 승차권
- `member` — 사용자, 회원번호 생성, 만료 회원 Spring Batch
- `payment` — Toss Payments, 환불
- `train` — 열차 스케줄, 역, 운임, 좌석 가용성
- `order` — 결제 단위로 PendingBooking들을 묶음
- `global` — 공통 인프라 (config, exceptions, Redis 유틸)

### Package Structure

```
{domain}/
├── presentation/        # REST controllers
├── application/
│   ├── service/         # Business logic
│   ├── facade/          # Coordinates multiple services
│   ├── dto/{request,response,projection}/
│   ├── mapper/          # DTO ↔ Domain
│   ├── validator/       # Business rule validation
│   ├── calculator/      # Fares, refunds
│   └── generator/       # Code generators
├── domain/              # Entities, enums
├── infrastructure/      # Repositories (JPA, QueryDSL, Redis)
├── exception/           # Domain-specific error codes
├── success/             # Domain-specific success codes
└── docs/                # Swagger documentation interfaces
```

### Layer Rules

```
Controller → Facade → Service → Repository
```

- **Facade → Service only** (Facade → Facade 금지; 등장 시 Event-driven 검토)
- **Service → Repository** (own or cross-domain 모두 허용)
- **Service → Service 호출 금지**

## Key Patterns

**Exception Handling** — 3종 분리:
- `BusinessException` — Service/Application 레이어의 비즈니스 로직 오류 (검증 실패, 리소스 없음)
- `DomainException` — Entity/VO 내부 도메인 불변식 위반 (상태 전이 오류, VO 검증)
- `ExternalApiException` — 외부 API 호출 실패 (Toss Payments 등)

도메인별 에러 enum은 `ErrorCode`를 구현한다:
```java
public enum BookingError implements ErrorCode {
    BOOKING_NOT_FOUND("예매 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_101");
}
```

> 코드 형식·접두사·밴드·추가 절차 → [docs/error-code-convention.md](./docs/error-code-convention.md)

**Validator Pattern** — `@Component`로 `application/validator/`에 `{Domain}Validator` 작성. Service/Facade에 주입되어 실패 시 `BusinessException`을 던진다.

**Response Handling** — 도메인별 `SuccessCode` enum 구현. `GlobalResponseHandler`가 void가 아닌 응답을 자동 래핑한다.

**Entity Base Class** — `BaseEntity` 상속 → `createdAt`/`updatedAt` 자동 (`@MappedSuperclass`).

**Repository Pattern** — 기본 CRUD는 `JpaRepository`. 복잡한 쿼리는 `*QueryRepository` + `JPAQueryFactory` (QueryDSL projection). 둘 다 `{domain}/infrastructure/` 직속에 둔다 (별도 `repository/` 하위 디렉터리 두지 않음).

**Redis Repository Pattern** — `RedisTemplate<String, Object>`, TTL은 `@Value`로 주입. 커서 순회는 `ScanOptions`.

**Redis Lua Scripts** — 좌석 동시 선점 충돌 방지. 스크립트는 `src/main/resources/scripts/`:
- `seat_hold.lua` / `seat_release.lua` / `get_hold_seats_count.lua`
- 상세 흐름, Hold Index, Lazy Cleanup, Train Search 통합 → [docs/seat-hold-architecture.md](./docs/seat-hold-architecture.md)
- 4-Layer 좌석 충돌 방어 → [docs/seat-conflict-validation.md](./docs/seat-conflict-validation.md)

## Coding Rules

**Parameter Passing**:
- ≤3 parameters: 개별 전달 (Service 재사용성 ↑)
- ≥4 parameters: Request 객체로 묶기

**Transactions**:
- Service 클래스: `@Transactional` 클래스 레벨
- 읽기 전용 메서드: `@Transactional(readOnly = true)`
- 조회 전용 Service: 클래스 레벨 `@Transactional(readOnly = true)`

## Domain Model

전체 엔티티 목록, 관계도, Booking Flow, 한국어 도메인 용어 → [docs/domain-model.md](./docs/domain-model.md)

### Status Enums (요약)

| Entity | Status | Values |
|--------|--------|--------|
| Booking | `BookingStatus` | BOOKED, CANCELLED |
| Order | `OrderStatus` | PENDING, ORDERED, EXPIRED |
| Payment | `PaymentStatus` | PENDING, PAID, CANCELLED, REFUNDED, FAILED |
| Ticket | `TicketStatus` | ISSUED, USED, CANCELLED |
| TrainSchedule | `OperationStatus` | ACTIVE, DELAYED, CANCELLED |

### Other Enums

- `PassengerType` — ADULT (0%), CHILD (40% off), INFANT (75% off), SENIOR (30% off), DISABLED_HEAVY (50% off), DISABLED_LIGHT (30% off), VETERAN (50% off)
- `CarType` — STANDARD (일반실), FIRST_CLASS (특실)
- `SeatType` — WINDOW (창측), AISLE (복도측)

### Korean Terminology

예약 = PendingBooking, 예매 = Booking, 승차권 = Ticket, 객차 = TrainCar, 정차역 = ScheduleStop

## Testing

테스트 작성/수정은 **`/test` skill**을 사용한다. 컨벤션과 워크플로우 전체가 skill에 포함되어 있다.

- 환경: H2 + 임베디드 Redis(포트 **63790** — 로컬 개발용 Redis 기본 포트 6379와 충돌 회피), 통합 테스트는 `@ServiceTest`
- ⚠️ **테스트 메서드 `@Transactional` 절대 금지** — `@ServiceTest`의 cleanup(`DatabaseCleanupExtension`, `RedisCleanupExtension`)을 우회한다
- 상세 컨벤션 → `.agents/skills/test/SKILL.md` / Helper 빌더 예제 → [docs/testing-guide.md](./docs/testing-guide.md)

## Technology Stack

Java 17, Spring Boot 3.5.0, MySQL (prod/dev), H2 (test), Redis, QueryDSL 5.0.0 (Jakarta), JWT (jjwt 0.11.2), Toss Payments, Spring Batch.

## Git Workflow

- Main branch: `develop`
- CI runs on push/PR to `develop` via GitHub Actions
- ArgoCD syncs from `main` to production
- Performance testing: K6

**이슈/브랜치/커밋/PR 흐름** — 모두 프로젝트 skill로 자동화한다:
1. `/issue <작업 내용>` — 팀 GitHub Issue 컨벤션에 맞춘 제목·본문·라벨 생성
2. `/branch <이슈 설명>` — 팀 네이밍 컨벤션에 맞춘 브랜치명 생성
3. `/commit` — 브랜치명에서 이슈번호 파싱 + 변경사항 분석해 커밋 메시지 작성
4. `/pr` — 변경사항 분석 + 이슈 본문 기반 PR 생성

배포 환경, K8s/ArgoCD/Docker 상세 → [docs/deployment.md](./docs/deployment.md)

## Development Workflow (superpowers 기반)

**팀 표준 워크플로우. `superpowers` 시리즈를 모든 작업의 기본으로 사용한다.** 프로젝트 커스텀 skill(`/test`, `/pr`, `/issue`, `/validator`, `/api-doc` 등)은 superpowers와 결합해 사용한다.

1. **이슈 생성** — `/issue <작업 내용>` (팀 GitHub Issue 컨벤션에 맞춘 제목·본문·라벨 생성)
2. **요구사항 탐색** — `superpowers:brainstorming` (새 기능/컴포넌트/동작 변경 전, 요구사항이 모호할 때 사용자와 합의 형성)
3. **계획** — `superpowers:writing-plans` (요구사항 합의 후, 코드 작성 전 — brainstorming 결과를 실행 가능한 계획으로 변환)
4. **구현** — `/branch <이슈>`로 브랜치 생성 후 도메인별 skill(`/validator`, `/api-doc` 등) 결합. 본 프로젝트는 **구현 → 테스트 순서**를 표준으로 한다.
5. **테스트 작성** — `/test <대상>` (BDD/Fixture/Helper 컨벤션 자동 적용; 단순 설정/문서 변경 등은 생략 가능)
6. **검증** — `superpowers:verification-before-completion` ("완료" 선언 전 `./gradlew test` 등 증거 확보)
7. **문서 반영** — 작업으로 바뀐 내용을 관련 문서에 반영한다. 영향받는 기존 문서(`AGENTS.md`, `README.md`, `docs/*`)를 수정하고, 새 도메인·아키텍처·배포 변경처럼 기존 문서로 담기 어려우면 적절한 문서를 새로 생성한다. 변경 문서는 같은 PR에 포함한다.
8. **PR** — `/commit`으로 커밋 후 `/pr` 실행 (변경사항 자동 분석 + 이슈 기반 PR 생성)

**버그 발생 시** → 가장 먼저 `superpowers:systematic-debugging`.
**코드리뷰 받았을 때** → `superpowers:receiving-code-review`로 맹목적 적용을 방지.
**병렬 작업 가능 시** → `superpowers:dispatching-parallel-agents`.

## Situational References

작업 맥락에 따라 다음 문서를 참조한다.

- **테스트 작성/수정 시** → **`/test` skill 호출**. 자동 호출 안 됐다면 명시적으로 `/test <대상>` 실행. 상세 예제는 [docs/testing-guide.md](./docs/testing-guide.md).

- **Lua 스크립트 / 좌석 동시성 작업 시** → [docs/seat-hold-architecture.md](./docs/seat-hold-architecture.md)
  핵심: 원자성 보장, Lazy Cleanup 패턴, Hold Index 3종 키 동시 갱신 (`hold:pendingId`, `holds`, `holding-seats`), `RedisScriptConfig` Bean 등록.

- **좌석 충돌 검증 로직 변경 시** → [docs/seat-conflict-validation.md](./docs/seat-conflict-validation.md)
  핵심: 4-Layer 방어 (Lua → SQL Fail Fast → SQL Re-validation → TTL Expiry) 영향 범위 모두 검토.

- **에러 코드 추가/변경 시** → [docs/error-code-convention.md](./docs/error-code-convention.md)
  핵심: `{DOMAIN}_{NNN}` 형식, 도메인별 카테고리 밴드(백의 자리), 새 코드 추가 절차.

- **새 도메인/엔티티 추가, Booking Flow 이해 시** → [docs/domain-model.md](./docs/domain-model.md)
  핵심: 엔티티 관계도, Status enum, 한국어 용어(예약/예매/승차권) 일관 사용.

- **배포/인프라/K8s 작업 시** → [docs/deployment.md](./docs/deployment.md)
  핵심: K8s 매니페스트는 `k8s/k8s-application`·`k8s-argocd`·`k8s-monitoring`, ArgoCD는 `main` 브랜치 sync, 환경변수는 `raillo-config`(ConfigMap)·`raillo-secrets`(Secret)를 `envFrom`으로 주입.
