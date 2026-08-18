# 🚅 Raillo

## 🙌🏻 멤버
<table>
  <tbody>
    <tr>
      <td align="center">
        <a href="https://github.com/Ogu1208"><img src="https://avatars.githubusercontent.com/u/76902448?v=4" width="100px;" alt="김민아"/><br /></a>
      </td>
      <td align="center">
        <a href="https://github.com/Jimin730"><img src="https://avatars.githubusercontent.com/u/108002997?v=4" width="100px;" alt="신지민"/><br /></a>
      </td>
      <td align="center">
        <a href="https://github.com/chanwonlee"><img src="https://avatars.githubusercontent.com/u/116537544?v=4" width="100px;" alt="이찬원"/><br /></a>
      </td>
    </tr>
    <tr>
      <td align="center"><a href="https://github.com/Ogu1208">김민아</a></td>
      <td align="center"><a href="https://github.com/Jimin730">신지민</a></td>
      <td align="center"><a href="https://github.com/chanwonlee">이찬원</a></td>
    </tr>
  </tbody>
</table>

## 📋 목차
- [프로젝트 개요](#-프로젝트-개요)
- [기술 스택](#-기술-스택)
- [유저 플로우](#-유저-플로우)
- [아키텍처](#-아키텍처)
- [주요 기능](#-주요-기능)
- [모니터링 & 운영](#-모니터링--운영)
- [테스트](#-테스트)
- [AI 코딩 에이전트 가이드](#-ai-코딩-에이전트-가이드)

## 📖 프로젝트 개요
**Raillo**는 코레일(KORAIL) 예매 시스템을 클론코딩한 기차 예약 플랫폼으로,  
실제 서비스의 핵심 기능들을 최대한 유사하게 구현하여 현업에서 사용되는 기술 스택과 설계 패턴을 학습하고 적용한 프로젝트입니다.

### 📅 진행 기간
- v1 : 2025. 05. 28. ~ 2025. 08. 08.
- v2 : 2025. 10. 11. ~ now

### 🎯 핵심 목표
- **실제 서비스와 유사한 핵심 기능 구현** : 실제 코레일에서 제공하는 회원 인증 및 주요 예매 흐름을 최대한 비슷하게 구현
- **활발한 협업과 역할 분담 경험** : 팀 내부에서 역할을 분담, 협업툴을 이용한 버전관리, 이슈 트래킹, 코드 리뷰 등 협업 방식을 적용
- **실무에서 사용되는 기술 스택 학습 및 경험** : 실무에서 사용되는 다양한 기술 스택을 학습, 경험하고 관련 패턴을 적용

## 🔧 기술 스택
### Backend
[![backend](https://skillicons.dev/icons?i=java,spring,redis,mysql,gradle)](https://skillicons.dev)
- **Language** : Java
- **Framework** : Spring Boot, Spring Security
- **ORM** : Spring Data JPA, QueryDSL
- **DB** : MySQL
- **Cache** : Redis
- **Authentication** : JWT
- **Build Tool** : Gradle

### Infrastructure & DevOps
[![infra,devops](https://skillicons.dev/icons?i=git,github,docker,kubernetes,aws,prometheus,grafana,githubactions)](https://skillicons.dev)
- **Cloud Platform** : AWS (EKS, RDS, Route53, Load Balancer)
- **Container** : Docker, Kubernetes
- **CI/CD** : GitHub Actions, ArgoCD (GitOps)
- **Monitoring** : Prometheus, Grafana
- **VCS** : Git, GitHub

### Testing
- **Framework** : JUnit, Spring Boot Test
- **Test Environment** : Testcontainers (MySQL 8.4.10, Redis 7.4) — 운영과 동일 버전, Docker 필요
- **Test Utils** : AssertJ
- **Performance Testing** : K6
- **Email Testing** : GreenMail

## 👤 유저 플로우
<img width="2048" alt="Raillo-User-Flow" src="https://github.com/user-attachments/assets/24a2ccee-0ba5-4f78-a54f-b57e31b38c1c" />

## 🏗️ 아키텍처
<img width="1920" alt="Raillo-Server-Architecture" src="https://github.com/user-attachments/assets/9d587d24-37e9-46d5-8f97-f1c7ea152bcc" />


### 도메인 주도 설계 (DDD)
```
src/main/java/com/sudo/raillo/
├── auth/       # 인증
├── booking/    # 예약·예매
├── member/     # 회원
├── order/      # 주문
├── payment/    # 결제
├── train/      # 열차
└── global/     # 공통 인프라
```

### Layer 아키텍처
```
Controller → Facade → Service → Repository
```
- **Facade** : 도메인 단일 진입점으로 여러 Service를 조합하며, Facade → Facade 호출은 금지
- **Service** : 비즈니스 로직과 트랜잭션 경계 담당
- **Validator / Calculator / Generator** : 검증, 계산, 식별자 생성 등 책임이 분리된 보조 컴포넌트

## 🚀 주요 특징
### 🔑 Auth 도메인
- **JWT 기반 인증 시스템** : Access Token과 Refresh Token을 활용한 Stateless 인증 및 인가
- **이메일 인증** : Redis를 활용한 인증 코드 발송 및 검증 시스템
- **보안 강화** : 로그아웃된 토큰 Redis 관리, 쿠키 기반 Refresh Token 관리

### 👤 Member 도메인
- **고유 회원번호 시스템** : Redis 기반 일일 증분 카운터를 활용한 회원번호 자동 생성 (`yyyyMMddCCCC` 형식)
- **Soft-Delete** : 실제 회원 삭제가 아닌 비활성화 처리
- **만료 회원 일괄 삭제** : 만료된 회원 데이터 정리를 위한 배치 처리 활용

### 🎫 Booking 도메인
- **장바구니 시스템**: 예약 후 결제 전 임시 저장 및 관리 기능
- **좌석 예약 관리**: 승객 유형별 좌석 배정 및 예약 상태 관리
- **요금 계산**: 거리별, 승객 유형별, 차량 등급별 요금 자동 계산
- **Redis Lua 스크립트 기반 좌석 선점**: 좌석 구간 충돌 검사와 임시 좌석 점유를 Lua 스크립트로 원자적 처리하여 동시 예약 방지
- **좌석 점유 인덱스 최적화**: 좌석별, 객차별 다중 인덱스 구조로 좌석 조회 성능과 정확성 확보
- **TTL 기반 자동 만료**: 일정 시간 내 결제하지 않은 임시 예약과 좌석 점유는 자동으로 해제되어 좌석을 예매 가능 상태로 전환

### 📦 Order 도메인
- **주문 통합 관리**: 예약과 결제를 연결하는 주문 단위 관리
- **주문 상태 라이프사이클 관리**: 결제 대기 → 결제 완료 → 취소까지의 주문 상태 흐름을 도메인 단에서 일관되게 관리
- **결제 흐름 일관성 보장:** 주문 단계별 상태 검증으로 중복 결제와 잘못된 상태 전이 차단

### 💵 Payment 도메인
- **Toss Payments 기반 결제 연동**: Toss Payments의 결제 UI를 통해 결제 플로우를 안정적으로 처리
- **결제 키 생성**: 고유한 결제 식별자 자동 생성
- **금액 다중 검증 및 중복결제 방지**: 결제 단계별 금액을 비교 검증하고, 이미 처리된 주문에 대한 재결제 시도 차단
- **자동 티켓 발급**: 결제 완료 시 티켓 생성
- **취소 및 환불**: 결제 취소 및 환불 처리 시스템

### 🚅 Train 도메인
- **실제 데이터 활용**: 코레일의 실제 운영 스케줄 Excel 파일을 파싱하여 데이터 구축
- **열차 검색 최적화**: 배치 쿼리를 활용한 대용량 스케줄 검색 성능 최적화
- **운행 캘린더 캐싱**: Redis 캐시로 반복 조회되는 운행 캘린더 응답 최적화
- **좌석 현황 관리**: 실시간 좌석 예약 현황 및 여유석 정보 제공
- **역 간 요금 시스템**: 구간별 세분화된 요금 체계 구현

## 📊 모니터링 & 운영
### 인프라 & 배포
- GitHub Actions와 ArgoCD를 활용해 GitOps 기반 CI/CD 환경 구성
- Public / Private 서브넷을 분리하고, ALB를 통해서만 내부 서비스에 접근하도록 네트워크 구성
- `topologySpreadConstraints` 기반 Pod 분산 배치와 다중 Replica 운영을 통해 고가용성을 확보하고, `readinessProbe` 기반 Rolling Update로 무중단 배포 구성
- Spring Boot 애플리케이션과 Redis는 Kubernetes(EKS) 기반으로 운영하고, DB는 AWS 관리형 서비스(RDS)로 구성

### 관측 (Observability)
- Spring Boot Actuator + Micrometer → Prometheus → Grafana 기반 메트릭 파이프라인 구축
- Node·JVM·HTTP 요청·애플리케이션 메트릭을 실시간 수집 및 시각화
- 예매, 좌석 충돌, 결제 흐름 등 비즈니스 도메인 기반 커스텀 메트릭을 설계하고 Grafana 대시보드로 시각화
- AOP 기반 계측을 적용해 비즈니스 로직 수정 없이 메트릭 수집

## 🧪 테스트
### 자동화 테스트 전략
- **도메인 단위 테스트** : Entity, VO, Calculator, Validator의 핵심 규칙을 빠르게 검증
- **서비스 통합 테스트** : `@ServiceTest` 기반으로 Testcontainers MySQL/Redis를 사용해 운영과 동일한 엔진에서 DB/Redis 연동 흐름 검증
- **동시성 테스트** : 좌석 선점, 중복 예매, 결제 승인처럼 충돌 가능성이 높은 흐름을 별도 시나리오로 검증
- **테스트 데이터 구성** : Fixture와 Test Helper를 분리해 단위 테스트와 통합 테스트의 데이터 준비 책임을 구분
- **BDD 스타일** : `given / when / then` 주석과 한국어 `@DisplayName`으로 테스트 의도를 명확하게 표현

### Test Helper 클래스
서비스 통합 테스트에서는 반복되는 DB 저장 로직을 Test Helper로 분리해 테스트 본문이 검증 의도에 집중하도록 구성한다.

- **Fixture** : DB 저장 없이 순수 도메인 객체를 생성할 때 사용
- **Test Helper** : DB 저장이 필요한 통합 테스트 데이터를 구성할 때 사용

| Helper | 역할 |
|--------|------|
| `TrainTestHelper` | 테스트용 열차, 객차, 좌석 생성 및 예약 가능한 좌석 조회 |
| `TrainScheduleTestHelper` | 기본/커스텀 운행 스케줄, 정차역, 역 간 요금 생성 |
| `BookingTestHelper` | 확정 예매, 좌석 예매, 티켓 발급까지 포함한 예매 데이터 구성 |
| `OrderTestHelper` | 주문, OrderBooking, OrderSeatBooking 데이터 구성 |

### 로컬 부하 테스트 환경 (`compose-test.yaml`)
운영 환경과 유사한 스택을 Docker Compose로 띄워, 외부 비용·제약 없이 반복 가능한 부하 테스트 환경 구축
- **Spring Boot** (CPU/메모리 제한으로 운영 Pod 스펙 모사)
- **Redis** + **redis-exporter** (좌석 선점, 캐시, 메트릭 수집)
- **WireMock** : Toss Payments 외부 API 모킹 → 결제 흐름까지 전체 부하 테스트
- **Prometheus** + **Grafana** : Spring Boot Actuator / Redis 메트릭 실시간 수집·시각화 (`qa/grafana/dashboards`)

## 🤖 AI 코딩 에이전트 가이드
팀은 Claude Code·Codex 등 AI 코딩 에이전트를 일관된 컨벤션으로 사용하기 위해 다음 문서·도구를 함께 제공한다. 어떤 도구를 쓰든 동일한 결과가 나오도록 단일 컨텍스트(`AGENTS.md`)를 공유한다.

### 상세 문서 (`docs/`)
| 문서 | 내용 |
|---|---|
| [`seat-hold-architecture.md`](./docs/seat-hold-architecture.md) | Redis Lua 기반 좌석 동시 선점 아키텍처, Hold Index, Train Search 통합 |
| [`seat-conflict-validation.md`](./docs/seat-conflict-validation.md) | 4-Layer 좌석 충돌 방어 (Lua → SQL → Re-validation → TTL) |
| [`domain-model.md`](./docs/domain-model.md) | 엔티티 관계도, Booking Flow, 한국어 도메인 용어 |
| [`testing-guide.md`](./docs/testing-guide.md) | Helper/Fixture 사용 예제와 `@ServiceTest` 상세 |
| [`deployment.md`](./docs/deployment.md) | K8s, ArgoCD, Docker, CI/CD 배포 상세 |

### 커스텀 Skills (`.agents/skills/`)
팀 컨벤션을 코드화한 커스텀 skill. 실제 파일은 `.agents/skills/`에 두고 `.claude/skills`는 이를 가리키는 심볼릭 링크다 — Claude Code는 `.claude/skills`, Codex 등은 `.agents/skills`를 참조하므로 어떤 도구에서도 동일한 skill을 사용한다.

| Skill | 용도 |
|---|---|
| `/issue` | 팀 Issue 컨벤션에 맞춘 제목·본문·라벨 텍스트 생성 |
| `/branch` | 이슈 번호 기반 브랜치명 자동 생성 |
| `/commit` | 브랜치명에서 이슈 추출 후 커밋 메시지 생성 (`Co-Authored-By` 금지) |
| `/pr` | 변경사항 분석 후 팀 PR 템플릿으로 PR 생성 (사용자 검수 후 실행) |
| `/test` | 도메인/서비스/Validator 테스트 자동 작성 (BDD, `@DisplayName` 한국어) |
| `/validator` | `application/validator/{Domain}Validator.java` 클래스/메서드 생성 |
| `/api-doc` | Controller 기반 Swagger `{Domain}ControllerDoc` 인터페이스 생성 |

### 표준 개발 워크플로우 (superpowers 기반)
팀은 [`superpowers`](https://github.com/obra/superpowers) 시리즈를 기본 워크플로우로 사용한다.

1. `superpowers:brainstorming` — 요구사항/디자인 탐색
2. `superpowers:writing-plans` — 구현 계획 수립
3. `superpowers:test-driven-development` — TDD 구현
4. `/test <대상>` — 프로젝트 컨벤션 기반 테스트 작성
5. `superpowers:verification-before-completion` — `./gradlew test` 등 증거 기반 검증
6. 문서 반영 — 바뀐 내용을 관련 문서(`AGENTS.md`, `README.md`, `docs/*`)에 반영. 기존 문서를 수정하거나 필요하면 적절한 문서를 신규 생성하고, 같은 PR에 포함한다
7. `/pr` — PR 생성 (사용자 검수 후 실행)

- **버그 발생 시** → `superpowers:systematic-debugging` 우선
- **코드리뷰 받았을 때** → `superpowers:receiving-code-review`로 맹목적 적용 방지
- **병렬 작업 가능 시** → `superpowers:dispatching-parallel-agents`
