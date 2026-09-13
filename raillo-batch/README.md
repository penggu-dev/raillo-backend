# raillo-batch

웹 서버 없이 실행되는 Spring Batch 애플리케이션이다. 실행할 때 `--job`으로 Job 하나를 지정하고, Job이 끝나면 프로세스가 종료된다.

- 의존 모듈: `raillo-domain` (`raillo-api`에는 의존하지 않는다)
- 실행기: `BatchJobLauncher`
  - `--job`으로 Job을 선택한다
  - `run.id`를 자동으로 붙여 같은 Job을 반복 실행할 수 있다
  - 결과를 종료 코드로 돌려준다 (성공 `0`, 실패 `1`)

## Job 목록

| Job | 하는 일 | 파라미터 | 반복 실행 |
|---|---|---|---|
| `trainParse` | 시간표·운임 Excel → 역·열차·스케줄 템플릿·운임 저장 | - | ✅ 템플릿·운임은 Excel 기준으로 교체 |
| `trainInitialize` | `trainParse` → `trainMonthlySchedule` 순서로 실행 | - | ✅ 템플릿·운임 교체, 이미 있는 날짜는 건너뜀 |
| `trainDailySchedule` | `operationDate` 날짜, 없으면 마지막 운행일 다음 날 스케줄 생성 | `operationDate` (선택, `yyyy-MM-dd`) | ✅ 이미 있는 날짜는 건너뜀 |
| `trainMonthlySchedule` | 실행일부터 한 달 뒤까지 스케줄 생성 | - | ✅ 이미 있는 날짜는 건너뜀 |
| `deleteExpiredMembers` | 삭제 상태가 된 지 3년 지난 회원을 100명 단위로 영구 삭제 | - | ✅ |

> `trainParse`를 다시 실행했을 때 동작
> - 스케줄 템플릿·정차역 템플릿·운임: 기존 데이터를 **모두 지우고 Excel 기준으로 다시 저장**한다. 중복되지 않는다.
> - 역·열차·객차·좌석: 이름·번호로 찾아 **없는 것만 추가**한다. 기존 데이터는 수정하거나 삭제하지 않는다.
> - 이미 생성된 운행 스케줄(`train_schedule`, `schedule_stop`)은 바뀌지 않는다. Excel 변경 사항은 이후 **새로 생성되는 날짜부터** 반영된다.
> - Excel에서 빠진 역·열차는 DB에 남는다. 기존 열차의 편성(열차 종류, 객차 구성)이 바뀌어도 반영되지 않는다.

### 옵션 규칙

- `--job=<이름>`: 실행할 Job (필수). 없거나 이름이 틀리면 사용 가능한 Job 목록을 출력하고 exit 1로 종료한다.
- `--operationDate=2026-10-20`처럼 **점(.)이 없고 값이 있는 옵션**은 Job 파라미터로 전달된다.
- `--spring.batch.jdbc.initialize-schema=always`처럼 **점이 있는 옵션**이나 `--debug`처럼 값이 없는 옵션은 Spring 설정으로 처리되고 Job 파라미터에는 들어가지 않는다.

## 환경변수

| 이름 | 필수 | 설명 |
|---|---|---|
| `DB_URL` | ✅ | 예: `jdbc:mysql://localhost:3306/raillo` |
| `DB_USERNAME` | ✅ | DB 계정 |
| `DB_PW` | ✅ | DB 비밀번호 |
| `TRAIN_SCHEDULE_LOCATION` | | 시간표 Excel 위치. 기본값 `classpath:files/train_schedule.xlsx` |
| `STATION_FARE_LOCATION` | | 운임 Excel 위치. 기본값 `classpath:files/station_fare.xls` |

외부 Excel 파일을 쓰려면 `file:/경로/train_schedule.xlsx` 형식으로 지정한다.

운영 기본 설정은 스키마를 자동으로 만들지 않는다.

- `spring.batch.jdbc.initialize-schema=never`: Spring Batch 메타테이블(`BATCH_*`)을 만들지 않는다.
- `spring.jpa.hibernate.ddl-auto=none`: 비즈니스 테이블을 만들지 않는다.

---

## 로컬에서 실행하기

### 1. 준비

- JDK 25. `bootRun`은 Gradle toolchain이 처리하지만, `java -jar`로 직접 실행하려면 JDK 25가 필요하다.
- MySQL
- 프로젝트 루트의 `.env` 파일. `raillo-api`와 같은 파일을 쓰며, `.gitignore`에 포함되어 커밋되지 않는다.

```properties
DB_URL=jdbc:mysql://localhost:3306/raillo
DB_USERNAME=root
DB_PW=비밀번호
```

`.env`는 **실행 위치 기준**으로 읽는다. `bootRun`은 작업 폴더가 프로젝트 루트로 고정되어 있어 루트 `.env`를 읽는다. 쉘 환경변수로 넣으면 `.env`보다 우선한다.

### 2. 처음 한 번: 테이블 생성 + 초기 데이터 적재

빈 DB라면 메타테이블과 템플릿 테이블을 함께 만들면서 초기화 Job을 실행한다.

```bash
SPRING_BATCH_JDBC_INITIALIZE_SCHEMA=always SPRING_JPA_HIBERNATE_DDL_AUTO=update ./gradlew :raillo-batch:bootRun -Pjob=trainInitialize
```

- `SPRING_BATCH_JDBC_INITIALIZE_SCHEMA=always`: `BATCH_*` 메타테이블을 만든다. 이미 있으면 그대로 사용한다.
- `SPRING_JPA_HIBERNATE_DDL_AUTO=update`: `train_schedule_template`, `schedule_stop_template` 등 없는 테이블을 만든다. 기존 테이블에도 스키마 변경이 적용될 수 있으므로 **로컬·개발 DB에서만** 사용한다.

### 3. Job 실행

```bash
./gradlew :raillo-batch:bootRun -Pjob=trainDailySchedule
```

```bash
./gradlew :raillo-batch:bootRun -Pjob=trainDailySchedule -PoperationDate=2026-10-20
```

```bash
./gradlew :raillo-batch:bootRun -Pjob=trainMonthlySchedule
```

```bash
./gradlew :raillo-batch:bootRun -Pjob=deleteExpiredMembers
```

`bootRun`이 받는 Gradle 프로퍼티는 `-Pjob`, `-PoperationDate`이다. 설정은 `build.gradle`의 `bootRun` 블록에 있다.

### JAR로 실행

```bash
./gradlew :raillo-batch:bootJar
```

```bash
java -jar raillo-batch/build/libs/raillo-batch-0.0.1-SNAPSHOT.jar --job=trainDailySchedule --operationDate=2026-10-20
```

### IntelliJ에서 실행

`RailloBatchApplication` 실행 설정을 만든다.

- Program arguments: `--job=trainDailySchedule` (필요하면 `--operationDate=2026-10-20` 추가)
- Environment variables: `DB_URL=...;DB_USERNAME=...;DB_PW=...`
- Working directory: 프로젝트 루트 (`.env`를 쓸 경우)
- JDK: 25

### 결과 확인

성공하면 로그에 아래 줄이 찍히고 exit 0으로 끝난다. 실패하면 `FAILED`와 exit 1이다.

```text
[trainDailySchedule] Job 실행 종료 - status: COMPLETED
```

실행 이력은 메타테이블에서 확인한다.

```sql
SELECT i.JOB_NAME, e.STATUS, e.START_TIME, e.END_TIME, e.EXIT_MESSAGE
FROM BATCH_JOB_EXECUTION e
JOIN BATCH_JOB_INSTANCE i USING (JOB_INSTANCE_ID)
ORDER BY e.JOB_EXECUTION_ID DESC;
```
