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
- [주요 특징](#-주요-특징)
- [모니터링 & 운영](#-모니터링--운영)
- [테스트](#-테스트)
- [AI 코딩 에이전트 가이드](#-ai-코딩-에이전트-가이드)

## 📖 프로젝트 개요
**Raillo**는 코레일(KORAIL) 예매 시스템을 클론코딩한 기차 예약 플랫폼으로,  
실제 서비스의 핵심 기능들을 최대한 유사하게 구현하여 현업에서 사용되는 기술 스택과 설계 패턴을 학습하고 적용한 프로젝트입니다.

### 📅 진행 기간
- 2025. 10. 11. ~ now

### 🎯 핵심 목표
- **실제 서비스와 유사한 핵심 기능 구현** : 실제 코레일에서 제공하는 회원 인증 및 주요 예매 흐름을 최대한 비슷하게 구현
- **활발한 협업과 역할 분담 경험** : 팀 내부에서 역할을 분담, 협업툴을 이용한 버전관리, 이슈 트래킹, 코드 리뷰 등 협업 방식을 적용
- **실무에서 사용되는 기술 스택 학습 및 경험** : 실무에서 사용되는 다양한 기술 스택을 학습, 경험하고 관련 패턴을 적용

## 🔧 기술 스택
### Backend
[![backend](https://skillicons.dev/icons?i=java,spring,redis,mysql,gradle)](https://skillicons.dev)
- **Language** : Java 25
- **Framework** : Spring Boot 4.1, Spring Security, Spring Batch
- **ORM** : Spring Data JPA, QueryDSL
- **DB** : MySQL 8.4
- **Cache / 좌석 점유** : Valkey 9 (Redis 호환)
- **Authentication** : JWT
- **Payment** : Toss Payments
- **Build Tool** : Gradle (멀티 모듈)

### Infrastructure & DevOps
[![infra,devops](https://skillicons.dev/icons?i=git,github,docker,kubernetes,prometheus,grafana,githubactions)](https://skillicons.dev)
- **Cloud Platform** : Oracle Cloud (OKE)
- **Container** : Docker, Kubernetes
- **CI/CD** : GitHub Actions, GHCR
- **Monitoring** : Prometheus, Grafana
- **VCS** : Git, GitHub

### Testing
- **Framework** : JUnit 5, Spring Boot Test
- **Test Environment** : Testcontainers (MySQL 8.4.10, Valkey 9) — 운영과 동일 버전, Docker 필요
- **Test Utils** : AssertJ, ArchUnit
- **Performance Testing** : K6
- **Email Testing** : GreenMail

## 👤 유저 플로우
<img width="2048" alt="Raillo-User-Flow" src="https://github.com/user-attachments/assets/24a2ccee-0ba5-4f78-a54f-b57e31b38c1c" />

## 🏗️ 아키텍처
<img width="1920" alt="Raillo-Server-Architecture" src="https://github.com/user-attachments/assets/9d587d24-37e9-46d5-8f97-f1c7ea152bcc" />

### 모듈 구조
```
raillo-domain ← raillo-api
              ← raillo-batch
```

- `raillo-domain`: Entity, VO, 도메인 규칙, Redis 키 계약의 단일 원본
- `raillo-api`: REST API. 결제 도메인은 헥사고날 구조(port, adapter)
- `raillo-batch`: 시간표 파싱, 운행 생성, 열차 캐시 적재, 회원 정리를 하는 Spring Batch 앱

### Layer 아키텍처
```
Controller → Facade → Service → Repository
```
- **Facade** : 여러 Service를 조합하는 진입점. Facade → Facade 호출은 금지
- **Service** : 비즈니스 로직과 트랜잭션 경계. Service → Service 호출은 금지
- **Validator / Calculator / Generator** : 검증, 계산, 식별자 생성 등 책임이 분리된 보조 컴포넌트

## 🚀 주요 특징
### 🔑 Auth 도메인
- **JWT 기반 인증 시스템** : Access Token과 Refresh Token을 활용한 Stateless 인증 및 인가
- **이메일 인증** : Redis를 활용한 인증 코드 발송 및 검증
- **보안 강화** : 로그아웃된 토큰 Redis 관리, 쿠키 기반 Refresh Token 관리

### 👤 Member 도메인
- **고유 회원번호 시스템** : Redis 기반 일일 증분 카운터를 활용한 회원번호 자동 생성 (`yyyyMMddCCCC` 형식)
- **Soft-Delete** : 실제 회원 삭제가 아닌 비활성화 처리
- **만료 회원 일괄 삭제** : 탈퇴 후 3년이 지난 회원을 Batch로 영구 삭제

### 🚅 Train 도메인
- **실제 데이터 활용** : 코레일의 실제 운영 시간표와 운임표 Excel을 파싱해 데이터 구축
- **요일 템플릿 기반 운행 생성** : 시간표 템플릿으로 날짜별 운행을 매일 Batch로 생성
- **구간 단위 잔여석 계산** : 정차 순서 구간으로 겹침을 판단해, 같은 좌석을 겹치지 않는 구간에 나눠 판매
- **열차 캐시** : 예약 생성에 필요한 열차, 운행 정보를 Batch가 Redis에 적재해 예약 시 DB를 읽지 않음
- **운행 캘린더 캐싱** : 하루 동안 같은 응답을 캐시하고 자정에 갱신

### 🎫 Booking 도메인
- **Redis Lua 기반 좌석 점유** : 좌석 구간 충돌 검사와 점유를 Lua 스크립트로 원자적으로 처리해 동시 예약 방지
- **객차별 Hash 구간 점유** : 객차마다 Hash 하나에 `{좌석}:{구간}` field로 점유를 기록
- **TTL 기반 자동 만료** : 결제하지 않은 예약의 좌석 점유는 field 단위 만료(`HEXPIRE`)로 자동 해제
- **예매 확정** : 결제가 끝나면 예매와 승차권을 만들고, 좌석 점유를 예약에서 예매로 전환

### 📦 Order 도메인
- **주문 단위 결제** : 여러 예약을 하나의 주문으로 묶어 한 번에 결제
- **예약 스냅샷** : 주문 시점의 예약을 저장해, Redis 예약이 만료된 뒤에도 결제를 확정

### 💵 Payment 도메인
- **Toss Payments 연동** : Toss 결제창과 승인 API로 결제 처리
- **멱등한 승인** : `paymentKey`에서 계산한 시도 ID와 유니크 제약으로 같은 승인 요청의 중복 처리 방지
- **결과 불명 대응** : 타임아웃, Toss 5xx는 실패로 단정하지 않고, 재요청 시 Toss 조회로 상태를 맞춰 이중 청구 방지
- **트랜잭션 분리** : 승인 시작, Toss 호출, 승인 확정을 나눠 장애 시점과 관계없이 복구 가능한 기록을 남김
- **Outbox** : DB 커밋 뒤 Redis 좌석 점유 전환을 Worker가 비동기로 재시도하며 처리

## 📊 모니터링 & 운영
### 인프라 & 배포
- Oracle Cloud OKE(Kubernetes)에 API, Batch CronJob, MySQL, Valkey, 모니터링을 운영
- `develop`에 머지되면 GitHub Actions가 테스트, ARM64 이미지 빌드, 커밋 SHA 태그 배포까지 수행
- `readinessProbe` 기반 Rolling Update로 무중단 배포
- 운영 DB는 bastion을 통한 SSH 터널로만 접속

### 관측 (Observability)
- Spring Boot Actuator + Micrometer → Prometheus → Grafana 기반 메트릭 파이프라인
- Node, JVM, HTTP 요청, MySQL, Valkey 메트릭을 수집하고 시각화
- 예약, 좌석 충돌, 결제, Outbox 등 비즈니스 메트릭을 Micrometer로 수집. 예약과 결제는 AOP로 계측해 비즈니스 로직을 건드리지 않음

## 🧪 테스트
### 자동화 테스트 전략
- **도메인 단위 테스트** : Entity, VO의 핵심 규칙을 빠르게 검증
- **서비스 통합 테스트** : `@ServiceTest` 기반으로 Testcontainers MySQL/Valkey를 사용해 운영과 동일한 엔진에서 검증
- **동시성 테스트** : 좌석 선점, 결제 승인처럼 충돌 가능성이 높은 흐름을 별도 시나리오로 검증
- **아키텍처 테스트** : ArchUnit으로 결제 도메인의 헥사고날 의존 방향을 강제
- **BDD 스타일** : `given / when / then` 주석과 한국어 `@DisplayName`으로 테스트 의도를 표현

### 로컬 부하 테스트 환경 (`compose-test.yaml`)
운영 환경과 유사한 스택을 Docker Compose로 띄워 반복 가능한 부하 테스트 환경을 구축
- **Spring Boot** (CPU/메모리 제한으로 운영 Pod 스펙 모사)
- **MySQL**, **Valkey** + **redis-exporter**
- **WireMock** : Toss Payments 외부 API 모킹 → 결제 흐름까지 전체 부하 테스트
- **Prometheus** + **Grafana** : 메트릭 실시간 수집, 시각화 (`qa/grafana/dashboards`)

## 🤖 AI 코딩 에이전트 가이드
Claude Code, Codex 등 AI 코딩 에이전트가 같은 컨벤션으로 작업하도록 문서와 스킬을 저장소에 함께 둔다. 시작점은 [`AGENTS.md`](./AGENTS.md)이며 `CLAUDE.md`는 그 심볼릭 링크다.

### 구성
| 위치 | 내용 |
|---|---|
| [`AGENTS.md`](./AGENTS.md) | 문서 지도, 모듈 구조, 빌드 명령, 작업 흐름 |
| [`docs/`](./docs) | 도메인별 문서. 도메인 설명, 용어, 모델, 설계 결정 |
| [`.agents/rules/`](./.agents/rules) | 코드 컨벤션, 테스트 규칙, 문서 규칙 |
| [`.agents/skills/`](./.agents/skills) | 팀 작업 흐름을 코드화한 스킬. `.claude/skills`는 이를 가리키는 심볼릭 링크 |

### 작업 흐름
```
/issue → /branch → /implement [ 구현 → /test → /commit ] × n → /review → /docs → /tidy-commits → /pr
```

| Skill | 용도 |
|---|---|
| `/issue` | 팀 형식으로 이슈를 쓰고 승인 후 생성 |
| `/branch` | 이슈 번호 기반 브랜치를 원격 최신 기준으로 분기 |
| `/implement` | 이슈를 커밋 단위 단계로 나눠 단계마다 검토 후 커밋 |
| `/test` | 규칙에 맞는 테스트 작성과 실행 |
| `/commit` | `type: 설명 (#N)` 형식으로 커밋 |
| `/review` | 대화 맥락이 없는 서브에이전트가 컨벤션, 정확성, 테스트 관점으로 리뷰 |
| `/docs` | 문서를 코드와 대조해 세션 맥락, 중복을 걷어내고 간결하게 유지 |
| `/tidy-commits` | PR 전에 수정 커밋을 합치고 메시지 정리 |
| `/pr` | 이슈 기준으로 PR을 쓰고 승인 후 생성 |
| `/db-reset` | 테스트 DB에서 열차, 회원 데이터만 남기고 테이블 초기화 |
