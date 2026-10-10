# raillo-batch

웹 서버 없이 실행되는 Spring Batch 애플리케이션이다. 실행할 때 `--job`으로 Job 하나를 지정하고, Job이 끝나면 프로세스가 종료된다.

Job 목록, 각 Job의 동작 규칙, 옵션 규칙은 [docs/batch](../docs/batch/README.md)에 있다. 이 문서는 로컬에서 실행하는 방법만 다룬다.

## 환경변수

| 이름 | 필수 | 설명 |
|---|---|---|
| `DB_URL` | ✅ | 예: `jdbc:mysql://localhost:3306/raillo` |
| `DB_USERNAME` | ✅ | DB 계정 |
| `DB_PW` | ✅ | DB 비밀번호 |
| `REDIS_HOST` | | Redis 호스트. 기본값 `localhost` |
| `REDIS_PORT` | | Redis 포트. 기본값 `6379` |
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
- Redis. 기준정보 적재에 쓴다.
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

`bootRun`이 받는 Gradle 프로퍼티는 `-Pjob`, `-PoperationDate`, `-PfromDate`, `-PtoDate`이다. 설정은 `build.gradle`의 `bootRun` 블록에 있다.

### 캐시만 복구하기

정적 데이터는 DB에서 다시 읽어 Redis에 적재한다.

```bash
./gradlew :raillo-batch:bootRun -Pjob=trainStaticCacheLoad
```

운행 데이터는 하루 또는 기간을 지정해 복구한다. 아래 날짜는 예시이며, 실제 복구할 운행일로 바꿔 실행한다.

```bash
./gradlew :raillo-batch:bootRun -Pjob=trainScheduleCacheLoad -PoperationDate=2026-10-20
```

```bash
./gradlew :raillo-batch:bootRun -Pjob=trainScheduleCacheLoad -PfromDate=2026-10-20 -PtoDate=2026-10-31
```

`operationDate`와 `fromDate`·`toDate` 중 한 방식을 선택한다. 기간은 시작일과 종료일을 모두 포함한다.

정적 캐시를 적재하는 `trainParse`, `trainInitialize`, `trainStaticCacheLoad`는 서로 겹치지 않게 실행한다.

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
