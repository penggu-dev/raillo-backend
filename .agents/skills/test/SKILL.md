---
name: test
description: 테스트 대상 코드를 분석해 프로젝트 테스트 컨벤션에 맞는 테스트 코드를 작성·수정하고 실행까지 확인한다. Use when the user asks for tests for a domain entity, VO, service, facade, validator, calculator, or Redis repository — e.g. `/test BookingService.cancel`, `/test Booking 엔티티`, `SeatConflictValidator 테스트 작성`. Also used by `/plan` for steps marked "테스트 필요".
argument-hint: "[클래스명 또는 클래스.메서드]"
---

# Test Writer

대상: `$ARGUMENTS`

테스트 유틸리티(어노테이션·Fixture·Helper) 사용법과 레시피는 [reference.md](reference.md)에 있다. **Helper/Fixture 메서드를 쓰기 전에 reference.md를 먼저 읽는다.** 거기 없는 메서드는 `raillo-api/src/test/java/com/sudo/raillo/support/` 소스를 직접 확인한 뒤 사용한다 (존재하지 않는 메서드를 추측해 쓰지 않는다).

## 1. 테스트 유형 결정

| 대상 위치 | 유형 | 테스트 위치 | 설정 |
|----------|------|-----------|------|
| `raillo-domain` `{domain}/domain/`, `cache/`, VO | 도메인 단위 테스트 | `raillo-domain/src/test/java/.../{domain}/domain/` | 어노테이션 없음 (POJO). Fixture 사용 불가 → Entity 정적 팩토리 + private 생성 메서드 |
| `raillo-api` `calculator/`, `generator/`, `util/` | 단위 테스트 | `raillo-api/src/test/java/.../{domain}/...` | 어노테이션 없음. Spring 컨텍스트 불필요 |
| `application/service/`, `facade/`, `validator/` | 서비스 통합 테스트 | `raillo-api/src/test/java/.../{domain}/application/{service,facade,validator}/` | `@ServiceTest` (MySQL + Valkey, 테스트마다 DB·Redis cleanup) |
| `infrastructure/` Redis Repository | Redis 통합 테스트 | `raillo-api/src/test/java/.../{domain}/infrastructure/` | `@RedisTest` (Redis만, DB cleanup 없음) |
| 그 외 `@SpringBootTest` 직접 사용 | — | — | `@ContextConfiguration(initializers = TestContainerInitializer.class)` 필수 |

> 실행에 Docker가 필요하다 (Testcontainers MySQL 8.4.10 / Valkey 9).

## 2. Workflow

1. **대상 읽기**: 대상 클래스·메서드, 호출하는 Repository/Validator/Domain 메서드, 던지는 예외와 ErrorCode를 읽는다.
2. **기존 테스트 읽기**: `{ClassName}Test.java`가 있으면 먼저 읽고 그 파일의 구조(`@Nested` 여부, 필드 구성, private 헬퍼)를 따른다. 없으면 같은 패키지의 형제 테스트를 하나 읽는다.
3. **케이스 도출**: 분기마다 성공 / 실패(예외) / 경계(빈 목록, 구간 끝, 시간 경계, 동일 값) 케이스를 뽑는다.
   - 메서드를 지정했으면 그 메서드와 직접 연결된 분기만, 클래스만 지정했으면 모든 public 메서드.
   - 메서드 본문뿐 아니라 호출되는 Validator·Domain 메서드가 던지는 예외 분기도 포함한다.
4. **케이스 목록 먼저 제시** (코드 작성 전, 짧게):
   ```
   🧪 테스트 케이스 — {ClassName}
   성공: 1) ...  2) ...
   실패: 1) ... → {ErrorCode}
   경계: 1) ...
   ```
   단독 호출이면 사용자 확인 후 작성한다. `/plan` 단계 안에서 호출됐으면 확인 없이 바로 작성한다.
5. **작성**: 아래 컨벤션 체크리스트를 지킨다.
6. **실행**: `./gradlew :{모듈}:test --tests "{FQCN}"` 실행 → 실패하면 원인을 고치고 재실행. 테스트를 통과시키기 위해 **프로덕션 코드를 수정해야 한다면 멈추고 사용자에게 알린다** (테스트가 버그를 찾은 것일 수 있음).
   - Docker를 쓸 수 없어 Testcontainers가 뜨지 않으면(샌드박스 등) 우회하지 않는다. `./gradlew :{모듈}:compileTestJava`로 컴파일만 확인하고, 실행할 명령을 사용자에게 넘긴다. 보고의 결과 칸에 `미실행 (Docker 불가)`라고 적는다.
7. **보고** (아래 Output).

## 3. 컨벤션 체크리스트

**구조**
- [ ] 파일명 `{ClassName}Test.java`, 클래스는 package-private (`class FooTest`)
- [ ] 모든 테스트에 `// given`, `// when`, `// then` 주석 (예외 테스트는 `// when & then` 허용). 맥락이 필요하면 `// given - 열차 출발 23:00, 정차역 01:00`처럼 덧붙인다
- [ ] 같은 메서드의 케이스가 많으면 `@Nested` + `@DisplayName("성공")`/`("실패")` 등으로 묶는다 (기존 파일이 안 쓰면 따르지 않는다)
- [ ] 공통 데이터는 `@BeforeEach`에서 준비, 테스트별 데이터는 given에서

**이름**
- [ ] `@DisplayName`: 한국어 완전한 문장, `상황 + 기대 결과` (예: "이미 취소된 예매를 취소하면 예외가 발생한다")
- [ ] 메서드명: 영어, 의도가 드러나는 snake/camel 혼용 허용 (예: `cancel_fail_if_already_cancelled`, `creates_reservation`)

**금지**
- [ ] ⚠️ 테스트 클래스·메서드에 `@Transactional` 금지 — cleanup Extension을 우회하고 트랜잭션 전파 버그를 숨긴다
- [ ] 통합 테스트는 실제 Repository·Redis를 쓴다. Mock(`@MockitoBean` 등)은 외부 API(Toss)·시계·재시도처럼 실제로 재현하기 어려운 경계에만 쓴다 (`payment/` 테스트 참고)

**검증**
- [ ] AssertJ 사용 (`assertThat`, `assertThatThrownBy`)
- [ ] BigDecimal은 `isEqualByComparingTo("30000")`
- [ ] 예외는 타입 + ErrorCode를 함께:
  ```java
  assertThatThrownBy(() -> booking.cancel())
      .isInstanceOf(DomainException.class)
      .hasFieldOrPropertyWithValue("errorCode", BookingError.BOOKING_ALREADY_CANCELLED);
  ```
  도메인 불변식 위반은 `DomainException`, 서비스 검증 실패는 `BusinessException` — 대상 코드가 실제로 던지는 타입을 확인한다
- [ ] 상태 변경은 **반환값이 아니라 DB/Redis에서 다시 조회**해 검증 (`repository.findById(...).orElseThrow()`)
- [ ] 컬렉션은 `extracting(...).containsExactly(...)` / `containsExactlyInAnyOrder(...)`
- [ ] Redis JSON 값에는 `doesNotContain("@class")` 검증을 고려 (캐시 계약)

**데이터**
- [ ] `raillo-domain` 테스트: Fixture 없이 정적 팩토리(`TrainSchedule.create(...)` 등) + 테스트 클래스 private 메서드
- [ ] `raillo-api` 테스트: Member는 `memberRepository.save(MemberFixture.create())`, 열차·스케줄·예매·주문은 TestHelper
- [ ] 예약 생성 경로(Reservation/좌석 점유)는 `@BeforeEach`에서 `trainCacheTestHelper.seed(train, scheduleResult)` 필수 (Redis가 테스트마다 비워짐)
- [ ] 날짜는 운행 가능한 미래(`LocalDate.now().plusDays(1)`) 또는 고정값(`LocalDate.of(2026, 1, 1)`) — 도메인 단위 테스트는 고정값 우선

## 4. Output

```
✅ 테스트 작성 완료
📄 파일: {경로} (신규|수정)
🧪 케이스: 성공 n / 실패 n / 경계 n (총 n개)
▶️ 실행: ./gradlew :raillo-api:test --tests "..."
📊 결과: 통과 n / 실패 n
⚠️ 발견 사항: {테스트 중 발견한 프로덕션 코드 의심 지점, 없으면 생략}
```

`/plan` 단계에서 호출된 경우 커밋하지 않는다 — 결과는 단계 검토 요청에 합쳐진다.

## References
- [reference.md](reference.md) — 어노테이션, Fixture, Helper API와 레시피 (예제의 단일 원본)
- [docs/testing-guide.md](../../../docs/testing-guide.md) — 테스트 환경(컨테이너 라이프사이클, 재사용 설정) 개요
- 루트 [AGENTS.md](../../../AGENTS.md) — Layer Rules, 예외 3종, 트랜잭션 규칙
