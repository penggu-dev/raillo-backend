# Implementation Roadmap

MSA 전환 각 Phase의 구현 순서, 태스크 의존 관계, Definition of Done, 롤백 전략을 정리한다. `README.md`의 로드맵이 macro-level이라면 이 문서는 각 Phase 안의 태스크 순서와 리스크를 다룬다.

이 문서는 태스크 단위 파일카드 계획서가 아니다. 그 수준의 계획서는 별도 문서(예: `docs/superpowers/plans/YYYY-MM-DD-<topic>.md`)에서 다룬다.

## 문서 관계

```
README.md (macro roadmap)
    │
    └─▶ implementation-roadmap.md (Phase별 태스크 순서·의존·DoD)
            │
            └─▶ docs/superpowers/plans/*.md (태스크별 파일카드 구현 계획서)
```

## 순서와 근거

전체 순서는 Payment 물리 분리를 가장 마지막으로 두는 하이브리드 접근이다. Payment 분리는 리스크가 가장 크므로 이벤트·재시도·멱등성·서킷 브레이커 같은 인프라 패턴이 실전에서 검증된 뒤에 진행한다.

```
Phase 0 (Common)
    │
    ▼
Phase 1 (Outbox MVP)  ────┐
    │                     │
    ▼                     │
Phase 2 (Auth+Member)     │  이벤트 인프라를 Auth 분리에서 재사용
    │                     │
    ▼                     │
Phase 3 (Email/PDF)  ─────┤  Outbox 패턴 확장
    │                     │
    ▼                     │
Phase 4 (Webhook)  ───────┤  Outbox 패턴 확장
    │                     │
    ▼                     │
Phase 5 (Refund Saga) ────┤  다중 도메인 이벤트 체인
    │                     │
    ▼                     │
Phase 6 (Payment split) ──┘  모든 패턴 검증 후 실행
```

## 공통 원칙

- **각 Phase는 이전 Phase의 인프라 위에서 확장한다.** Phase 1 인프라를 Phase 3~5가 재사용한다.
- **신규 코드는 헥사고날로 작성한다.** [hexagonal-architecture.md](./hexagonal-architecture.md) 참조.
- **각 태스크는 독립 배포 가능한 단위로 마무리한다.** 반쯤 완성된 상태로 다음 태스크로 넘어가지 않는다.
- **Definition of Done**: 테스트 통과 + 문서 반영 + 프로덕션 배포 안전성 확인.

## Phase 0 — Common 모듈 추출

이후 모든 서비스 분리에서 재사용할 공통 코드를 Gradle multi-module로 분리한다. MSA 물리 분리와 무관하게 단독으로 완결되며, 이후 어떤 Phase에도 재활용된다.

### 태스크 순서

**Step 1: `common/` Gradle 모듈 뼈대**
- `settings.gradle`에 `include ':common'` 추가.
- `common/build.gradle`에 최소 의존성만 (Spring core, jakarta.persistence-api 등).
- 도메인 서비스는 아직 이관 안 함.
- **DoD**: `./gradlew :common:build` 성공, 기존 앱 빌드도 정상.

**Step 2: 공통 예외 계층 이관**
- `ErrorCode` 인터페이스를 `common`으로 이동.
- `BaseBusinessException`, `BaseDomainException`, `BaseExternalApiException`를 `common`으로 이관.
- 기존 도메인별 `BookingError`, `PaymentError` 등은 그대로 두되 `common`의 `ErrorCode`를 참조.
- **DoD**: 전 도메인 컴파일·테스트 통과. 에러 응답 포맷 회귀 없음.

**Step 3: 공통 응답 래퍼·페이지네이션 DTO 이관**
- `ApiResponse<T>`, `ErrorResponse`, `PageResponse`, `SortRequest` 등.
- **DoD**: 컨트롤러 응답 스키마 회귀 없음, Swagger 문서 정상.

**Step 4: JWT 유틸 이관 (선택)**
- Auth 도메인의 JWT 파싱·검증 유틸이 여러 서비스에서 필요할 예정이면 미리 이관.
- Phase 2 Auth 분리 시점까지 미루는 것도 가능.
- **DoD**: Auth 필터 정상 작동.

**Step 5: `CLAUDE.md` 업데이트**
- 표준 패키지 구조에 `common/` 모듈 위치 반영.
- 공통 모듈에 담을 것·담지 말 것 원칙 명시 ([common-module-strategy.md](./common-module-strategy.md) 참조).
- **DoD**: 새 도메인 개발 시 참조 가능한 상태.

### 위험 지점

| 위험 | 완화 |
|---|---|
| 도메인 로직이 `common`으로 유입 | PR 리뷰 기준을 [common-module-strategy.md](./common-module-strategy.md)에 명시. |
| 순환 참조 발생 | `common`은 어떤 도메인 모듈에도 의존하지 않도록 build.gradle로 강제. |

### 롤백 전략

멀티 모듈 리팩터가 문제를 일으키면 Git revert로 되돌린다. 배포 파이프라인은 변경 없음 (여전히 단일 아티팩트).

## Phase 1 — Outbox 인프라 + Slack Consumer (MVP)

> **2026-08-18 유예 결정** — MSA 전환의 핵심 흐름인 서비스 물리 분리를 먼저 완료한 뒤 이 단계를 재검토한다. 현재 결제 흐름은 크리티컬 패스만으로 충분하며, 소비자 1개(Slack)를 위해 12 스텝 인프라(포트·어댑터·재시도·DEAD·모니터링)를 짓는 것은 시점상 과잉 스코프로 판단. Phase 3·4·5는 이 단계의 outbox 인프라에 의존하므로 함께 유예된다. Phase 2(Auth+Member 분리)는 outbox 무관, 독립 진행 가능. Phase 6(Payment 분리)의 "이벤트 기반 발행 안정" 준비 조건은 재개 시 재조정한다. 아래 상세 계획은 재개 시 재사용을 위해 그대로 보존한다.

Phase 1은 outbox 인프라 도입, 3종 이벤트 발행, Slack 소비자 1종 구현으로 구성된다.

### 태스크 순서

**Step 1: 로컬 인프라 준비**
- 로컬 docker-compose에 Kafka 브로커 추가.
- `payment-events`, `booking-events` topic 생성 자동화.
- Kafka Producer/Consumer 설정 클래스 작성.
- **DoD**: `./gradlew bootRun` 후 Kafka UI 또는 CLI로 topic·broker 정상 확인.

**Step 2: Outbox 도메인·포트 정의 (헥사고날)**
- `application/port/out`: `OutboxEventRepository`, `DomainEventPublisher` 인터페이스.
- `application/port/in`: `PublishOutboxEventUseCase`, `PickPendingOutboxBatchUseCase`.
- `domain`: `OutboxEvent`, `EventStatus`, `AggregateType`.
- **DoD**: Port 인터페이스만으로 유스케이스 계약 표현. 구현체 없이 컴파일 가능.

**Step 3: Outbox 테이블 마이그레이션**
- `V{N}__create_outbox_event.sql` 작성.
- 인덱스 3개 포함 (`idx_status_created`, `idx_aggregate`, `idx_next_retry`).
- **DoD**: local·test 환경 테이블 생성 확인, 인덱스 존재 검증.

**Step 4: Outbox Out Adapter 구현**
- `adapter/out/persistence/JpaOutboxEventRepository`. `SELECT ... FOR UPDATE SKIP LOCKED` 쿼리 포함.
- `adapter/out/kafka/KafkaDomainEventPublisher`. 동기/비동기 두 메서드 노출.
- **DoD**: Repository·Publisher 단위 테스트 통과 (H2 + Testcontainers Kafka).

**Step 5: Publisher / Poller 유스케이스**
- `application/service/PublishOutboxEventService`, `PickPendingOutboxBatchService`.
- `adapter/in/scheduler/OutboxPollingScheduler`.
- `adapter/in/event/OutboxImmediatePublishListener` (`@TransactionalEventListener(AFTER_COMMIT)`).
- 즉시 Publisher는 동기+timeout, Polling Publisher는 비동기+callback.
- **DoD**: 즉시 발행 성공/실패, 폴링 재발행, 복구 poller 시나리오 통합 테스트 통과.

**Step 6: PaymentConfirmed 발행 연결**
- `PaymentFacade.confirmPayment` 성공 커밋 지점에 이벤트 발행.
- `PaymentConfirmedPayload` DTO 정의 (`schemaVersion` 포함).
- **DoD**: 결제 승인 시 outbox row 생성 → Kafka `payment-events` 발행 확인.

**Step 7: PaymentFailed 발행 연결**
- `PaymentService.failPaymentInNewTransaction` 커밋 지점에 이벤트 발행.
- **DoD**: Toss 승인 실패 케이스에서 outbox row 생성·발행 확인.

**Step 8: BookingConfirmed 발행 연결**
- `createBookingFromOrder` 이후 이벤트 발행 (PaymentConfirmed와 같은 트랜잭션).
- Payload에 좌석·정차역 스냅샷 포함.
- **DoD**: 결제 승인 성공 시 두 이벤트 발행·순서 보장 확인.

**Step 9: Slack Consumer 구현**
- `adapter/in/kafka/SlackAlertConsumer`. `payment-events` 구독, `PaymentFailed`만 필터.
- 3중 방어 (Redis dispatch-lock + `processed_events` unique + Payment 상태 재조회).
- **DoD**: `PaymentFailed` 발행 시 Slack 알림 도착. 중복 소비 방어 통합 테스트 통과.

**Step 10: 재시도·DEAD 로직**
- 실패 분류 (4xx / 5xx / timeout / 파싱 실패).
- `RETRY_WAIT`·`nextRetryAt` 저장, backoff 10s → 20s → 30s. 3회 초과 시 `DEAD`.
- DEAD 상태 배치 Slack 알림.
- **DoD**: Slack API 강제 실패 케이스에서 재시도 → DEAD 전환 → DEAD 알림 확인.

**Step 11: 모니터링·로그 표준**
- Outbox status별 카운트, 발행 지연 히스토그램, Kafka lag 지표.
- 로그 표준 필드 준수 확인 ([observability-strategy.md](./observability-strategy.md)).
- **DoD**: Prometheus에서 outbox 지표 조회 가능, 로그 표준 필드 포함 확인.

**Step 12: 문서·릴리즈**
- 설계 문서 구현 결과 반영 (특이사항·발견 이슈).
- 배포 매니페스트(K8s) 갱신.
- **DoD**: 프로덕션 배포 후 실 트래픽에서 outbox row 생성·발행 확인.

### 태스크 의존 관계

```
Step 1 (인프라)
    │
    ▼
Step 2 (Port·도메인) ──┬─▶ Step 4 (Adapter) ──┐
    │                  │                       │
    ▼                  ▼                       ▼
Step 3 (마이그레이션)   Step 5 (Publisher·Poller)
                              │
                              ▼
                       Step 6 (PaymentConfirmed)
                              │
                              ▼
                       Step 7·8 (PaymentFailed / BookingConfirmed)
                              │
                              ▼
                       Step 9 (Slack Consumer)
                              │
                              ▼
                       Step 10 (재시도·DEAD)
                              │
                              ▼
                       Step 11 (모니터링) ──▶ Step 12 (릴리즈)
```

Step 2·3 병렬 가능. Step 7·8 병렬 가능. 나머지는 순차.

### 위험 지점

| 위험 | 완화 |
|---|---|
| `@TransactionalEventListener` 트랜잭션 전파 버그 | Step 5에서 `REQUIRES_NEW` 사용 강제. 트랜잭션 phase 단위 테스트로 검증. |
| Poller가 잠긴 row를 오래 홀드해 스루풋 저하 | Step 5에서 락 트랜잭션 안에는 상태 변경만, Kafka 발행은 이후 별도 트랜잭션. |
| 콜백 실패로 outbox가 `PROCESSING`에 잔류 | Step 5의 복구 poller가 `PENDING`으로 복귀. |
| Slack API 급증 실패로 알림 폭주 | Step 10에서 DEAD 알림은 배치·집계형. |
| 프로덕션 첫 배포 시 이벤트 폭주 | Step 12 배포 전 staging에서 realistic 부하 검증. |

### 롤백 전략

- **Step 6~9 실패**: 이벤트 발행 지점의 `publishEvent(...)` 호출을 제거하면 기존 흐름과 동일.
- **Kafka 브로커 장애**: outbox row가 남으므로 브로커 복구 후 폴링이 재발행. 결제 흐름 무영향.
- **Slack Consumer 오작동**: Consumer만 정지시켜도 다른 흐름은 정상.

## Phase 2 — Auth + Member 물리 분리

Common 모듈과 이벤트 계약이 준비된 상태에서 첫 물리 분리를 진행한다.

### 태스크 outline

1. `service-decomposition-plan.md` 별도 문서 작성.
2. Auth+Member 도메인을 신규 Gradle 모듈(`auth-service/`)로 분리, 헥사고날 재구성.
3. 별도 저장소 또는 별도 배포 파이프라인 구성 (판단 필요).
4. API Gateway 또는 Ingress에서 JWT 검증 이관.
5. 회원 데이터 접근 전략 결정 (DB 공유 유지 vs 분리 + REST/gRPC).
6. `MemberCreated`, `MemberUpdated` 등 이벤트 발행 (Phase 1 outbox 재사용).
7. 나머지 서비스가 회원 조회 시 Redis 캐시 + REST/gRPC로 전환.
8. 관측성·트레이싱 cross-service 동작 확인.

### 의존

- Phase 0 완료 (common 모듈).
- Phase 1 완료 (outbox 인프라, 이벤트 계약).

### 재검토 항목 (Phase 0에서 유예된 결정)

- **`raillo-core`의 bootRun `workingDir` 우회 제거** — Phase 0에서 raillo-core가 아직 모놀리스 전체라 compose.yaml·.env가 루트에 있고, bootRun의 작업 디렉토리를 프로젝트 루트로 고정하는 우회를 두었다. Auth·Member를 자체 폴더로 분리하는 이 단계에서, 각 서비스가 자기 폴더에 compose.yaml·.env를 두는 구조로 정리하고 이 우회를 제거한다.

### 위험 지점

| 위험 | 완화 |
|---|---|
| Auth 서비스 장애가 전체 API 접근 불가로 이어짐 | JWT 검증을 Gateway에 이관해 서비스 호출 없이도 검증 가능. |
| Member 조회 트래픽이 병목 | Redis 캐시 + TTL. cache-aside 패턴. |
| DB 분리 시 대량 마이그레이션 필요 | 초기에는 DB 공유 유지 (스키마 소유권만 명확화), 실측 뒤 분리 판단. |

### 롤백 전략

- 배포 파이프라인은 두 서비스로 나뉘어 있으므로 Auth+Member만 이전 버전으로 롤백 가능.
- 최악의 경우 Gateway 설정에서 라우팅을 원래 모놀리스로 되돌려 한 서비스로 회귀.

### DoD

Auth+Member가 독립 서비스로 배포·롤백 가능. 다른 서비스가 회원 조회·JWT 검증에서 이관된 서비스를 정상 사용.

## Phase 3 — 이메일 영수증 + 승차권 PDF Consumer

### 태스크 outline

1. Email Sender / PDF Generator 외부 API 조사·계약.
2. `application/port/out/EmailSender`, `PdfGenerator` 인터페이스 정의.
3. `adapter/out/external`에 어댑터 구현.
4. Email Consumer (`PaymentConfirmed` 구독).
5. PDF Consumer (`BookingConfirmed` 구독).
6. 각 Consumer 3중 방어 적용.
7. 재시도·DEAD 정책 적용 (Phase 1 재사용).
8. Slack 소비자에 일 요약 스케줄러 추가.

### 의존

Phase 1 완료. `outbox_event`, Publisher, Poller, Consumer 프레임 재사용.

### DoD

실제 결제·예매 완료 시 사용자에게 이메일·PDF 도착 확인. 외부 API 강제 장애 시 재시도·DEAD 흐름 확인.

## Phase 4 — Toss 웹훅 인바운드

### 태스크 outline

1. `POST /webhooks/toss` 엔드포인트.
2. `webhook_event` 테이블 (`event_id` unique, payload JSON).
3. 웹훅 수신 즉시 저장 + outbox에 `TossWebhookReceived` 발행.
4. Toss가 즉시 200 응답 받도록 처리 (동기 저장만, 후속은 비동기).
5. `toss-webhook-events` topic + Consumer.
6. Consumer가 웹훅 payload를 해석해 Payment 상태 보정.
7. 웹훅 재전송·순서 뒤바뀜 대응 멱등성 (3중 방어).

### 의존

Phase 1 완료. `outbox_event`, Kafka Consumer 프레임 재사용.

### DoD

Toss가 재전송한 웹훅에서도 결제 상태가 idempotent하게 유지됨.

## Phase 5 — 환불 / 취소 Saga

### 태스크 outline

1. `refund-saga-design.md` 별도 문서 작성.
2. `RefundRequested` 이벤트 스키마 정의.
3. Payment가 Toss 취소 API 호출 후 `PaymentCancelled` 발행.
4. Booking이 `PaymentCancelled` 구독 → SeatBooking 취소, Ticket CANCELLED → `BookingCancelled` 발행.
5. Notification 소비자가 `BookingCancelled` 구독 → 사용자 알림.
6. 각 단계 실패 처리 (보상 트랜잭션 또는 DEAD).
7. 부분 환불 지원 검토 (`payment_refund` 엔티티).

### 의존

Phase 1~4 완료. 이벤트 스키마·재시도·멱등성 안정화.

### DoD

전액 환불 e2e 시나리오 통과. 각 단계 실패 케이스에서 보상 흐름 검증.

## Phase 6 — Payment 물리 분리

### 준비 조건

다음이 모두 만족될 때 진행한다.

- Phase 0~5 안정 운영.
- 오케스트레이션 책임이 `Order/Checkout` 유스케이스로 리팩터 완료. Payment 도메인은 "Toss 승인·취소·환불·웹훅·결제 상태 관리"에만 집중된 상태.
- Toss 관련 이벤트(승인·취소·환불·웹훅)가 이미 outbox 기반으로 발행 중이며 소비자가 안정.
- 환불 Saga가 실전에서 검증된 상태.

준비 조건이 부족하면 그 조건을 먼저 충족한 뒤 진행한다.

### 태스크 outline

1. Payment 서비스 별도 저장소 또는 별도 모듈로 분리.
2. Order/Booking이 Payment를 sync REST/gRPC로 호출하도록 계약 정의.
3. Toss 호출·상태 관리·환불·웹훅을 Payment 서비스가 단독 소유.
4. `payment_outbox`(Payment 서비스 소유 DB) + 기존 Kafka topic 재사용.
5. 결제 승인 크리티컬 패스 e2e 검증 (Order → Payment → Booking).
6. 보안·감사 스코프 격리 확인.

### 위험 지점

| 위험 | 완화 |
|---|---|
| Order → Payment sync 호출의 지연·장애가 크리티컬 패스 확장 | [circuit-breaker-strategy.md](./circuit-breaker-strategy.md)의 크리티컬 패스형 정책 적용. |
| 결제 승인 실패 시 분산 롤백 필요 | Payment 실패는 예외로 반환, Order/Booking이 로컬 롤백. Saga 미사용. |
| Payment DB 분리 시 스키마 이관 리스크 | 초기에는 스키마 소유권만 분리, 물리 DB 분리는 실측 뒤. |

### DoD

Payment가 독립 서비스로 배포·롤백 가능. 결제 크리티컬 패스가 두 서비스 sync 호출로도 SLA 유지. Toss 이벤트 소비자가 이관된 상태에서 정상 동작.

## Definition of Done 공통

각 태스크는 다음을 만족해야 다음 태스크로 넘어간다.

- 유닛·통합 테스트 작성 및 통과.
- 로그·지표에 표준 필드 포함.
- 관련 문서(설계·운영) 업데이트.
- 롤백 시나리오 검증 (자동 롤백 불가한 경우 수동 절차 문서화).
- Staging 환경에서 realistic 트래픽 검증 후 프로덕션 배포.

## 참고

- [../README.md](./README.md) — Macro 로드맵.
- [payment-outbox-design.md](./payment-outbox-design.md) — Phase 1 상세 설계.
- [hexagonal-architecture.md](./hexagonal-architecture.md) — 신규 코드의 구조 원칙.
- [common-module-strategy.md](./common-module-strategy.md) — Phase 0 공통 모듈 원칙.
