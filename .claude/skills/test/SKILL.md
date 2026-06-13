---
name: test
description: 테스트 대상 코드를 분석하고 프로젝트 테스트 컨벤션에 맞는 테스트 코드를 작성하거나 수정한다. Use when the user asks for tests for a domain entity, service, facade, validator, or calculator, including requests like `/test BookingService.cancel`, `/test Booking 엔티티`, `SeatConflictValidator 테스트 작성`, or similar natural-language asks.
---

# Test Writer

## Workflow
1. 대상 클래스, 대상 메서드, 관련 예외와 의존 객체를 읽는다.
2. 기존 테스트 파일이 있으면 먼저 읽고 패턴을 따른다.
3. 패키지 위치로 테스트 유형을 결정한다.
    - `domain/`, `application/calculator/`: 도메인 단위 테스트
    - `application/service/`, `application/facade/`, `application/validator/`: 서비스 통합 테스트
4. 테스트 케이스를 도출한다.
    - 성공 케이스
    - 실패 케이스
    - 경계 케이스
5. 코드 작성 전 테스트 케이스 목록을 먼저 짧게 제시한다.
6. 프로젝트 컨벤션에 맞춰 테스트 코드를 작성한다.
7. `./gradlew test --tests "<FQCN>"` 를 실행하고, 실패하면 수정 후 재실행한다.

## Scope
- 사용자가 메서드를 지정하면 그 메서드와 직접 연결된 분기를 우선 테스트한다.
- 사용자가 클래스만 지정하면 모든 public 메서드를 대상으로 본다.
- 메서드 본문뿐 아니라 Repository, Validator, Domain 객체 호출에서 발생하는 예외 분기도 함께 본다.

## Conventions
- 파일명은 `{ClassName}Test.java` 를 사용한다.
- 도메인 테스트는 Fixture만 사용하고 DB를 사용하지 않는다.
- 서비스 통합 테스트는 `@ServiceTest` 를 사용한다.
- 서비스 통합 테스트의 공통 데이터는 `@BeforeEach` 에서 준비한다.
- Member는 `memberRepository.save(MemberFixture.create())` 방식으로 저장한다.
- Train, Schedule, Booking, Order 등은 TestHelper가 있으면 우선 사용한다.
- 모든 테스트에 `// given`, `// when`, `// then` 주석을 넣는다.
- `@DisplayName` 은 한글 완전한 문장으로 `상황 + 기대 결과`를 쓴다.
- 테스트 메서드명은 영어 스네이크 스타일로 작성한다.
- 테스트 메서드에 `@Transactional` 을 사용하지 않는다.
- BigDecimal 비교는 `isEqualByComparingTo` 를 사용한다.
- 예외 검증은 예외 타입과 메시지 또는 에러코드를 함께 확인한다.
- 기존 테스트가 더 강한 로컬 컨벤션을 보여주면 그 패턴을 우선 따른다.

## Service Test Setup
서비스 통합 테스트의 기본 공통 셋업은 다음 흐름을 따른다.
- `member = memberRepository.save(MemberFixture.create())`
- `train = trainTestHelper.createKTX()`
- `scheduleResult = trainScheduleTestHelper.createDefault(train)`
- 각 테스트에서 필요한 예매나 주문은 `bookingTestHelper`, `orderTestHelper` 등으로 생성한다.

## Output
코드 작성 전에는 테스트 케이스 목록을 먼저 제시한다.
- 성공 케이스
- 실패 케이스
- 필요한 경우 경계 케이스

코드 작성 후에는 다음을 함께 보고한다.
- 생성 또는 수정한 테스트 파일 경로
- 추가하거나 수정한 테스트 수
- 실행한 Gradle 명령
- 테스트 통과 여부

## References

- [docs/testing-guide.md](../../../docs/testing-guide.md) — TrainTestHelper / TrainScheduleTestHelper / BookingTestHelper / OrderTestHelper의 빌더 사용 예제, Fixture 목록, `@ServiceTest`가 제공하는 Extension 상세. Helper 사용 패턴이 모호할 때 이 문서를 먼저 읽는다.
- 루트 [CLAUDE.md](../../../CLAUDE.md)의 Layer Rules, Exception 3종, Transaction 규칙은 테스트 대상 코드를 이해할 때 함께 참고한다.
