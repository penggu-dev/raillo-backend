# MSA Transition

raillo-backend-v2를 MSA로 전환하기 위한 설계 결정과 근거를 정리한다. 물리 분리 이전에 갖춰야 할 이벤트 계약과 인프라(Outbox, Kafka 등)를 먼저 마련한다.

## 배경

raillo는 현재 6개 도메인(`auth`, `member`, `payment`, `booking`, `order`, `train`, `global`)이 한 애플리케이션에서 트랜잭션을 공유하는 모듈러 모놀리스다. 결제 흐름은 `PaymentFacade.confirmPayment`가 오케스트레이션하며, Toss 승인부터 예매 확정·좌석 확정까지 한 트랜잭션 안에서 처리된다.

이 구조는 정합성 관점에서 안전하지만 다음 한계가 있다.

- 외부 API(Toss, Slack, 이메일 등) 실패가 결제 트랜잭션에 영향을 준다 — dual-write 문제.
- 알림·영수증 등 사후 처리 실패 시 유실 위험이 있다.
- Toss 웹훅 재전송·중복 처리 대응 지점이 명확하지 않다.
- 도메인 간 이벤트 계약이 없어 MSA 분리 준비가 되지 않는다.

## 원칙

### 크리티컬 패스와 사후 흐름의 분리

**크리티컬 패스**는 사용자가 응답을 기다리는 동안 반드시 성공해야 하는 흐름이다. 이 프로젝트에서는 `Toss 승인 → Payment PAID → Order ORDERED → Booking 생성 → Ticket 발급 → Seat Hold 해제`가 크리티컬 패스에 해당한다. 사용자 관점에서 "결제 완료 = 예매 완료"여야 하므로 동기 오케스트레이션과 실패 시 즉시 롤백으로 처리한다.

**사후 흐름**은 사용자 응답 이후 실행돼도 되는 작업이다. 알림, 이메일 영수증, 승차권 PDF, 마일리지, 감사 로그 등이 이에 해당한다. Outbox 패턴과 이벤트 발행을 통해 크리티컬 패스에서 격리한다.

### Prep-first 하이브리드 접근

물리 분리(별도 배포·별도 DB)를 먼저 시도하면 분산 트랜잭션과 운영 부담이 한꺼번에 밀려온다. 다음 순서로 접근한다.

1. **Common 모듈 추출** — 이후 모든 서비스가 공유할 예외·응답 래퍼·JWT 유틸을 Gradle multi-module로 분리.
2. **이벤트 계약 정립** — Outbox 도입, 도메인 이벤트 정의, Kafka 발행 인프라 구축. 모놀리스 내부에서 검증.
3. **Auth+Member 물리 분리** — 커플링이 낮은 첫 후보. Common 모듈과 이벤트 계약을 재사용.
4. **사후 흐름 소비자 확장** — Email·PDF·웹훅.
5. **환불 Saga·Payment 물리 분리** — 가장 복잡한 흐름은 나머지 인프라가 검증된 뒤 진행.

이 순서의 근거는 Payment 분리가 가장 위험하므로 **이벤트·재시도·멱등성 패턴을 그 이전에 실제 트래픽으로 검증**하는 데 있다. Auth+Member 분리는 이벤트 인프라 없이도 가능하지만, 사전에 준비된 이벤트 계약이 있으면 Auth 이벤트(`MemberCreated` 등)를 처음부터 자연스럽게 발행할 수 있다.

## 로드맵

> **2026-08-18 업데이트** — outbox 계열 단계(1·3·4·5)는 MSA 전환의 핵심 흐름인 서비스 물리 분리를 먼저 완료한 뒤 재검토한다. Auth+Member 분리(2)는 outbox 의존이 없어 독립 진행 가능. Common 모듈 추출(0)은 진행 중.

| Phase | 목표 | 상태 |
|---|---|---|
| 0 | Common 모듈 추출 (Gradle multi-module) | 구현 완료 (2 PR 병합 대기 중) |
| 1 | Outbox 인프라, 결제 이벤트 발행, Slack 소비자 (MVP) | 유예 — 실제 소비자 요구 시점까지 |
| 2 | Auth+Member 물리 분리 | 설계 예정 |
| 3 | 이메일 영수증·승차권 PDF 소비자 | 유예 (1 의존) |
| 4 | Toss 웹훅 인바운드 큐잉 | 유예 (1 의존) |
| 5 | 환불/취소 Saga | 유예 (1 의존) |
| 6 | Payment 물리 분리 | 확정, 준비 조건 충족 후 |

## 세부 문서

### 전략·경계

- [domain-boundary-analysis.md](./domain-boundary-analysis.md) — 도메인 결합도, 분리 우선순위, 좌석 가용성 경계.
- [data-consistency-strategy.md](./data-consistency-strategy.md) — 2PC와 eventual consistency, Outbox·@TransactionalEventListener·Saga 원칙.
- [idempotency-strategy.md](./idempotency-strategy.md) — 3중 방어 구조와 지점별 적용.
- [common-module-strategy.md](./common-module-strategy.md) — 공통 모듈 공유 전략.
- [hexagonal-architecture.md](./hexagonal-architecture.md) — Ports & Adapters 구조와 점진 마이그레이션 전략.

### 결제 도메인 설계

- [payment-outbox-design.md](./payment-outbox-design.md) — 결제 도메인 Outbox 설계 (Phase 1 MVP).

### 실행 계획

- [implementation-roadmap.md](./implementation-roadmap.md) — Phase별 태스크 순서, 의존 관계, Definition of Done, 롤백 전략.

### 인프라·운영

- [circuit-breaker-strategy.md](./circuit-breaker-strategy.md) — 외부 호출 서킷 브레이커·bulkhead·timeout 정책.
- [observability-strategy.md](./observability-strategy.md) — 분산 추적, 로그 통합, 메트릭.
- [config-management.md](./config-management.md) — 설정 관리 도구 선택.
- [containerization.md](./containerization.md) — Docker 이미지 빌드, JVM 메모리, graceful shutdown.

## 예정 문서

- `refund-saga-design.md` — 환불/취소를 이벤트 기반 saga로 설계.
- `webhook-inbound-design.md` — Toss 웹훅 수신 큐잉·멱등성·재수신.
- `service-decomposition-plan.md` — Auth+Member 물리 분리 실행 계획.
