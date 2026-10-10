---
name: test
description: 대상 코드를 분석해 프로젝트 테스트 규칙에 맞는 테스트를 작성하거나 고치고, 실행해 통과를 확인한다. Use when the user asks for tests for a domain entity, VO, service, facade, validator, calculator, or Redis repository — e.g. `/test BookingService.cancel`, `/test Booking 엔티티`, `SeatConflictValidator 테스트 작성`. Also used by `/implement` for steps marked "테스트 필요".
argument-hint: "[클래스명 또는 클래스.메서드]"
---

# Test

대상: `$ARGUMENTS`

규칙의 단일 소스는 [.agents/rules/test.md](../../rules/test.md)다. **작성 전에 읽는다.** 테스트 유형 선택, 금지 사항(`@Transactional` 등), 작성 컨벤션, 테스트 데이터 만드는 법이 거기 있다. Helper와 Fixture 메서드는 `raillo-api/src/test/java/com/sudo/raillo/support/` 소스를 확인하고 쓴다. 없는 메서드를 추측하지 않는다.

## 절차

1. **대상 읽기**: 대상 메서드, 호출하는 Repository, Validator, Domain 메서드, 던지는 예외와 ErrorCode.
2. **기존 테스트 읽기**: `{ClassName}Test.java`가 있으면 그 구조(`@Nested`, 필드, private 헬퍼)를 따른다. 없으면 같은 패키지의 형제 테스트 하나를 읽는다.
3. **케이스 도출**: 분기마다 성공, 실패(예외), 경계(빈 목록, 구간 끝, 시간 경계). 호출되는 Validator와 Domain 메서드의 예외 분기도 포함한다. 메서드를 지정했으면 그 메서드만, 클래스만 지정했으면 public 메서드 전부.
4. **케이스 목록 제시**: 단독 호출이면 확인을 받고 쓴다. `/implement` 안에서는 바로 쓴다.

   ```
   🧪 {ClassName}
   성공: 1) ...
   실패: 1) ... → {ErrorCode}
   경계: 1) ...
   ```

5. **작성**.
6. **실행**: `./gradlew :{모듈}:test --tests "{FQCN}"`. 실패하면 고치고 다시 돌린다.
   - 통과시키려면 **프로덕션 코드를 고쳐야 하면 멈추고 알린다.** 테스트가 버그를 찾은 것일 수 있다.
   - Docker를 못 쓰면 `./gradlew :{모듈}:compileTestJava`까지만 하고 실행 명령을 넘긴다.

## 자주 놓치는 것

- 상태 변경은 반환값이 아니라 **DB나 Redis에서 다시 조회해** 검증한다.
- 예외는 타입과 ErrorCode를 함께 본다. 대상 코드가 실제로 던지는 타입(`DomainException` / `BusinessException`)을 확인한다.

  ```java
  assertThatThrownBy(() -> booking.cancel())
      .isInstanceOf(DomainException.class)
      .hasFieldOrPropertyWithValue("errorCode", BookingError.BOOKING_ALREADY_CANCELLED);
  ```

- 예약 생성 경로는 `@BeforeEach`에서 `trainCacheTestHelper.seed(...)`를 다시 한다.
- Mock은 외부 API(Toss), 시계, 재시도처럼 재현이 어려운 경계에만 쓴다.

## 보고

```
✅ {경로} (신규|수정)
케이스: 성공 n / 실패 n / 경계 n
실행: ./gradlew :{모듈}:test --tests "..." → 통과 n / 실패 n (또는 미실행: Docker 불가)
발견 사항: {프로덕션 코드 의심 지점, 없으면 생략}
```

`/implement` 안에서는 커밋하지 않는다. 결과는 단계 검토 요청에 합쳐진다.
