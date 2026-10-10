# 코드 컨벤션

레이어 의존 방향, 예외 분리, 네이밍, 트랜잭션 규칙을 정한다. 에러 코드 형식은 [docs/error-code-convention.md](../../docs/error-code-convention.md)가 단일 소스다.

## 레이어 규칙

```
Controller → Facade → Service → Repository
```

- Facade는 **Service만** 호출한다. Facade → Facade 호출은 금지다. 필요해 보이면 Event-driven을 검토한다.
- Service는 Repository를 호출한다. 자기 도메인과 다른 도메인 모두 허용한다.
- **Service → Service 호출은 금지다.**

### Service → Service 기존 위반 4건

아래는 규칙 이전에 생긴 것이다. 새 코드에서 모방하지 않고, 다른 작업의 PR에서 함께 고치지 않는다.

| 호출하는 쪽 | 호출되는 쪽 |
|---|---|
| `EmailAuthService` | `EmailSendService` |
| `MemberService` | `AuthService` |
| `MemberUpdateService` | `EmailAuthService` |
| `MemberFindService` | `EmailAuthService` |

Facade → Facade는 위반이 없다.

## 헥사고날 구조 (payment)

`payment`만 헥사고날이다. 의존 방향은 `adapter → application → domain`이며 ArchUnit이 강제한다. 규칙은 `raillo-api/src/test/.../payment/PaymentHexagonalArchitectureTest.java`에 있다.

| 규칙 | 금지하는 것 |
|---|---|
| `domainMustNotDependOnApplicationOrAdapter` | `payment.domain`이 application이나 adapter를 보는 것 |
| `applicationMustNotDependOnAdapter` | `payment.application`이 adapter를 보는 것. adapter 세부는 required port로만 노출한다 |
| `adaptersMustNotDependOnEachOther` | `adapter.persistence`가 다른 어댑터를 보는 것 |
| `webApiMustNotDependOnPersistenceOrIntegration` | `adapter.webapi`가 다른 어댑터를 보는 것. provided port만 쓴다 |
| `requiredPortsMustBeInterfaces` | `application.required`의 top-level 타입이 인터페이스가 아닌 것. nested record는 예외 |
| `providedPortsMustBeInterfaces` | `application.provided`의 top-level 타입이 인터페이스가 아닌 것 |
| `domainMustNotDependOnFrameworkAnnotations` | `payment.domain`이 `jakarta.validation`, `io.swagger`, `org.springframework.web`를 보는 것 |
| `mustNotReachIntoOtherDomainsInternals` | payment가 다른 도메인의 `infrastructure`나 `adapter`를 보는 것 |

마지막 규칙의 뜻은 도메인 간 통신이 상대의 `application`이나 `domain` 진입점으로만 이뤄진다는 것이다.

### 게이트가 있는 규칙과 없는 규칙

| 규칙 | 게이트 |
|---|---|
| payment 헥사고날 8종 | ArchUnit — 테스트로 막힌다 |
| Facade → Facade 금지 | 없음 |
| Service → Service 금지 | 없음 |
| 다른 도메인 내부 접근 금지 (payment 외) | 없음 |

게이트 없는 규칙은 지켜지지 않는다. Service → Service 위반 4건이 그 결과다. ArchUnit 의존성(`archunit-junit5`)은 이미 `raillo-api`에 있으므로 적용 범위를 전 도메인으로 넓히는 것이 후속 작업이다.

## 예외

모듈 경계에 맞춰 셋으로 나눈다.

| 예외 | 쓰는 곳 | 위치 |
|---|---|---|
| `BusinessException` | Service와 Application 레이어의 비즈니스 오류. 검증 실패, 리소스 없음 | `raillo-api/global/exception/` |
| `DomainException` | Entity와 VO 내부의 도메인 불변식 위반. 상태 전이 오류, VO 검증 | `raillo-domain/global/exception/` |
| `ExternalApiException` | 외부 API 호출 실패. Toss Payments 등 | `raillo-api/global/exception/` |

도메인별 에러 enum은 `raillo-domain`의 `ErrorCode`를 구현한다.

```java
public enum BookingError implements ErrorCode {
    BOOKING_NOT_FOUND("예매 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_101");
}
```

### Exception Handler

셋으로 분리하고 순서를 고정한다.

| Handler | 범위 | Order |
|---|---|---|
| `global/exception/CommonExceptionHandler` | `BusinessException`, `ExternalApiException`, validation 예외 등 도메인 무관 | `LOWEST_PRECEDENCE` |
| `auth/exception/AuthExceptionHandler` | `BadCredentialsException` 등 auth 도메인 | `HIGHEST_PRECEDENCE` |
| `global/redis/exception/RedisExceptionHandler` | Redis 계열 | `HIGHEST_PRECEDENCE` |

## 네이밍과 배치

### Repository

- 기본 CRUD는 `JpaRepository`를 쓴다.
- 복잡한 쿼리는 `*QueryRepository` + `JPAQueryFactory`로 QueryDSL projection을 쓴다.
- 둘 다 `{domain}/infrastructure/` **직속**에 둔다. 별도 `repository/` 하위 디렉터리를 만들지 않는다.

**헥사고날 도메인 예외.** `application/required/{Domain}Repository` port가 있는 도메인(현재 `payment`)에서는 "Repository" 이름을 port에 예약한다. Repository는 쿼리 실행자가 아니기 때문이다.

| 역할 | 이름 | 위치 |
|---|---|---|
| port | `{Domain}Repository` | `application/required/` |
| QueryDSL projection 구현체 | `*QueryDao` | `adapter/persistence/` |
| Spring Data 인터페이스 | `*JpaRepository` | `adapter/persistence/` |

### Validator

`@Component`로 `application/validator/`에 `{Domain}Validator`를 둔다. Service나 Facade에 주입되어 실패 시 `BusinessException`을 던진다.

payment는 헥사고날이라 다르다. `PaymentValidator`는 `application/` 직속에, `SeatConflictValidator`는 required port이므로 `application/required/`에 있다.

이 규칙은 **비즈니스 규칙 검증기**에만 적용한다. 토큰 서명 검사처럼 기술적 검증을 하는 컴포넌트는 해당하지 않으며 쓰이는 곳 옆에 둔다. `auth/security/jwt/TokenValidator`가 그 예다.

### Entity

`raillo-domain/global/domain/BaseEntity`를 상속한다. `@MappedSuperclass`이며 `createdAt`과 `updatedAt`이 자동으로 붙는다.

### Response

도메인별 `SuccessCode` enum을 구현한다. `raillo-api/global/response/GlobalResponseHandler`가 void가 아닌 응답을 자동으로 래핑한다.

## Redis

### Repository

- 새 코드는 `StringRedisTemplate` + `RedisJsonConverter`를 쓴다. 평문 JSON이며 `@class` 메타데이터를 넣지 않는다.
- `customStringRedisTemplate`은 hash serializer가 JDK 직렬화다. **Hash에 쓰지 않는다.**
- TTL은 `@Value`로 주입한다. 코드에 상수로 박지 않는다.
- 커서 순회는 `ScanOptions`를 쓴다.

### Lua 스크립트

좌석 동시 선점 충돌을 막는다. `raillo-api/src/main/resources/scripts/`에 둔다.

| 스크립트 | 역할 |
|---|---|
| `reservation_create.lua` | 예약 생성. 객차 점유 Hash 검사와 점유, 예약 저장을 원자적으로 처리 |
| `reservation_delete.lua` | 예약 삭제. 값이 자기 `R:{reservationId}`인 점유 field만 HDEL하고 예약 본문 DEL |
| `reservation_booking_confirm.lua` | 결제 확정. 좌석 점유를 `R:` 에서 `B:`로 전환 |

Bean 등록은 `booking/infrastructure/config/RedisScriptConfig`가 한다. 키와 값 계약은 [docs/reservation-cache-schema.md](../../docs/reservation-cache-schema.md), 방어 계층은 [docs/seat-conflict-validation.md](../../docs/seat-conflict-validation.md)를 본다.

## 파라미터 전달

- 3개 이하면 개별로 전달한다. Service 재사용성이 높아진다.
- 4개 이상이면 Request 객체로 묶는다.

## 트랜잭션

- Service 클래스는 `@Transactional`을 클래스 레벨에 붙인다.
- 읽기 전용 메서드는 `@Transactional(readOnly = true)`를 붙인다.
- 조회 전용 Service는 클래스 레벨에 `@Transactional(readOnly = true)`를 붙인다.

테스트에는 `@Transactional`을 붙이지 않는다. 이유와 예외는 [test.md](./test.md)에 있다.
