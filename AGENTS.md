# AGENTS.md

레일로(Raillo)는 KTX 승차권 예매 서비스의 백엔드다. 이 파일은 AI가 저장소에서 작업할 때 처음 읽는 지도다. `CLAUDE.md`는 이 파일의 심볼릭 링크다.

## 문서 지도

작업할 도메인의 문서를 먼저 읽는다. 문서는 도메인 설명, 용어, 모델(속성, 행위, 규칙), 설계 결정으로 되어 있다.

| 문서 | 다루는 것 |
|---|---|
| [docs/train](docs/train/README.md) | 열차, 객차, 좌석, 운행, 정차역, 운임, 검색, 열차 캐시 |
| [docs/reservation](docs/reservation/README.md) | 예약(Redis), 좌석 점유, Lua 스크립트, 예매, 승차권 |
| [docs/order](docs/order/README.md) | 주문, 예약 스냅샷 |
| [docs/payment](docs/payment/README.md) | 결제 준비와 승인, 결제 시도, Toss 연동 |
| [docs/batch](docs/batch/README.md) | `raillo-batch` Job |
| [docs/worker](docs/worker/README.md) | 결제 Outbox Worker, 스케줄러 |
| [docs/infra](docs/infra/README.md) | 빌드, 배포, OKE 클러스터, 접속, 모니터링 |

코드 규칙은 `.agents/rules/`에 있다. 코드를 쓰기 전에 읽는다.

| 규칙 | 다루는 것 |
|---|---|
| [code-convention.md](.agents/rules/code-convention.md) | 레이어, 예외, 에러 코드, 네이밍, 트랜잭션, Redis |
| [test.md](.agents/rules/test.md) | 테스트 환경, 금지 사항, 작성 컨벤션, 테스트 데이터 |
| [docs.md](.agents/rules/docs.md) | 문서 위치, 언제 고치는가, 어떻게 쓰는가 |

## 기술 스택

- Java 25, Spring Boot 4.1, Gradle 멀티 모듈
- Spring Data JPA, QueryDSL 5, Spring Security, Spring Batch
- MySQL 8.4, Valkey 9 (Redis 호환, Hash field 만료 사용)
- 결제: 토스페이먼츠
- 테스트: JUnit 5, AssertJ, Testcontainers, ArchUnit
- 운영: Oracle Cloud OKE (Kubernetes), GHCR, GitHub Actions

## 모듈

```
raillo-domain   Entity, VO, Enum, 도메인 규칙, Redis 키 계약. 실행할 수 없는 라이브러리
raillo-api      REST API 앱
raillo-batch    Spring Batch 앱. Job 하나를 실행하고 끝난다
```

의존 방향은 `raillo-domain ← raillo-api / raillo-batch`다. `raillo-api`와 `raillo-batch`는 서로 의존하지 않는다.

도메인은 `auth`, `member`, `train`, `booking`(예약, 예매), `order`, `payment`다. 패키지는 도메인 아래에 레이어를 둔다.

```
raillo-domain/.../{domain}/   domain/  cache/  exception/  util/
raillo-api/.../{domain}/      presentation/  application/{service,facade,validator,dto,...}/  infrastructure/
raillo-api/.../payment/       헥사고날: adapter/{webapi,persistence,integration,scheduling}/  application/{provided,required}/
raillo-batch/.../{domain}/    job/  application/  infrastructure/  config/
```

## 빌드와 테스트

```bash
./gradlew build                                   # 전체 빌드와 테스트
./gradlew :raillo-api:test --tests "{FQCN}"      # 테스트 하나
./gradlew :raillo-api:bootRun                     # API 실행. .env의 DB를 쓰고 Valkey는 compose로 뜬다
./gradlew :raillo-batch:bootRun -Pjob={Job 이름}   # Batch Job 실행
```

테스트는 Docker가 필요하다. Docker를 쓸 수 없으면 컴파일까지만 확인하고 그 사실을 알린다.

## 작업 흐름

스킬은 `.agents/skills/`에 있다.

```
/issue → /branch → /implement [ 구현 → /test → /commit ] × n → /review → /docs → /tidy-commits → /pr
```

| 스킬 | 하는 일 |
|---|---|
| `/issue` | 팀 형식으로 이슈를 쓰고 승인 후 만든다 |
| `/branch` | `{label}/{이슈번호}-{설명}` 브랜치를 원격 최신 기준으로 판다 |
| `/implement` | 이슈를 커밋 단위 단계로 나눠 단계마다 검토를 받고 커밋한다 |
| `/test` | 규칙에 맞는 테스트를 쓰고 실행한다 |
| `/commit` | `type: 설명 (#N)` 형식으로 커밋한다 |
| `/review` | 대화 맥락이 없는 서브에이전트들이 변경을 리뷰한다 |
| `/docs` | 길어진 문서에서 세션 맥락, 중복을 걷어낸다 |
| `/tidy-commits` | PR 전에 수정 커밋을 합치고 메시지를 정리한다. 코드는 바꾸지 않는다 |
| `/pr` | 이슈 기준으로 PR을 쓰고 승인 후 만든다 |
| `/db-reset` | `.env`의 DB에서 열차, 회원 데이터만 남기고 테이블을 지운다 |

## 지켜야 할 것

- 이슈, PR 생성처럼 팀에 보이는 일과 push는 사용자 승인을 받고 한다.
- `develop`, `main`에서 커밋하지 않는다.
- `develop`에 머지되면 승인 없이 운영에 배포된다. 운영 DB는 `ddl-auto: validate`라 스키마 변경은 배포 전에 DB에 반영해야 한다.
- 코드의 계약(API, 상태 전이, 저장 형식, Redis 키)을 바꾸면 같은 PR에서 해당 도메인 문서를 고친다.
