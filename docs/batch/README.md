# 배치

`raillo-batch`는 웹 서버 없이 Job 하나를 실행하고 끝나는 Spring Batch 앱이다. 열차 데이터와 회원 정리를 맡는다. 다루는 데이터의 의미는 [train](../train/README.md)에 있다.

## 하는 일

- 열차 시간표와 운임표 Excel을 읽어 역, 열차, 운행 템플릿, 구간 운임을 DB에 넣는다.
- 운행 템플릿으로 날짜별 운행과 정차역을 만든다. 매일 하루치씩 앞으로 늘린다.
- 예약 생성이 읽는 [열차 캐시](../train/README.md#열차-캐시traincache)를 Redis에 적재한다. 운행을 만들면 곧바로 그 운행을 적재한다.
- 탈퇴한 지 3년이 지난 회원을 영구 삭제한다.

## 용어

| 한국어 | 영어 | 설명 |
|---|---|---|
| 파싱 | parse | 시간표, 운임표 Excel을 읽어 DB에 넣는 것 |
| 스케줄 생성 | schedule | 운행 템플릿으로 날짜별 운행과 정차역을 만드는 것 |
| 정적 적재 | static cache load | 좌석, 객차, 역, 운임을 열차 캐시에 넣는 것. 만료가 없다 |
| 운행 적재 | schedule cache load | 운행, 정차역을 열차 캐시에 넣는 것. 운행일 + 2일에 만료한다 |

## Job

| Job | 단계 | 언제 |
|---|---|---|
| `trainInitialize` | 파싱 → 정적 적재 → 한 달치 스케줄 생성 → 운행 적재 | 빈 DB를 처음 구성할 때 |
| `trainParse` | 파싱 → 정적 적재 | 시간표나 운임표가 바뀌었을 때 |
| `trainMonthlySchedule` | 오늘부터 1개월 + 1일치 스케줄 생성 → 운행 적재 | 수동 |
| `trainDailySchedule` | 하루치 스케줄 생성 → 운행 적재 | 매일 03:00 (CronJob) |
| `trainStaticCacheLoad` | 정적 적재 | 캐시만 복구할 때 |
| `trainScheduleCacheLoad` | 운행 적재 | 캐시만 복구할 때 |
| `deleteExpiredMembers` | 탈퇴 3년 경과 회원 삭제 | 매일 04:00 (CronJob) |

### 파싱 (`trainParse`)

- 입력: `train.schedule.excel.location`, `train.station-fare.excel.location`. 기본값은 `resources/files/`의 Excel이다
- 규칙
  - 역과 열차는 없으면 만들고 있으면 둔다. 운행 템플릿과 구간 운임은 **모두 지우고 다시 넣는다**
  - 운임표의 한 행은 양방향 운임 두 개가 된다
  - 차종별 객차 구성과 좌석 배열은 `train-template.yml`이 정한다
  - 이미 만든 운행은 바꾸지 않는다. 바뀐 시간표는 이후 새로 만드는 날짜부터 적용된다

### 스케줄 생성 (`trainDailySchedule`, `trainMonthlySchedule`)

- 파라미터: `trainDailySchedule`만 `--operationDate=yyyy-MM-dd`를 받는다. 없으면 **DB의 마지막 운행일 다음 날**을 만든다
- 규칙
  - 날짜마다 그 요일에 운행하는 템플릿만 운행으로 만든다
  - 이미 운행이 있는 날짜는 건너뛴다. 건너뛴 날짜도 운행 적재 대상에는 넣는다
  - `trainDailySchedule`은 하루씩만 늘린다. 며칠 빠지면 실행 한 번에 하루만 따라잡는다
  - 대량 저장은 JPA가 아니라 JDBC batch insert(`*JdbcRepository`)로 한다

### 열차 캐시 적재 (`trainStaticCacheLoad`, `trainScheduleCacheLoad`)

- 파라미터: 운행 적재는 앞 Step이 넘긴 날짜 범위, `--operationDate`, `--fromDate`와 `--toDate` 순으로 대상을 찾는다
- 규칙
  - 정적 적재는 쓴 다음 DB에 없는 키를 지운다. 순서가 반대면 키가 잠시 비어 예약이 실패한다
  - 운행 적재는 같은 정차역을 역 ID field와 정차역 ID field에 모두 넣는다. 조회 방향이 둘이다
  - DB 트랜잭션을 열지 않는다. 조회는 커넥션을 빌려 끝내고 Redis 쓰기는 그 밖에서 한다

### 회원 정리 (`deleteExpiredMembers`)

- 규칙
  - 삭제 상태(`is_deleted = true`)이고 `updated_at`이 3년 전보다 오래된 회원을 100명씩 영구 삭제한다. 삭제 시각을 따로 저장하지 않으므로 삭제 후 행이 수정되면 기간이 다시 시작된다
  - 삭제가 실패하면 250ms부터 2배씩, 최대 1초 간격으로 5번까지 재시도한다

## 실행

```bash
./gradlew :raillo-batch:bootRun -Pjob=trainDailySchedule -PoperationDate=2026-01-01
java -jar raillo-batch.jar --job=trainDailySchedule --operationDate=2026-01-01
```

- `--job`은 하나만 지정한다. 점(`.`)이 없는 나머지 옵션은 Job 파라미터가 된다
- `run.id`를 실행마다 자동으로 붙인다. 같은 파라미터로 다시 돌릴 수 있다
- 종료 코드는 성공 0, 실패 1이다
- **Spring Batch 메타 테이블(`BATCH_*`)을 자동으로 만들지 않는다**(`initialize-schema: never`). DB에 미리 있어야 한다
- 운영 CronJob은 `k8s/oke/batch/batch-cronjob.yaml`이다. 한국 시간 기준이고 동시 실행을 막는다

## 설계 결정

### 스케줄 생성 Job은 끝에 운행 적재 Step을 붙인다
- 맥락: 운행을 만들고 캐시를 적재하지 않으면 그 운행은 예약할 수 없다. 별도 Job으로 두면 빠뜨리기 쉽다.
- 결정: 스케줄 생성 Step이 대상 날짜 범위를 실행 컨텍스트에 넣고, 같은 Job의 운행 적재 Step이 그 범위를 적재한다. 날짜는 DB에 직렬화되므로 ISO 문자열로 넘긴다.
- 결과: 운행이 생기면 캐시도 같이 생긴다. `trainDailySchedule`은 적재 시작일을 오늘로 당겨, 이전 실행이 적재에서 실패했어도 다음 실행이 메운다.

### Job은 하나씩 실행하고 종료 코드로 결과를 알린다
- 맥락: Job은 Kubernetes CronJob으로 돈다. CronJob은 프로세스 종료 코드로 성공과 실패를 판단한다.
- 결정: Spring Batch 자동 실행을 끄고, `BatchJobLauncher`가 `--job`으로 고른 Job 하나만 실행한 뒤 결과를 종료 코드로 돌려준다.
- 결과: 실패한 Job은 CronJob에 실패로 남는다. 여러 Job을 이어 돌리려면 위처럼 한 Job 안에 Step으로 묶는다.
