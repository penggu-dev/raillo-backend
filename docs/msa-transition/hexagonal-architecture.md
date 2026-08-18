# Hexagonal Architecture

Ports & Adapters 원칙을 이 프로젝트에 어떻게 도입할지 정한다. 인프라 교체(Kafka ↔ Redis Stream, MySQL ↔ PostgreSQL, JPA ↔ JOOQ 등)를 도메인 로직 수정 없이 수행하고, 도메인을 프레임워크 의존성에서 격리한다.

## 동기

현재 구조도 계층 분리는 되어 있지만, `infrastructure` 계층이 `application`이 요구하는 인터페이스를 명시적으로 구현하지 않는다. 예: `PaymentRepository`가 `application.service` 안에서 참조되지만 그 인터페이스가 `application/port/out`에 있지 않고 `infrastructure`에 있다. 결과적으로 다음이 어렵다.

- **인프라 교체 시 도메인 재조립 필요**: Kafka Producer를 Redis Stream으로 바꾸려면 호출 지점을 모두 수정해야 한다. Producer 구현체가 인터페이스 뒤에 숨어 있지 않기 때문이다.
- **도메인 순수성 보장 어려움**: `domain` 안에 JPA 어노테이션이 섞이거나 `application`이 `infrastructure`를 직접 참조하는 경로가 생긴다.
- **테스트에서 mock 대체 어려움**: 인터페이스 없이 구현체를 직접 대체해야 하므로 테스트 세팅이 무거워진다.

Ports & Adapters는 이 문제를 다음 원칙으로 해결한다.

- 도메인·유스케이스는 순수하다 (프레임워크·인프라 무관).
- 외부와의 접점은 **Port(인터페이스)**로만 노출된다.
- 실제 구현은 **Adapter(구현체)**로 별도 패키지에 분리된다.
- Adapter 교체는 Port 뒤에서 이뤄지며 도메인·유스케이스에 영향이 없다.

## 개념

```
                [ In Adapter ]           [ Out Adapter ]
                     │                          │
                     ▼                          ▲
              [ In Port ] ──▶ [ Application ] ──▶ [ Out Port ]
              (Use Case)      (Domain + Service)  (Repository,
                                                   Publisher,
                                                   External Client)
```

- **In Adapter**: 시스템으로 들어오는 요청을 받는다 (REST Controller, Kafka Listener, Scheduler 등).
- **In Port**: Adapter가 호출하는 유스케이스 인터페이스. 애플리케이션이 "무엇을 할 수 있는가"를 표현.
- **Application**: 유스케이스 구현. 도메인 로직을 조립·오케스트레이션.
- **Out Port**: Application이 외부에 요구하는 능력의 인터페이스. Repository, Publisher, External Client 등.
- **Out Adapter**: Out Port의 실제 구현 (JPA Repository, Kafka Producer, HTTP Client 등).

## 현재 구조 vs 헥사고날 구조

**현재 (CLAUDE.md 표준)**

```
{domain}/
├── presentation/         # REST controllers
├── application/
│   ├── service/          # 
│   ├── facade/           #
│   ├── dto/, mapper/, validator/, calculator/, generator/
├── domain/               # entities
├── infrastructure/       # JPA/QueryDSL/Redis Repository, External Client
├── exception/
└── success/
```

**헥사고날 (제안)**

```
{domain}/
├── adapter/
│   ├── in/
│   │   ├── web/          # REST Controllers (@RestController)
│   │   ├── kafka/        # Kafka Listeners
│   │   └── scheduler/    # 스케줄러 진입점
│   └── out/
│       ├── persistence/  # JPA/QueryDSL 구현체
│       ├── kafka/        # Kafka Producer 구현체
│       ├── redis/        # Redis 구현체
│       └── external/     # Toss 등 외부 HTTP 클라이언트
├── application/
│   ├── port/
│   │   ├── in/           # 유스케이스 인터페이스 (Command/Query/UseCase)
│   │   └── out/          # Repository/Publisher/Client 인터페이스
│   ├── service/          # 유스케이스 구현 (In Port 구현)
│   ├── dto/, mapper/, validator/, calculator/, generator/
├── domain/               # 순수 엔티티·VO·도메인 서비스
├── exception/
└── success/
```

## Port/Adapter 예시 (Payment Outbox)

Outbox 발행 흐름을 헥사고날로 표현한 예시.

### Out Port (application/port/out)

```java
// 도메인 이벤트 발행 능력 (Kafka인지 Redis Stream인지 모른다)
public interface DomainEventPublisher {
    PublishResult publish(OutboxEvent event);
}

// Outbox 조회·상태 변경 능력 (JPA인지 JOOQ인지 모른다)
public interface OutboxEventRepository {
    Optional<OutboxEvent> findById(Long id);
    List<OutboxEvent> pickPendingBatch(int size);
    void markAsPublished(Long id);
    void markAsPending(Long id, String lastError);
}
```

### Application Service (application/service)

```java
@Service
@RequiredArgsConstructor
public class PublishOutboxEventService implements PublishOutboxEventUseCase {

    private final OutboxEventRepository outboxRepository;   // Out Port
    private final DomainEventPublisher publisher;            // Out Port

    @Transactional
    @Override
    public void execute(Long eventId) {
        OutboxEvent event = outboxRepository.findById(eventId)
            .orElseThrow(...);
        
        PublishResult result = publisher.publish(event);
        if (result.isSuccess()) {
            outboxRepository.markAsPublished(eventId);
        } else {
            outboxRepository.markAsPending(eventId, result.error());
        }
    }
}
```

### Out Adapter (adapter/out/kafka)

```java
@Component
@RequiredArgsConstructor
public class KafkaDomainEventPublisher implements DomainEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public PublishResult publish(OutboxEvent event) {
        try {
            var record = new ProducerRecord<>(
                topicFor(event.aggregateType()),
                event.aggregateId(),
                event.payload()
            );
            kafkaTemplate.send(record).get(3, TimeUnit.SECONDS);
            return PublishResult.success();
        } catch (Exception e) {
            return PublishResult.failure(e.getMessage());
        }
    }
}
```

### 인프라 교체 시 변화

Kafka → Redis Stream 교체가 필요한 경우, 다음만 수정한다.

- 신규: `adapter/out/redis/RedisStreamDomainEventPublisher implements DomainEventPublisher`
- 기존 `KafkaDomainEventPublisher`는 제거 또는 프로파일별 활성화.
- `PublishOutboxEventService`는 수정 없음.
- `OutboxEvent` 도메인도 수정 없음.

MySQL → PostgreSQL 교체는 JPA를 계속 쓰면 자동 대응. JPA → JOOQ 교체 시에는 `JooqOutboxEventRepository implements OutboxEventRepository` 신규 구현으로 대응.

## 마이그레이션 전략

전체 도메인을 한 번에 재구성하지 않는다. **새 코드부터 헥사고날, 기존 코드는 접촉 시점에 점진 이관**.

### Phase별 적용

| Phase | 적용 범위 | 근거 |
|---|---|---|
| Phase 0 | Common 모듈 자체는 순수 인터페이스·베이스 클래스 위주 | 헥사고날 도구(Port 인터페이스) 자체가 여기 담김. |
| Phase 1 | **Outbox 인프라 신규 코드는 헥사고날로 작성** | 새 인프라라 마이그레이션 부담 없음. Publisher·Repository·Consumer가 모두 Port로 노출되면 이후 교체·테스트 유리. |
| Phase 2 | **Auth+Member 물리 분리 시 해당 도메인 헥사고날 재구성** | 어차피 서비스 경계 재정의하므로 함께 리팩터. |
| Phase 3~4 | 이메일·PDF·웹훅 소비자도 헥사고날로 신규 작성 | 신규 어댑터 추가 방식. |
| Phase 5 | 환불 Saga 관련 코드도 헥사고날 | 신규 유스케이스. |
| Phase 6 | Payment 물리 분리 시 헥사고날 재구성 | 크리티컬 패스 리팩터 시점에 병행. |

### 원칙

- **기존 도메인 강제 재구성 금지**: 접촉하지 않는 도메인은 현재 구조 유지.
- **새 코드는 무조건 헥사고날**: Phase 1 이후 신규 유스케이스·어댑터는 예외 없이 헥사고날.
- **혼재 허용**: 프로젝트 전체가 하이브리드 상태를 얼마간 유지. 문제 아님.

## CLAUDE.md 표준과의 관계

현재 `CLAUDE.md`는 `presentation/application/domain/infrastructure` 4계층 표준을 명시한다. 헥사고날 도입은 이 표준과 충돌하므로 다음 방식으로 정리한다.

- **당분간 두 표준이 공존**한다. 기존 도메인은 4계층, 신규 코드는 헥사고날.
- Phase 1 완료 시점에 `CLAUDE.md`를 업데이트해 "**신규 도메인·신규 유스케이스는 헥사고날, 기존 도메인은 접촉 시 이관**"을 명시.
- 각 도메인이 완전 이관되면 CLAUDE.md에서 해당 도메인의 4계층 참조를 제거.
- 마지막 도메인 이관 완료 시 4계층 표준 자체를 제거.

이 문서 자체가 헥사고날 표준의 근거 문서 역할을 한다.

## 트레이드오프

**얻는 것**

- 인프라 교체가 국소적 (Adapter 갈아끼움).
- 도메인·유스케이스 테스트가 순수 단위 테스트로 가능 (Port를 mock).
- 도메인 계층이 프레임워크 의존성에서 격리.
- MSA 분리 시 서비스 경계가 명확 (Application 자체를 통째로 이관).

**잃는 것**

- 인터페이스 레이어 하나 추가로 파일 수 증가.
- 소규모 유스케이스에서는 오버엔지니어링으로 보일 수 있음.
- 팀 러닝 커브 (Port/Adapter 개념·명명 규칙 정착 필요).

**판단**: 인프라 교체 가능성이 실제로 있는 지점(Outbox의 Kafka, 웹훅의 큐, 캐시 계층 등)에서는 얻는 것이 명확하다. Phase 1 신규 코드부터 시작해 점진 확산하는 방식이 비용을 통제한다.

## 명명 규칙

- **In Port**: `{UseCase명}UseCase` 또는 `{Verb}{Noun}Command/Query` (예: `ConfirmPaymentUseCase`, `GetPaymentQuery`)
- **In Port 구현**: `{UseCase명}Service` (예: `ConfirmPaymentService`)
- **Out Port**: 능력을 표현하는 인터페이스 이름. `Repository`, `Publisher`, `Client`, `Gateway` 등 접미사 (예: `OutboxEventRepository`, `DomainEventPublisher`, `TossPaymentClient`)
- **Out Adapter**: 기술 스택을 prefix로 (예: `JpaOutboxEventRepository`, `KafkaDomainEventPublisher`, `TossHttpPaymentClient`)
- **In Adapter**: 진입 채널을 표현 (예: `PaymentController`, `PaymentKafkaListener`, `OutboxScheduler`)

## 참고

- [payment-outbox-design.md](./payment-outbox-design.md) — Outbox 인프라가 헥사고날 첫 적용 지점이다.
- [implementation-roadmap.md](./implementation-roadmap.md) — Phase별 구현 순서 (헥사고날 적용 시점 포함).
