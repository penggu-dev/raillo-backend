# 코드 컨벤션

레이어, 예외, 네이밍, 트랜잭션 규칙을 정한다.

## 레이어

```
Controller → Facade → Service → Repository
```

- Facade는 Service만 호출한다. **Facade → Facade 호출은 금지다.** 필요해 보이면 이벤트를 검토한다.
- **Service → Service 호출은 금지다.** Service는 자기 도메인과 다른 도메인의 Repository를 호출할 수 있다.
- 모듈 의존은 `raillo-domain ← raillo-api / raillo-batch`다. `raillo-api`와 `raillo-batch`는 서로 의존하지 않는다.

기존 코드에 Service → Service 호출이 남아 있다. 새 코드에서 따라 하지 않는다.

### payment는 헥사고날이다

의존 방향은 `adapter → application → domain`이며, 다른 도메인과는 상대의 `application`이나 `domain`으로만 통신한다. ArchUnit이 강제하고 규칙은 `PaymentHexagonalArchitectureTest`에 있다.

## 예외

| 예외 | 쓰는 곳 | 위치 |
|---|---|---|
| `DomainException` | Entity, VO의 불변식 위반 (상태 전이, VO 검증) | `raillo-domain` |
| `BusinessException` | Service의 비즈니스 오류 (검증 실패, 리소스 없음) | `raillo-api` |
| `ExternalApiException` | 외부 API 호출 실패 (Toss) | `raillo-api` |

- 도메인별 에러 enum `{Domain}Error`는 `raillo-domain`의 `ErrorCode`를 구현한다.
- 에러 코드는 `{DOMAIN}_{NNN}`이다. `BOOKING_`, `TRAIN_`, `PAYMENT_`, `GLOBAL_`은 백의 자리가 카테고리이므로 enum에서 같은 카테고리의 다음 번호를 쓴다. 나머지 도메인은 `001`부터 순번이다.
- 재시도해도 결과가 바뀌지 않는 오류(깨진 데이터, 영구히 무효한 입력)는 `failedWorkRetryable()`을 `false`로 오버라이드한다. Outbox 같은 재시도 큐가 이 값으로 즉시 포기할지 정한다.
- 예외 핸들러는 `CommonExceptionHandler`가 `LOWEST_PRECEDENCE`로 공통 예외를 받는다. 도메인 전용 핸들러는 그 도메인의 `exception/`에 `HIGHEST_PRECEDENCE`로 둔다.

## 네이밍과 배치

- **Repository**: `{domain}/infrastructure/` 직속에 둔다. `repository/` 하위 디렉터리를 만들지 않는다. 복잡한 조회는 `*QueryRepository` + QueryDSL projection으로 쓴다.
- **payment의 Repository**: 이름 `{Domain}Repository`는 `application/required/`의 port가 쓴다. 구현은 `adapter/persistence/`에 Spring Data는 `*JpaRepository`, QueryDSL은 `*QueryDao`로 둔다.
- **Validator**: 비즈니스 규칙 검증기는 `application/validator/{Domain}Validator`(`@Component`)로 두고 실패 시 `BusinessException`을 던진다. 토큰 서명 검사 같은 기술적 검증은 쓰이는 곳 옆에 둔다.
- **Entity**: `BaseEntity`를 상속한다.
- **응답**: 도메인별 `SuccessCode`를 구현한다. `GlobalResponseHandler`가 void가 아닌 응답을 래핑한다.

## 트랜잭션

- Service는 클래스에 `@Transactional`, 읽기 메서드에 `@Transactional(readOnly = true)`를 붙인다. 조회 전용 Service는 클래스에 `readOnly = true`를 붙인다.
- DB를 쓰지 않는 Service(Redis 전용, 순수 계산)는 붙이지 않는다.

## 파라미터

3개 이하는 개별로 넘기고, 4개 이상이면 Request 객체로 묶는다.

## Redis

- 새 코드는 `StringRedisTemplate` + `RedisJsonConverter`를 쓴다. JSON에 `@class`를 넣지 않는다.
- `customStringRedisTemplate`은 hash serializer가 JDK 직렬화다. **Hash에 쓰지 않는다.**
- 여러 키를 검사하고 쓰는 작업은 Lua 스크립트로 원자적으로 처리한다. 스크립트는 `raillo-api/src/main/resources/scripts/`에 둔다.
- TTL은 `@Value`로 주입한다. 키 순회는 `ScanOptions`를 쓰고 `KEYS`를 쓰지 않는다.
