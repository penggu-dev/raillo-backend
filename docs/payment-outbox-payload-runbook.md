# Outbox payload 런북

`payment_outbox`의 `BOOKING_CONFIRMED` payload가 지원 버전과 다르거나 깨져 있을 때 **무엇이 몇 개 있는지 세고 어떻게 조치할지 정하는** 절차다. 설계 근거와 계약은 [payment-consistency.md](./payment-consistency.md)의 `payload 스키마 변경 절차`에 있다.

#### 측정된 이력 — 이 저장소에서 실제로 무슨 일이 있었나

`develop` push가 테스트를 통과하면 OKE 프로덕션에 배포된다(`.github/workflows/deploy_raillo_with_k8s.yml`은 `workflow_run`으로 "Build and Test with Gradle"의 `branches: [develop]` 성공에 걸려 있고 `group: raillo-oke-production`이다). 승인 게이트는 없다. 배포 실행 이력으로 확인한 시점:

| 배포 | 시각(UTC) | 생산자(#257) | payload v2(#266) |
|---|---|---|---|
| `f33d8a17` | 2026-09-21 08:52 | 있음 | **없음** |
| `4a0663de` | 2026-09-21 15:39 | 있음 | **없음** |
| `5c20daae` | 2026-09-22 03:31 | 있음 | 있음 |
| 이후 전부 | | 있음 | 있음 |

**프로덕션이 구 모양 payload를 쓸 수 있었던 창은 약 19시간이다**(09-21 08:52 ~ 09-22 03:31 UTC). #257의 develop 머지일(2026-09-13, 머지 커밋 `4166620f`)과 혼동하지 않는다 — 머지와 배포가 8일 떨어져 있었다. 커밋 날짜(2026-09-11)는 머지일이 아니다. 09-21 이전에는 `develop`을 배포하는 파이프라인 자체가 없었다(그때까지는 `main` push → AWS EKS였고, `bd452ca1`이 OKE·develop로 바꿨다).

그리고 **배포된 모든 SHA에서 `BookingConfirmedProcessor`에 `@Component`가 없었다.** 소비자가 프로덕션에서 한 번도 돌지 않았으므로 그 사이 쓰인 행은 전부 PENDING으로 남아 있을 것이다. 이 브랜치가 `@Component`를 붙여 워커가 처음으로 그 행들을 집게 된다.

운영에서 결제까지 테스트한 적이 없다는 것이 작업자 확인 사항이다. 그렇다면 행이 없을 가능성이 높다. **DB를 직접 확인하지 않았으므로 단정하지 않는다.**

#### 지금 상태를 확인하는 쿼리

MySQL 8.4이고 `payload`는 TEXT다. 아래 쿼리는 `mysql:8.4`(`ONLY_FULL_GROUP_BY` 기본값) 컨테이너에 7행 픽스처를 넣고 실행 확인했다.

**`LIKE '%"schemaVersion":2%'`로는 안 된다.** 실행으로 확인한 결함 넷:

| 행 | 실제 | `NOT LIKE` 판정 |
|---|---|---|
| `{ "schemaVersion" : 2 , ...}` | v2 | **구버전으로 오분류** — 콜론 뒤 공백 하나에 깨진다 |
| `{"schemaVersion":21,...}` | v21 | **v2로 오분류** — `":2"`가 부분 문자열로 들어 있다. 2로 시작하는 모든 버전이 v2로 보인다 |
| `{"schemaVersion":2,"paymentId":401` (깨짐) | 파싱 불가 | **v2로 오분류** — 깨진 앞부분에 `":2"`가 남아 있어 아예 안 보인다 |
| `{"schemaVersion":2}` (`bookings` 없음) | 처리 불가 | **v2로 오분류** — 버전만 보는 조건으로는 원리상 못 잡는다 |

두 번째가 가장 위험하다. 조용히 틀리고, 버전이 10을 넘기면 드러난다.

**`JSON_VALID` 가드는 선택이 아니다.** 가드 없이 `payload ->> '$.schemaVersion'`을 쓰면 깨진 행에서 `ERROR 3141`로 쿼리 전체가 죽는다(NULL이 아니다). `CASE`는 지연 평가되므로 `NOT JSON_VALID(payload)`를 첫 `WHEN`에 두면 뒤 분기가 깨진 행을 보지 않는다. `WHERE`에서도 같은 `CASE`로 감싼다 — `OR`의 평가 순서는 보장되지 않는다.

**분포 — 무엇이 몇 개 있나**

```sql
SELECT CASE
         WHEN NOT JSON_VALID(payload)                     THEN 'invalid-json'
         ELSE COALESCE(payload ->> '$.schemaVersion', 'no-schemaVersion-key')
       END AS schema_version,
       CASE
         WHEN NOT JSON_VALID(payload)                     THEN '-'
         WHEN JSON_EXTRACT(payload, '$.bookings') IS NULL THEN 'bookings-missing'
         ELSE 'ok'
       END AS bookings_check,
       status,
       count(*)        AS row_count,
       min(created_at) AS first_seen,
       max(created_at) AS last_seen
  FROM payment_outbox
 WHERE type = 'BOOKING_CONFIRMED'
 GROUP BY schema_version, bookings_check, status
 ORDER BY CAST(NULLIF(schema_version, 'invalid-json') AS UNSIGNED),
          schema_version, bookings_check, status;
```

`parse()`가 한 에러 코드로 덮는 세 조건이 결과표에서 분리돼 보인다 — 깨진 JSON은 `schema_version = 'invalid-json'`, 레거시 행은 `'no-schemaVersion-key'`, `bookings` 누락은 `bookings_check = 'bookings-missing'`이다. 적어도 조회 단계에서는 "에러 코드 하나가 세 사고를 덮는" 문제가 풀린다.

고칠 때 걸리는 지점 넷. 전부 실행으로 확인했다.

- `count(*) AS rows`는 **문법 오류**다. `ROWS`는 MySQL 8.0부터 윈도 함수 예약어다.
- `bookings_check`를 `SELECT`에만 넣고 `GROUP BY`에서 빼면 `ONLY_FULL_GROUP_BY`가 `ERROR 1055`로 거절한다.
- `->>`(`JSON_UNQUOTE(JSON_EXTRACT(...))`)로 문자열로 통일한다. `JSON_EXTRACT`는 JSON 타입을 돌려주므로 문자열 리터럴과 한 `CASE`에 섞이면 강제 변환되고 표기가 환경에 따라 달라진다.
- `->>`가 문자열이므로 **정렬도 문자열이다.** `ORDER BY schema_version`만 쓰면 `21`이 `3` 앞에 온다. `CAST(... AS UNSIGNED)`로 숫자 정렬하고, 숫자가 아닌 `'invalid-json'`은 `NULLIF`로 빼서 `CAST` 경고를 피한다.

**조치용 행 목록 — 무엇을 어떻게 할지 정하려면 ID가 필요하다**

```sql
SELECT payment_outbox_id, aggregate_id, status, retry_count, created_at,
       CASE
         WHEN NOT JSON_VALID(payload)                     THEN 'a:invalid-json'
         WHEN payload ->> '$.schemaVersion' IS NULL       THEN 'b:no-schemaVersion-key'
         WHEN payload ->> '$.schemaVersion' <> '2'        THEN 'b:unsupported-version'
         WHEN JSON_EXTRACT(payload, '$.bookings') IS NULL THEN 'c:bookings-missing'
       END AS reason
  FROM payment_outbox
 WHERE type = 'BOOKING_CONFIRMED' AND status = 'PENDING'
   AND CASE
         WHEN NOT JSON_VALID(payload)                     THEN TRUE
         WHEN payload ->> '$.schemaVersion' IS NULL       THEN TRUE
         WHEN payload ->> '$.schemaVersion' <> '2'        THEN TRUE
         WHEN JSON_EXTRACT(payload, '$.bookings') IS NULL THEN TRUE
         ELSE FALSE
       END
 ORDER BY created_at;
```

`CASE`의 `WHEN`은 위에서 처음 맞는 하나만 쓰이므로 사유가 하나로 확정된다 — 깨진 행은 뒤 조건을 아예 따지지 않는다. `WHERE`의 `CASE`는 `SELECT`의 것과 같은 순서·같은 조건이어야 한다. **어긋나면 `reason`이 `NULL`인 행이 나오고, 그게 두 곳이 안 맞는다는 신호다.**

`schema_version`이 `2`가 아니거나 `bookings_check`가 `ok`가 아닌 행이 0이면 할 일이 없다. 0이 아니면 아래 절차를 적용한다.
## 행별 실패 원인을 가려낼 수 있는 범위

`payment_outbox`에는 실패 원인을 담는 컬럼이 없어, FAILED 행을 보고 알 수 있는 것은 "실패했다"와 "몇 번 시도했다"까지다. 재투입 전에 원인을 확인해야 하므로 가려낼 수 있는 범위를 먼저 본다.

| | 식별 수단 |
|---|---|
| 구 payload 행 | `payload`로 직접 식별된다 — 위 쿼리가 그 용도다 |
| 즉시 FAILED된 오염 행 | `retry_count = 0`. 재시도를 소진한 FAILED는 `retry_count = 4`다(`markFailed()`는 증가시키지 않는다). `processed_at - created_at`도 ~0 대 ~7.5분으로 갈린다 |
| **재시도를 소진한 FAILED 안에서** 충돌·타임아웃·스크립트 오류·오염 | **가려낼 수단이 없다.** 두 원인이 섞인 경우도 구분되지 않는다 |

마지막 줄이 실제 공백이다. 대응이 정반대인데(구 payload는 변환이나 폐기, 좌석 오염은 Redis 값 정리) 행에 남는 것은 "재시도를 다 썼다"까지다. 행별 원인은 로그에만 있고(`[Outbox 처리 실패]`, `[Outbox 재시도 불가 - 즉시 FAILED]`, `[Outbox 최대 재시도 초과]`) 로그는 보존 기간이 지나면 사라진다.

수동 SQL 재투입 절차는 [reservation-cache-schema.md](./reservation-cache-schema.md) 2장에 있다.

## v1 payload가 들고 있던 것 — 복원 가능성 판정 근거

v1 행을 실제로 만나면 payload만으로 v2를 만들 수 없다. 기록으로 남긴다.

| v2가 쓰는 것 | v1에 있었나 |
|---|---|
| `bookingId`(소비자가 키로 쓴다) | **없음.** v1의 `pendingBookingId`는 Redis ID다 |
| 객차 ID(`trainCarId`) | 없음 |
| 운행일(`operationDate`) | 없음 |
| 구간 | stop PK(`departureStopId`/`arrivalStopId`)로만 있었다. v2는 stopOrder를 쓴다 |

그래서 v1→v2는 DB 조회 없이는 변환할 수 없고, 위 절차의 3번(적체 비우기) 대상이다.

> `payment_outbox`의 컬럼 목록은 [payment-data-contracts.md](./payment-data-contracts.md)에 있다. 저장소에 DDL이 없고 prod는 `ddl-auto: validate`라, 엔티티가 매핑하지 않은 컬럼이 실제 스키마에 있는지는 확인하지 않았다.
