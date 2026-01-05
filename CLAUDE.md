# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Raillo is a KORAIL (Korean Railway) clone project - a train reservation platform built with Spring Boot. The project implements core features of a real train booking system including authentication, train schedule search, seat reservation, payment, and ticketing.

## Build & Development Commands

```bash
# Build
./gradlew build

# Run application (requires MySQL and Redis)
./gradlew bootRun

# Run all tests (uses H2 in-memory DB and embedded Redis)
./gradlew test

# Run a single test class
./gradlew test --tests "com.sudo.raillo.booking.application.BookingServiceTest"

# Run a single test method
./gradlew test --tests "com.sudo.raillo.booking.application.BookingServiceTest.testMethodName"

# Clean QueryDSL generated classes
./gradlew clean
```

## Architecture

### Domain-Driven Design Structure

Each domain follows this layered structure:
```
src/main/java/com/sudo/raillo/{domain}/
├── application/          # Business logic (services, facades, DTOs, mappers, validators)
├── domain/               # Entities, value objects, enums
├── docs/                 # Swagger documentation interfaces
├── exception/            # Domain-specific error codes (implements ErrorCode)
├── infrastructure/       # Repositories (JPA, Redis, QueryDSL)
├── presentation/         # REST controllers
└── success/              # Domain-specific success codes (implements SuccessCode)
```

### Domains

- **auth**: JWT authentication, email verification (Redis-based code storage)
- **member**: User management, member number generation (`yyyyMMddCCCC` format via Redis counter)
- **train**: Train schedules, stations, cars, seats, fare calculation
- **booking**: Cart, pending bookings (Redis TTL), confirmed bookings, seat booking, tickets
- **order**: Order management linking bookings and payments
- **payment**: Payment processing, Toss Payments integration
- **global**: Cross-cutting concerns (exception handling, response wrappers, configs)

### Key Architectural Patterns

**Facade Pattern**: Complex operations use Facade classes to coordinate multiple services
- `TrainSearchFacade`: Orchestrates train search with seat availability calculation
- `BookingFacade`: Coordinates booking cancellation across services
- `PendingBookingFacade`: Manages temporary booking flow
- `PaymentFacade`: Handles payment flow coordination

**Response Handling**:
- Success: Return `SuccessResponse.of(SuccessCode, data)` - GlobalResponseHandler sets HTTP status
- Error: Throw `BusinessException(ErrorCode)` - handled by global exception handler
- Each domain defines its own `XxxError` enum implementing `ErrorCode` and `XxxSuccess` enum implementing `SuccessCode`

**Validation**: Use `@Valid` on request DTOs. Complex business validation goes in `*Validator` classes within application layer.

## Testing

### Test Infrastructure

- **@ServiceTest**: Custom annotation for integration tests (combines @SpringBootTest with extensions)
- **Extensions**: `RedisServerExtension` (embedded Redis on port 63790), `DatabaseCleanupExtension`, `RedisCleanupExtension`
- **Test profile**: `application-test.yml` with H2 and embedded Redis

### Fixtures and Helpers

```java
// Fixtures: Builder pattern for test entities
Member member = MemberFixture.builder()
    .withEmail("test@example.com")
    .withName("tester")
    .build();

// Helpers: Complex setup with persistence
TrainScheduleResult result = trainScheduleTestHelper.builder()
    .scheduleName("KTX 001")
    .operationDate(LocalDate.now())
    .train(train)
    .addStop("서울", null, LocalTime.of(5, 0))      // departure station
    .addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))  // intermediate
    .addStop("부산", LocalTime.of(9, 0), null)      // arrival station
    .build();
```

Test fixtures are in `src/test/java/com/sudo/raillo/support/fixture/`, helpers in `support/helper/`.

## Tech Stack Details

- **Java 17**, Spring Boot 3.5.0
- **Database**: MySQL (prod), H2 (test), QueryDSL for complex queries
- **Cache/Session**: Redis (pending bookings use TTL for auto-expiration)
- **Auth**: JWT (access + refresh tokens), refresh token in cookies
- **API Docs**: springdoc-openapi (Swagger) - controllers implement `*Doc` interfaces
- **Batch**: Spring Batch for expired member cleanup

## Key Conventions

- Korean language used in comments, exception messages, and success messages
- QueryDSL Q-classes generated to `build/generated/querydsl`
- Redis keys for pending bookings have TTL slightly shorter than main booking TTL to prevent ghost indexes
