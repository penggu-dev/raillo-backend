# 테스트 규칙

테스트 환경, 금지 사항, 작성 컨벤션을 정한다. `/test` 스킬은 이 문서를 따른다.

## 환경

Testcontainers로 MySQL 8.4.10과 Valkey 9를 띄운다. 운영과 같은 버전이며 **Docker가 필요하다.**

| 애노테이션 | 구성 | 쓰는 곳 |
|---|---|---|
| `@ServiceTest` | 컨테이너 + 매 테스트 후 DB 전체 DELETE, Redis FLUSH | Service, Facade, Validator, Calculator |
| `@RedisTest` | 컨테이너 + 매 테스트 후 Redis FLUSH (DB 정리 없음) | Redis Repository |
| 없음 | POJO | `raillo-domain`의 Entity, VO |

`raillo-api`의 기본은 `@ServiceTest`다. 대상이 의존성을 주입받지 않아도 검증 데이터를 DB에 만들어야 하면 `@ServiceTest`를 쓴다.

## 금지

- **테스트 클래스와 메서드에 `@Transactional`을 붙이지 않는다.** cleanup을 우회해 데이터가 다음 테스트로 새고, 프로덕션 코드의 트랜잭션 누락을 숨긴다. 트랜잭션 동작을 재현하려고 테스트 안에 둔 중첩 `@Service`는 해당하지 않는다.
- `@SpringBootTest`를 직접 쓰면 컨테이너가 붙지 않는다. `@ContextConfiguration(initializers = TestContainerInitializer.class)`를 함께 붙인다.
- Mock은 외부 API(Toss), 시계, 재시도처럼 실제로 재현하기 어려운 경계에만 쓴다. 통합 테스트는 실제 Repository와 Redis를 쓴다.

## 테스트가 있어야 하는 변경

- **기능 추가**: 추가한 동작을 고정하는 테스트
- **버그 수정**: 수정 전에 실패하는 테스트
- **리팩터링**: 기존 테스트가 동작을 고정하는지 확인한다. 없으면 리팩터링 전에 추가한다.

설정, 인프라, 문서 변경은 테스트가 없을 수 있다. 그때는 이유를 적는다.

## 작성 컨벤션

- 파일명은 `{ClassName}Test.java`, 모든 테스트에 `// given`, `// when`, `// then` 주석을 넣는다. 예외 검증은 `// when & then`으로 합쳐도 된다.
- `@DisplayName`은 상황과 기대 결과를 담은 **완전한 한국어 문장**이다. 메서드명은 영어다.
- 상태 변경은 반환값이 아니라 **DB나 Redis에서 다시 조회해** 검증한다.
- 예외는 타입과 함께 메시지나 ErrorCode를 검증한다.
- BigDecimal은 `isEqualByComparingTo`로 비교한다.
- 기존 테스트 파일에 더 구체적인 패턴이 있으면 따른다.

```java
@Test
@DisplayName("만료된 주문으로 예매 생성 시 예외가 발생한다")
void expiredOrder_createBookingFromOrder_throwException() {
	// given
	Member member = memberRepository.save(MemberFixture.create());
	Order order = OrderFixture.create(member);
	order.expired();

	// when & then
	assertThatThrownBy(() -> bookingService.createBookingFromOrder(order))
		.isInstanceOf(DomainException.class)
		.hasMessage(OrderError.ORDER_IS_EXPIRED.getMessage());
}
```

## 테스트 데이터

- `raillo-domain`: Fixture를 쓸 수 없다(API 모듈의 테스트 코드). Entity 정적 팩토리와 테스트 클래스의 private 메서드로 만든다.
- `raillo-api`: `support/fixture/`(메모리 객체)와 `support/helper/`(DB 저장)를 쓴다. Helper가 있으면 Helper를 쓴다. **쓰기 전에 `support/` 소스에서 메서드를 확인한다.** 없는 메서드를 추측하지 않는다.

공통 셋업:

```java
member = memberRepository.save(MemberFixture.create());
train = trainTestHelper.createKTX();
scheduleResult = trainScheduleTestHelper.createDefault(train);
```

**예약 생성 경로는 `@BeforeEach`에서 `trainCacheTestHelper.seed(train, scheduleResult)`를 해야 한다.** 예약 생성은 Redis 기준정보를 읽는데, Redis가 테스트마다 비워지기 때문이다.

## 실행

```bash
./gradlew :raillo-api:test --tests "com.sudo.raillo.booking.application.BookingServiceTest"
```

테스트를 쓰거나 고쳤으면 실행해서 통과를 확인한다.
