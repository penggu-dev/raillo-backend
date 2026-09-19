# 열차 기준정보 Redis 스키마

예약 생성 시 DB 조회 없이 운행·정차역·좌석·운임을 검증하기 위한 캐시의 저장 계약이다.

`raillo-batch`가 적재하고, `raillo-api`가 예약 생성 시 조회하는 저장 계약이다. 두 모듈은 서로 의존하지 않으므로 공통 키 포맷과 값 타입은 `raillo-domain`의 `com.sudo.raillo.train.cache` 패키지에 둔다.

- 키·만료 계산: `TrainCacheKey`
- 값 타입: `SeatCacheValue`, `TrainCarCacheValue`, `ScheduleInfoCacheValue`, `ScheduleStopCacheValue`, `StationFareCacheValue`

## 1. 키 목록

### 정적 데이터 (만료 없음)

| 키 | 타입 | 값 |
|---|---|---|
| `train:seat:{seatId}` | String | `SeatCacheValue` JSON |
| `train:traincar:{trainCarId}` | String | `TrainCarCacheValue` JSON |
| `train:station:{stationId}` | String | 역명 문자열 (JSON 아님) |
| `train:fare` | Hash | field `{출발역Id}:{도착역Id}` → `일반실:특실` |

### 일일 운행 데이터 (운행일 기준 만료)

| 키 | 타입 | 값 |
|---|---|---|
| `{schedule:{trainScheduleId}}:info` | String | `ScheduleInfoCacheValue` JSON |
| `{schedule:{trainScheduleId}}:stops` | Hash | field `st:{역Id}` 와 `id:{정차역Id}` → `ScheduleStopCacheValue` JSON |

운행 단위 키는 `{schedule:id}` hash tag를 쓴다. 좌석 Hold 키(`{schedule:1001}:seat:12:hold:...`)와 같은 형식이라 Redis Cluster에서 한 운행의 모든 키가 같은 slot에 놓인다. 이 형식을 바꾸면 Lua 스크립트가 여러 slot에 걸쳐 실패한다.

## 2. 값 형식

모든 값은 평문 문자열이다. Java 타입 메타데이터(`@class`)를 쓰지 않는다. 값에 클래스명이 박히면 읽는 쪽이 같은 FQCN을 가져야 해서 두 모듈이 묶이기 때문이다.

```text
train:seat:12
{"trainCarId":231,"carNumber":3,"carType":"STANDARD","seatRow":12,"seatColumn":"A","seatType":"WINDOW"}

{schedule:1001}:info
{"trainScheduleId":1001,"operationDate":"2026-10-20","departureTime":"06:00:00","arrivalTime":"08:52:00",
 "operationStatus":"ACTIVE","delayMinutes":0,"trainId":7,"trainNumber":101,"trainName":"KTX",
 "departureStationId":1,"arrivalStationId":5}

{schedule:1001}:stops  field st:1
{"stopId":9001,"stopOrder":0,"stationId":1,"stationName":"서울","arrivalTime":null,"departureTime":"06:00:00"}

train:fare  field 1:5
59800:83700
```

### 규칙

| 항목 | 규칙 |
|---|---|
| 날짜 | `yyyy-MM-dd` 문자열 |
| 시각 | `HH:mm:ss` 문자열. 숫자 배열이 아니다 |
| enum | 이름 문자열 (`STANDARD`, `WINDOW`, `ACTIVE`) |
| 운임 | `BigDecimal.stripTrailingZeros().toPlainString()`. DB가 `59800.00`이어도 `59800`으로 저장된다 |
| 기점·종점 | 기점의 `arrivalTime`과 종점의 `departureTime`은 `null`이다 |

`StationFareCacheValue.parse()`가 운임 문자열을 되돌린다. 읽는 쪽에서 형식을 다시 구현하지 않는다.

### 정차역을 두 field에 중복 저장하는 이유

조회 방향이 둘이다. 예약을 만들 때는 사용자가 고른 **역 ID**로 찾고, 예약을 조회하거나 삭제할 때는 예약(`Reservation`)에 저장해 둔 **정차역 ID**로 되짚는다. 예약이 `trainScheduleId`를 함께 들고 있어 두 경로 모두 같은 Hash에 도달할 수 있다. 운행당 정차역이 10~15개라 값을 두 벌 갖는 비용보다 양방향 단건 조회의 이점이 크다.

## 3. 만료

정적 데이터는 만료를 걸지 않는다. `trainParse`를 다시 실행할 때 갱신된다.

운행 데이터는 **운행일 기준 절대 시각**으로 만료한다. `TrainCacheKey.expireAtEpochSecond(operationDate)`가 운행일 + 2일의 한국 시간 자정을 돌려주고, `EXPIREAT`으로 건다.

예를 들어 10월 1일에 미리 적재한 10월 20일 운행 데이터도 **10월 22일 00:00 KST**에 만료된다. 운행 종료 후 48시간이 아니라 운행 날짜에 2일을 더한 날의 자정이 기준이다.

> **적재 시점 기준 상대 TTL을 쓰면 안 된다.** `trainMonthlySchedule`이 한 달치를 미리 만들기 때문에, `2d` 같은 상대 TTL은 운행일이 오기도 전에 만료된다.

여유를 2일로 잡은 이유는 자정을 넘겨 운행하는 열차가 있고, 예매 마감이 출발 5분 전이며, 예약 자체의 TTL이 10분이기 때문이다. 값은 `train.cache.retention-days`로 조절하며 1 이상이어야 한다. `train.cache.pipeline-size`도 1 이상이어야 하고, 어기면 기동 시점에 실패한다.

## 4. 적재 주체

| Job | 실행 흐름 |
|---|---|
| `trainParse` | 파일 파싱 → DB 저장 → 정적 데이터를 Redis에 적재 |
| `trainDailySchedule` | 하루치 스케줄 DB 생성 → 오늘과 대상 운행일 중 이른 날짜부터 대상 운행일까지 Redis에 적재 |
| `trainMonthlySchedule` | 한 달치 스케줄 DB 생성 → 해당 기간의 운행 데이터를 Redis에 적재 |
| `trainInitialize` | 파싱 → 정적 캐시 적재 → 월간 스케줄 생성 → 운행 캐시 적재 |
| `trainStaticCacheLoad` | 기존 DB 조회 → 정적 데이터를 Redis에 적재 |
| `trainScheduleCacheLoad` | 기존 DB에서 지정 날짜 조회 → 운행 데이터를 Redis에 적재 |

이름에 `Cache`가 없는 파싱·스케줄 생성 Job도 DB 저장 뒤에 Redis 적재 Step을 실행한다.

`trainStaticCacheLoad`와 `trainScheduleCacheLoad`는 **캐시 적재 Step만 단독으로 실행하는 Job**이다. DB의 열차 데이터를 생성·수정하지 않고, 기존 데이터를 조회해서 Redis를 다시 채운다. 열차 기준정보의 원본은 DB에 있으므로 Redis 데이터가 유실되어도 이 두 Job으로 복구할 수 있다.

스케줄 생성 Job은 **DB에 이미 스케줄이 있으면 생성만 건너뛰고, Redis 적재는 그대로 실행한다.** 예를 들어 `trainDailySchedule`을 `operationDate=2026-10-20`으로 실행하면 다음과 같이 동작한다.

1. DB에 10월 20일 스케줄이 없으면 생성한다. 이미 있으면 중복으로 만들지 않는다.
2. 어느 경우든 DB에서 적재 대상 기간의 스케줄을 읽어서 Redis에 저장한다. 실행일이 10월 1일이라면 10월 1일부터 20일까지, 실행일이 10월 20일 이후라면 10월 20일 하루가 대상이다.

일간 Job의 캐시 적재 범위는 **오늘(한국 시간)과 대상 운행일 중 이른 날짜부터 대상 운행일까지**다. 대상 운행일이 미래라면 오늘부터 그날까지의 기존 스케줄도 다시 적재하고, 과거라면 지정한 하루만 적재한다. `operationDate`를 생략하면 DB의 마지막 운행일 다음 날을 생성하지만, 캐시는 오늘부터 다시 채우므로 이전 실행에서 누락된 오늘 이후의 캐시도 복구한다. 과거 날짜나 특정 기간만 복구하려면 `trainScheduleCacheLoad`에 날짜를 지정한다.

`trainScheduleCacheLoad`는 `fromDate`가 `toDate`보다 늦으면 실패한다.

## 5. 오래된 키 정리

`trainParse`, `trainInitialize`, `trainStaticCacheLoad`는 정적 데이터 적재 후 오래된 캐시를 정리한다. 운행 데이터 적재 Job에는 이 정리 과정이 없다.

예를 들어 DB에서 서울→광주 운임이 삭제되어도, 나머지 구간을 Redis에 덮어쓰는 것만으로는 기존 서울→광주 항목이 사라지지 않는다. 그래서 **Redis에 있는 항목에서 이번에 DB에서 읽은 항목을 뺀 차집합**을 구해 삭제한다.

| 대상 | 저장 구조 | 오래된 항목 정리 |
|---|---|---|
| 운임 | `train:fare`라는 Hash 하나 안에 구간별 field 저장 | `HKEYS`로 field 목록을 읽고 DB에 없는 구간만 `HDEL` |
| 좌석·객차·역 | `train:seat:12`처럼 데이터마다 개별 키 저장 | `SCAN`으로 해당 패턴의 키를 읽고 DB에 없는 키만 `DEL` |

**최신 데이터를 먼저 저장한 뒤 오래된 항목을 삭제한다.** 기존 캐시를 전부 비우고 다시 채우는 방식에서 생기는 조회 공백을 피하기 위한 흐름이다. 두 작업 전체가 원자적으로 실행되는 것은 아니다.

좌석·객차·역·운임 모두 **해당 종류의 DB 조회 결과가 0건이면 정리를 건너뛰고 기존 캐시를 유지한다.**

이는 **조회에 성공했지만 결과가 비어 있는 경우**다. DB 조회 중 예외가 발생하면 해당 종류의 저장·삭제 단계로 진행하지 않고 Job이 실패한다. 앞서 다른 종류에 대해 완료한 Redis 작업까지 되돌리지는 않는다.

## 6. 용량과 운영

`trainInitialize`를 실제 시간표로 실행한 결과다. 열차 418대, 운행 31일치 기준이며 다른 데이터가 없는 Redis에서 측정했다.

| 구분 | 키 수 | 키당 평균 | 합계 | 비중 |
|---|---|---|---|---|
| 좌석 | 272,462 | 147 B | 38.2 MB | 54% |
| 정차역 | 10,864 | 2,795 B | 29.0 MB | 41% |
| 운행 정보 | 10,864 | 303 B | 3.1 MB | 4% |
| 객차 | 5,028 | 154 B | 0.7 MB | 1% |
| 운임 | 1 (field 1,366) | 75.6 KB | 0.1 MB | - |
| 역 | 77 | 48 B | 4 KB | - |

데이터셋 66.2 MB, Redis 오버헤드까지 더해 `used_memory` 81.7 MB, RSS 91.5 MB다.

### 필요 용량 계산

정적 데이터 39 MB는 고정이고 운행 데이터만 날짜에 비례한다. 운행 데이터는 만료로 자동 정리되므로 유지 기간만큼에서 평형을 이룬다.

```text
필요 용량 = 39 MB + (1.04 MB × 유지할 일수)
```

한 달 창을 유지하면 약 70 MB다.

### 적재 소요 시간

| Step | 소요 | 처리 건수 |
|---|---|---|
| 정적 적재 | 7.4초 | 278,933 |
| 운행 적재 | 2.9초 | 10,864 (정차역 90,762 포함) |

같은 실행에서 Excel 파싱이 1분 50초, 스케줄 생성이 38초였다. 전체 2분 48초 중 캐시 적재는 10초로, 병목이 아니다.

### 축출 정책을 확인할 것

만료가 걸린 키는 운행 데이터뿐이고 **정적 데이터 277,568개에는 만료가 없다.**

운영 Redis에 `maxmemory`가 설정되어 있고 정책이 `allkeys-lru` 같은 `allkeys-*` 계열이면 좌석 키가 축출될 수 있다. 그러면 조회 측이 캐시 미스를 맞는다. `volatile-*` 계열은 만료가 설정된 키만 축출하므로 정적 데이터가 안전하다.

예매·인증 데이터가 같은 Redis를 쓰므로 기준정보 80 MB가 얹히는 것을 감안해 Pod 메모리 한도에 여유를 둔다.

### 운영 시 고려사항

- **정적 캐시를 적재하는 Job은 겹치지 않게 실행한다.** DB 변경과 적재가 겹치면, 이전 DB 목록을 읽은 Job이 다른 Job에서 새로 적재한 키를 오래된 키로 판단해 삭제할 수 있다. 현재 공통 잠금은 없으며, 동시 실행이 필요해지면 직렬화 장치를 검토한다.
- **운행 값 저장과 만료 설정은 별도 명령이다.** 첫 명령만 반영된 상태에서 연결이 끊기면 만료 없는 키가 남을 수 있다. 같은 날짜를 다시 적재하면 만료 시각도 다시 설정된다. 장애 대응을 강화할 때 값과 만료 설정을 한 번에 처리하는 방식을 검토한다.
