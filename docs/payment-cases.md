# 결제 케이스별 처리 인덱스

각 결제 케이스의 트리거·응답·데이터 상태 변화·대응 다이어그램. 재설계 방향(옵션 Y + 옵션 3, guard 스택 삭제)을 반영한 목표 동작이다. 구현 시점은 [`docs/payment-reservation-revised-plan.md`](./payment-reservation-revised-plan.md)의 커밋 순서 참고.

관련 문서: [데이터 계약](./payment-data-contracts.md) · [다이어그램 폴더](./diagrams/payment-flow/)

## 케이스 목록

| # | 케이스 | 트리거 | 클라이언트 응답 | 다이어그램 |
|---|---|---|---|---|
| 1 | 정상 결제 | Toss 승인 성공 | 200 결제 완료 | [normal-flow · case-1](./diagrams/payment-flow/normal-flow.html) |
| 2 | 카드 승인 거절 후 재시도 | Toss 4xx | 402 실패 → 새 카드 재시도 | [retry-defense · case-2](./diagrams/payment-flow/retry-defense.html) |
| 3 | 결과 불명 · 인라인 자동 재조회 | Toss 5xx/타임아웃 (confirm 안에서) | Toss GET 재조회 후 200/402/503 | [unclear-result · case-3](./diagrams/payment-flow/unclear-result.html) |
| 4 | 결과 불명 · 유저 재시도 | 유저가 같은 결제창에서 재시도 | attempt dedup + Toss GET → 정정 결과 | [unclear-result · case-4](./diagrams/payment-flow/unclear-result.html) |
| 5 | 같은 attemptId 재요청 (SUCCEEDED) | 클라이언트 double-click / 자동 재전송 | 이전 결과 재사용 (Toss 호출 없음) | [normal-flow · case-5](./diagrams/payment-flow/normal-flow.html) |
| 6 | 같은 attemptId 재요청 (FAILED) | 실패 attempt에 재요청 | `PAYMENT_ATTEMPT_ALREADY_FAILED` 예외 | [retry-defense · case-6](./diagrams/payment-flow/retry-defense.html) |
| 7 | 예약 만료 | 예약 TTL(10분) 초과 후 confirm | `RESERVATION_EXPIRED` — 새 예약 필요 | [validation-failure · case-7](./diagrams/payment-flow/validation-failure.html) |
| 8 | 중복 결제 (Order에 이미 PAID) | validateDuplicatePayment 실패 | `PAYMENT_ALREADY_COMPLETED` | [validation-failure · case-8](./diagrams/payment-flow/validation-failure.html) |
| 9 | 금액 불일치 | request vs Order vs Payment 금액 다름 | `PAYMENT_AMOUNT_MISMATCH` | [validation-failure · case-9](./diagrams/payment-flow/validation-failure.html) |
| 10 | Toss 응답 paymentKey 미스매치 | Toss 응답 vs 요청 paymentKey 다름 | `PAYMENT_KEY_MISMATCH` | [validation-failure · case-10](./diagrams/payment-flow/validation-failure.html) |
| 11 | 유저 명시 예약 취소 (#259) | 유저가 예약 취소 API 호출 | 좌석/예약/Order/Payment 정리 | (#259 후속) |
| 12 | Recovery Worker 대사 | 오래된 IN_PROGRESS attempt (#270) | 배치 정정 (유저 응답 아님) | [unclear-result · case-12](./diagrams/payment-flow/unclear-result.html) |
| 13 | 새 세션 재시도 (이전 IN_PROGRESS) | 다른 attemptId, 같은 Payment의 이전 attempt가 IN_PROGRESS | `PAYMENT_ATTEMPT_IN_PROGRESS` — TX A `findLatestApprovalByPaymentId`가 차단 | [retry-defense · case-13](./diagrams/payment-flow/retry-defense.html) |
| 14 | pre-check와 잠금 사이 SUCCEEDED race | pre-check 통과 후 TX A 잠금 획득 전에 다른 요청이 확정 커밋 | `PAYMENT_ALREADY_COMPLETED` — TX A가 SUCCEEDED 발견 | [retry-defense · case-14](./diagrams/payment-flow/retry-defense.html) |
| 15 | 동시 TX B 진입 경합 | 원본 confirm 대기 중 유저가 재시도, 둘 다 DONE 확인 → 둘 다 TX B 진입 | TX B 락 직렬화 후 늦게 진입한 쪽이 `attempt.status == SUCCEEDED` 조기 리턴. 유저 관점 성공 응답 | [tx-b-race · case-15](./diagrams/payment-flow/tx-b-race.html) |
| 16 | markFailed idempotency 경합 | 원본 4xx 실패 대기 중 재시도가 먼저 ABORTED로 markFailed 커밋 | 원본의 뒤늦은 markFailed는 `attempt.status != IN_PROGRESS`라 no-op 종료. 원본에는 원래 4xx 예외 그대로 전파 | [markfail-race · case-16](./diagrams/payment-flow/markfail-race.html) |

## 케이스별 상태 전이 표

| # | Reservation | 좌석 field | Order | Payment | Attempt | 비고 |
|---|---|---|---|---|---|---|
| 1 | 그대로 | R (후속 PR에서 B) | ORDERED | PAID (+paymentKey) | SUCCEEDED | Outbox BOOKING_CONFIRMED 발행 |
| 2 | 그대로 | 그대로 | PENDING | PENDING | 첫 Attempt FAILED · 새 Attempt IN_PROGRESS → SUCCEEDED | 같은 Order/Payment 재사용 |
| 3 | 그대로 | 성공 시 R→(후속 B) | 성공 시 ORDERED · 실패 시 PENDING | 성공 시 PAID · 실패 시 PENDING | 재조회 후 정정 | 인라인 재조회로 3xx도 사실은 확정됨 감지 |
| 4 | 그대로 | 성공 시 R→(후속 B) | 성공 시 ORDERED · 실패 시 PENDING | 성공 시 PAID · 실패 시 PENDING | 재조회 후 정정 | 유저 요청 트리거 |
| 5 | 그대로 | 그대로 | 이전 트랜잭션 결과 유지 | 이전 결과 | SUCCEEDED (재사용) | Toss 호출 없음 |
| 6 | 그대로 | 그대로 | PENDING | PENDING | FAILED (변화 없음) | 예외 응답 |
| 7 | 자연 만료 | 자연 만료 | PENDING (배치 정리) | PENDING (배치 정리) | — | 유저에게 재예약 안내 |
| 8 | 그대로 | 그대로 | 이미 ORDERED | 이미 PAID | — | 중복 결제 방어 |
| 9 | 그대로 | 그대로 | 그대로 | 그대로 | — | 위변조/버그 방어 |
| 10 | 그대로 | 그대로 | 그대로 | 그대로 | — | Toss 응답 검증 실패 |
| 11 | DEL | HDEL | UPDATE 취소 상태 | CANCELLED | — | #259 취소 도메인 |
| 12 | 상황 따라 | 상황 따라 | 대사 결과 | 대사 결과 | 정정 | 배치, 유저 응답 없음 |
| 13 | 그대로 | 그대로 | PENDING | PENDING | 새 attempt 생성 안 됨 | TX A `findLatestApprovalByPaymentId`가 이전 IN_PROGRESS 발견해 차단 |
| 14 | 그대로 | 그대로 | 이미 ORDERED | 이미 PAID | 새 attempt 생성 안 됨 | TX A가 SUCCEEDED 발견 후 차단 (다른 요청이 먼저 확정) |
| 15 | 그대로 | 성공 시 R→(후속 B) | 성공 시 ORDERED · 실패 시 PENDING | 성공 시 PAID · 실패 시 PENDING | 하나로만 확정 (SUCCEEDED or FAILED) | 두 스레드가 동시 TX B 진입해도 락으로 직렬화 |
| 16 | 그대로 | 그대로 | PENDING | PENDING | 하나로만 FAILED | markFailed 두 번 호출돼도 idempotency로 no-op |

## 케이스별 재시도 정책

| 케이스 | 자동 재시도 | 유저 재시도 UX |
|---|---|---|
| 2 (카드 거절) | 안 함 | 다른 카드로 시도 → 새 결제창 → 새 Attempt |
| 3 (인라인 재조회) | 2~3회 GET 재조회 (총 <1s) | 실패 시 4로 진행 |
| 4 (유저 재시도) | 매 재요청 시 Toss GET | 재시도 안내 응답 시 유저가 다시 클릭 |
| 5 (재사용) | 즉시 이전 결과 응답 | 유저 관점에서 재시도 성공한 것처럼 보임 |
| 13 (새 세션 IN_PROGRESS) | 안 함 | 이전 결제 결과 대기 안내 |
| 14 (SUCCEEDED race) | 안 함 | 이미 완료된 결제 안내 |
| 15 (TX B 동시 진입) | 방어 매커니즘이 자동 처리 (락 + status) | 유저에겐 하나의 응답만 반환 |
| 16 (markFailed 경합) | 방어 매커니즘이 자동 처리 (idempotency) | 두 요청 모두 실패로 일관 응답 |

## 각 케이스의 로그 관측 지점

| 케이스 | 주요 로그 라인 |
|---|---|
| 정상 | `[결제 승인 완료] paymentId=... orderCode=...` |
| 카드 거절 | `PaymentApprovalFailureHandler.fail` + Toss error_code 저장 |
| 결과 불명 인라인 | (구현 예정) Toss GET 재조회 attempt 수 카운트 |
| 유저 재시도 | `[결제 재요청 - SUCCEEDED attempt 재사용]` |
| 중복 결제 | `PAYMENT_ALREADY_COMPLETED` 예외 |
| 금액 불일치 | `[금액 불일치] 요청 금액 != Order 금액` |

## 개발 시 참고

- 새 케이스 추가 시 이 파일의 케이스 목록 · 상태 전이 · 재시도 정책에 각각 행 추가
- 필요하면 archify로 다이어그램 생성 → 목록에 링크 추가
- 케이스 넘버는 안 바꾸는 것을 원칙으로 함 (문서·PR·이슈에서 "케이스 3" 등으로 참조)
