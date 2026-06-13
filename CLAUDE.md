# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
    SEAT_NOT_FOUND("좌석을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_001");
}
```

**Validator Pattern** — `@Component`로 `application/validator/`에 `{Domain}Validator` 작성. Service/Facade에 주입되어 실패 시 `BusinessException`을 던진다.

**Response Handling** — 도메인별 `SuccessCode` enum 구현. `GlobalResponseHandler`가 void가 아닌 응답을 자동 래핑한다.

**Entity Base Class** — `BaseEntity` 상속 → `createdAt`/`updatedAt` 자동 (`@MappedSuperclass`).

**Repository Pattern** — 기본 CRUD는 `JpaRepository`. 복잡한 쿼리는 `*QueryRepository` + `JPAQueryFactory` (QueryDSL projection).

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

- 환경: H2 + 임베디드 Redis(포트 63790), 통합 테스트는 `@ServiceTest`
- ⚠️ **테스트 메서드 `@Transactional` 절대 금지** — `@ServiceTest`의 cleanup(`DatabaseCleanupExtension`, `RedisCleanupExtension`)을 우회한다
- 상세 컨벤션 → `.claude/skills/test/SKILL.md` / Helper 빌더 예제 → [docs/testing-guide.md](./docs/testing-guide.md)

## Technology Stack

Java 17, Spring Boot 3.5.0, MySQL (prod/dev), H2 (test), Redis, QueryDSL 5.0.0 (Jakarta), JWT (jjwt 0.11.2), Toss Payments, Spring Batch.

## Git Workflow

- Main branch: `develop`
- CI runs on push/PR to `develop` via GitHub Actions
- ArgoCD syncs from `main` to production
- Performance testing: K6

배포 환경, K8s/ArgoCD/Docker 상세 → [docs/deployment.md](./docs/deployment.md)

## Development Workflow (Skills-based)

새로운 기능/버그 작업 흐름:

1. **계획** — `/superpowers:writing-plans` (이슈 받은 직후, 코드 작성 전)
2. **TDD** — `/superpowers:test-driven-development` (Validator/Calculator/Service/Facade 구현 시)
3. **테스트 작성** — `/test <대상>` (프로젝트의 BDD/Fixture/Helper 컨벤션 자동 적용)
4. **검증** — `/superpowers:verification-before-completion` ("완료" 선언 전 `./gradlew test` 등 증거 확보)
5. **PR** — `/pr` (변경사항 자동 분석 + 이슈 기반 PR 생성)

**버그 발생 시** → 가장 먼저 `/superpowers:systematic-debugging`.
**코드리뷰 받았을 때** → `/superpowers:receiving-code-review`로 맹목적 적용을 방지.

## Situational References

작업 맥락에 따라 다음 문서를 참조한다.

- **테스트 작성/수정 시** → **`/test` skill 호출**. 자동 호출 안 됐다면 명시적으로 `/test <대상>` 실행. 상세 예제는 [docs/testing-guide.md](./docs/testing-guide.md).

- **Lua 스크립트 / 좌석 동시성 작업 시** → [docs/seat-hold-architecture.md](./docs/seat-hold-architecture.md)
  핵심: 원자성 보장, Lazy Cleanup 패턴, Hold Index 3종 키 동시 갱신 (`hold:pendingId`, `holds`, `holding-seats`), `RedisScriptConfig` Bean 등록.

- **좌석 충돌 검증 로직 변경 시** → [docs/seat-conflict-validation.md](./docs/seat-conflict-validation.md)
  핵심: 4-Layer 방어 (Lua → SQL Fail Fast → SQL Re-validation → TTL Expiry) 영향 범위 모두 검토.

- **새 도메인/엔티티 추가, Booking Flow 이해 시** → [docs/domain-model.md](./docs/domain-model.md)
  핵심: 엔티티 관계도, Status enum, 한국어 용어(예약/예매/승차권) 일관 사용.

- **배포/인프라/K8s 작업 시** → [docs/deployment.md](./docs/deployment.md)
  핵심: K8s 매니페스트는 `k8s/`, ArgoCD는 `main` 브랜치 sync, `raillo-secrets`로 시크릿 주입.
