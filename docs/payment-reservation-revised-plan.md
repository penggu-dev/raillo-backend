# 결제·예약 이관 재검토 계획 (2026-09-20)

이전 계획서: [`docs/payment-reservation-work-roadmap.md`](./payment-reservation-work-roadmap.md)

이전 계획서의 "결제 중 좌석 보호(Reservation Payment Guard)" 전제를 폐기하고, 예약 TTL만 신뢰하는 방향으로 재작업한다. Codex가 만든 브랜치(`feature/266-payment-outbox-worker`)는 그 위에 되돌리기 및 신규 커밋을 얹어 정리한다.

## 원칙

**결제 실패는 예약과 Payment 상태에 영향을 주지 않는다.** 실패한 Attempt만 FAILED로 마킹하고, Payment는 PENDING을 유지해서 같은 Payment 위에 새 Attempt를 얹을 수 있게 한다. 예약의 생사는 다음 두 경로만 결정한다:

- 예약 TTL(10분) 만료
- 유저의 명시적 예약 취소

근거:

- 실제 예매 서비스의 UX 계약이다. 카드 거절/한도 초과 등 재시도 가능한 실패에서도 예약과 결제 세션이 유지되어야 유저가 다른 카드로 재시도할 수 있다.
- 결제 성공/실패는 Attempt 단위의 상태 전이일 뿐이지 Payment/Order/Booking 도메인의 소유권을 좌지우지할 근거가 아니다.
- 좌석 회수는 예약 TTL이라는 시간 제약 하나로 충분하다. 결제 시점 별도 보호 계층은 UX 저하만 만든다.

## 도메인 상태 계층

옵션 Y와 옵션 3(paymentKey semantics)을 함께 채택. 각 층의 책임을 명확히 분리한다.

| 도메인 | 실패 시 상태 전이 | paymentKey 소유 |
|---|---|---|
| Reservation | 변화 없음 (TTL/유저 취소만 회수) | — |
| Order | 변화 없음 (PENDING 유지) | — |
| Payment | 변화 없음 (PENDING 유지) | 성공한 Attempt의 paymentKey만 확정 값으로 저장 (승인 확정 시) |
| PaymentAttempt | FAILED로 마킹 | Attempt마다 자기 paymentKey 소유 (TX A에서 사전 저장) |

카드 A 실패 → 카드 B로 재시도 시 흐름:

- 카드 A: `PaymentAttempt(A, paymentKey_A, FAILED)`, Payment 그대로 PENDING
- 카드 B: 프론트가 `prepare` 재호출 없이 새 Toss 결제창만 열어 `paymentKey_B` 발급
- confirm(paymentKey_B) → 같은 Payment 위에 `PaymentAttempt(B, paymentKey_B, IN_PROGRESS)` 커밋
- Toss 승인 → TX B에서 `Payment.approve(method, paymentKey_B)` → Payment.paymentKey = paymentKey_B, status = PAID

### paymentKey 사전 저장 요구사항

옛 구조에서 Payment.paymentKey를 사전 세팅했던 이유(Toss 응답 유실 시 recovery용 durable key 저장)는 지금 3단계 구조에서 **PaymentAttempt TX A**가 담당한다. TX A가 Attempt를 커밋한 뒤 Toss를 호출하므로, Toss 응답 유실·프로세스 죽음 어느 지점에서도 `SELECT paymentKey FROM payment_attempt WHERE payment_id=X AND status=IN_PROGRESS`로 recovery 가능하다. 따라서 Payment.paymentKey를 사전에 세팅할 필요가 없다.

## 유지되는 것 (Codex 작업 재사용)

- Reservation 기반 결제 API 계약 (`PaymentPrepareRequest.reservationIds`)
- `PaymentApprovalStarter` → Toss → `PaymentApprovalFinalizer` 3단계 흐름과 TX A/B 분리
- `attemptId`의 `paymentKey` 기반 자동 파생 및 DB unique로 dedup
- Outbox 인프라: `PaymentOutboxWorker`, `PaymentOutboxScheduler`, retry policy, SKIP LOCKED 잠금 조회
- `BookingConfirmedPayload` schemaVersion=2, `Order.reservation_snapshot` 컬럼
- `TrainSearchFacade`의 `SeatOccupancyQuery` 이관, `TrainCarQueryRepository`의 countDistinct
- PendingBooking/SeatHold 스택 전면 제거

## 되돌리는 것

### 1. `PaymentConfirmRequest.attemptId` optional 제거

**변경**: optional 필드 삭제. 서버가 항상 `paymentKey`에서 파생.

**근거**: 프론트가 자체 attemptId를 관리해야 할 유일한 케이스로 "offline queue 재전송" 상황이 있으나, Toss 결제창은 온라인 상태에서만 뜨고 발급된 `paymentKey`가 이미 idempotency 역할을 한다. 자체 UUID가 필요한 시나리오가 없다.

**대상**:

- `PaymentConfirmRequest.attemptId` 필드 삭제 (`@Size` 제약 포함)
- `PaymentConfirmCommand`의 `attemptId` 파라미터 및 `attemptIdOrDerived()` 삭제, `attemptId()` 하나로 통합 (항상 파생)
- `PaymentValidator.validateAttemptId` 유효성 검증 대상 재확인
- Swagger 문서 갱신
- 테스트에서 명시 attemptId 사용하던 곳은 command factory helper에서 override

### 2. 결제 실패 시 좌석/예약/Payment 정리 로직 삭제

**변경**: Toss 4xx 응답 시 **`PaymentAttempt.markFailed`만 실행**. Payment는 PENDING 유지. 좌석 HDEL, 예약 본문 DEL, marker 세팅/삭제 모두 제거.

**대상**:

- `PaymentApprovalFailureHandler.fail`에서 `payment.fail(...)` 호출 삭제 및 outbox 발행 코드 삭제 (Attempt 상태만 갱신)
- `ReservationReleasePayload` 삭제
- `ReservationReleaseProcessor` 삭제
- `PaymentOutboxType.RESERVATION_RELEASE` enum 값 삭제
- `PaymentOutbox.forReservationRelease` 팩토리 삭제
- `PaymentConfirmService.confirm`의 claim 실패 catch에서 outbox 발행 없이 Attempt 실패만 남김
- `Payment.fail` 도메인 메서드는 유지 (호출 경로는 후속 취소 도메인 #259에서 유저 명시 취소·예약 만료 배치에서만 사용)

### 2-2. Payment.paymentKey semantics 재정의 (옵션 3)

**변경**: `Payment.paymentKey`는 **성공한 Attempt의 paymentKey만 확정 값으로 저장**한다. 사전 세팅 로직 삭제.

**대상**:

- `Payment.updatePaymentKey(String)` 도메인 메서드 삭제
- `Payment.approve(PaymentMethod)` → `Payment.approve(PaymentMethod, String paymentKey)`로 시그니처 변경
- `PaymentApprovalFinalizer.finalizeApproval`에서 `payment.approve(gatewayResult.method(), gatewayResult.paymentKey())` 호출
- `Payment.paymentKey`는 nullable 유지 (unique 제약 유지 — MySQL은 nullable + unique에서 여러 null 허용)
- `PaymentModifier.createPayment`에서 paymentKey 세팅 안 함 (기존과 동일)
- `PaymentPersistenceAdapter.findByPaymentKey` 조회는 SUCCEEDED Payment만 대상이 됨 (기존 콜러 재확인 필요)

**paymentKey의 두 역할 분리**:

- **PaymentAttempt.paymentKey** — 진행 중 recovery용 durable key (TX A에서 사전 저장)
- **Payment.paymentKey** — 확정된 결제 키 (취소/환불용, 승인 성공 시 세팅)

### 3. `ReservationPaymentGuard` 스택 삭제

동시 결제 방지는 이미 두 계층으로 커버된다:

- `PaymentAttempt.attempt_id`의 DB unique 제약 → 같은 결제창(같은 `paymentKey`)의 중복 요청은 DB에서 실패
- `PaymentValidator.validateApprovable(payment)` → Payment 상태가 PENDING이 아니면 승인 거부

따라서 Redis marker의 실질 이득이 없다. 삭제한다.

**대상**:

- `ReservationPaymentGuard` port 삭제
- `ReservationPaymentGuardAdapter` 삭제
- `ReservationPaymentGuardService` 삭제
- `ReservationPaymentGuardRepository` 삭제
- `scripts/reservation_payment_claim.lua` 삭제
- `scripts/reservation_payment_release.lua` 삭제
- `RedisScriptConfig`에서 두 script bean 삭제
- `ReservationCacheKey.paymentGuard(...)` 메서드 삭제
- `BookingError.RESERVATION_PAYMENT_CONFLICT` 삭제
- `PaymentConfirmService.confirm`에서 `guard.claim`/`release` 호출 삭제
- `PaymentApprovalStart.guardToken()` 삭제
- `BookingConfirmedPayload.guardToken` 필드 삭제
- 관련 테스트 삭제 (`ReservationPaymentGuardRepositoryTest`, `ReservationPaymentIntegrationTest`의 guard 관련 케이스 재구성)

### 4. IN_PROGRESS attempt 재조회 경로 추가

**변경**: `PaymentApprovalStarter.handleExistingAttempt`의 IN_PROGRESS 분기가 예외를 던지지 말고 Toss에 재조회한다.

**신규**:

- `PaymentGateway.query(String paymentKey)` 메서드 추가 → Toss `GET /v1/payments/{paymentKey}`
- `PaymentGatewayQueryResult` VO (status, method, totalAmount 등)
- `TossPaymentGateway`에 구현 추가
- IN_PROGRESS 분기 처리:
  - Toss `DONE` → 로컬 attempt/payment 정정 후 TX B(Booking 생성) 실행, 정상 결과 반환
  - Toss `ABORTED` / `EXPIRED` → 로컬 FAILED 정정, 실패 응답
  - Toss `IN_PROGRESS` / 타임아웃 → 기존처럼 IN_PROGRESS 예외 (유저에게 재시도 안내)

**결과**: 프로세스 죽음, Toss 응답 유실 이후 유저가 재시도할 때 즉시 정답 응답. Recovery Worker(#270)는 백그라운드 대사(오래된 IN_PROGRESS 청소, 대규모 정합성 감사) 용도로 별도 유지.

## 커밋 순서

브랜치: `feature/266-payment-outbox-worker` 유지. Codex 51 커밋 위에 추가 커밋 얹기.

1. **[docs] 결제·예약 이관 재검토 계획 반영 (#266)** — 이 문서 + 아래 다이어그램 추가
2. **[refactor] PaymentConfirmRequest.attemptId 제거 및 서버 파생 통합 (#266)**
3. **[refactor] Payment 실패 상태 전이 삭제, Attempt만 FAILED (#266)** — `PaymentApprovalFailureHandler`에서 `payment.fail` 호출 삭제, Attempt만 마킹
4. **[refactor] Payment.paymentKey를 승인 확정 시에만 세팅 (#266)** — `updatePaymentKey` 삭제, `approve(method, paymentKey)`로 시그니처 변경, Finalizer에서 세팅
5. **[refactor] RESERVATION_RELEASE outbox 스택 삭제 (#266)** — Payload/Processor/enum/팩토리 제거, Failure Handler에서 outbox 발행 제거
6. **[refactor] ReservationPaymentGuard 스택 삭제 (#266)** — port/adapter/service/repository/Lua 2개/키 상수/에러코드/`BookingConfirmedPayload.guardToken` 제거
7. **[feat] PaymentGateway.query 및 IN_PROGRESS 재조회 경로 (#266)** — TossPaymentGateway 구현 추가, `PaymentApprovalStarter` IN_PROGRESS 분기 갱신
8. **[test] 재작성된 결제 흐름 통합 테스트 (#266)** — 3~7 각 커밋에 인접 또는 뒤에
9. **[docs] payment-consistency / reservation-cache-schema / seat-conflict-validation 문서 갱신 (#266)**

각 커밋의 build/test는 그 커밋 내부에서 통과해야 한다. 5·6번은 삭제 위주라 컴파일 에러 파급이 있으므로 커밋 안에서 관련 테스트 정리까지 함께 포함한다.

## 문서 반영 (9번 커밋 상세)

- `CLAUDE.md` 결제 흐름 요약 갱신
- `docs/payment-consistency.md`:
  - `attemptId` 계약 절 신설 (paymentKey 파생 규칙, 한 결제창 세션당 하나, DB unique dedup)
  - 결제 실패 정책 절 신설 (예약과 Payment 유지 원칙, Attempt만 FAILED, TTL과 유저 취소만 좌석 회수)
  - `Payment.paymentKey` semantics 절 신설 (승인 확정 시에만 세팅, `PaymentAttempt.paymentKey`의 recovery 역할 명시)
  - IN_PROGRESS 재조회 절 신설 (Toss 조회 규칙, Recovery Worker와의 역할 분담)
- `docs/reservation-cache-schema.md`:
  - `reservation-payment:*` 키 언급 삭제
  - HPERSIST/보호 marker 관련 서술 삭제
- `docs/seat-conflict-validation.md`: guard 계층 언급 삭제
- 이 재검토 계획서(`docs/payment-reservation-revised-plan.md`)와 이전 계획서(`docs/payment-reservation-work-roadmap.md`) 둘 다 유지: 이전 계획서는 초기 조사와 계약 정리 부분이 여전히 참고 가치가 있고, 방향 전환 이력을 남길 필요가 있다.

## 다이어그램

`docs/diagrams/payment-flow/`에 archify로 생성:

- `normal.html` — 정상 결제 흐름
- `card-rejected.html` — Toss 4xx 카드 승인 거절
- `unknown-result-retry.html` — 결과 불명 후 유저 재시도 (IN_PROGRESS 재조회)

## 검증 조건 (커밋 완료 후 재확인)

- 결제 실패해도 예약 본문(String)과 좌석 field가 그대로 남는다.
- 결제 실패 시 Payment는 PENDING을 유지하고 Attempt만 FAILED로 마킹된다.
- 유저가 다른 카드로 재시도(=새 paymentKey)하면 같은 Payment 위에 새 Attempt가 생성되고 승인 확정 시 `Payment.approve(method, paymentKey)`가 성공한 attempt의 paymentKey를 확정 값으로 세팅한다.
- `Payment.paymentKey`는 승인 확정 전에는 null이며, 확정 후에는 성공한 Attempt의 paymentKey와 일치한다.
- 같은 결제창의 재요청(=같은 paymentKey)은 DB unique로 dedup되어 첫 결과를 반환한다.
- Toss 응답 유실로 IN_PROGRESS 남은 attempt에 재요청이 오면 Toss 재조회 후 결과 반환한다 (Attempt.paymentKey로 조회).
- 예약 TTL 만료 후에는 결제 승인이 불가능하다 (예약 조회 실패로 자연 거절).
- Outbox의 `BOOKING_CONFIRMED`는 계속 PENDING으로 보존된다 (R→B 후속 PR 대상). `RESERVATION_RELEASE` 이벤트는 신규 발생하지 않으며 기존 데이터는 마이그레이션 시점에 정리.
