# 열차 기준정보 Redis 스키마

예약 생성 시 DB 조회 없이 운행·정차역·좌석·운임을 검증하기 위한 캐시의 저장 계약이다.

적재는 `raillo-batch`가, 조회는 `raillo-api`가 담당한다. 두 모듈은 서로 의존하지 않으므로 키 포맷과 값 타입은 `raillo-domain`의 `com.sudo.raillo.train.cache` 패키지에 둔다. 양쪽 모두 이 패키지를 참조한다.

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

> **적재 시점 기준 상대 TTL을 쓰면 안 된다.** `trainMonthlySchedule`이 한 달치를 미리 만들기 때문에, `2d` 같은 상대 TTL은 운행일이 오기도 전에 만료된다.

여유를 2일로 잡은 이유는 자정을 넘겨 운행하는 열차가 있고, 예매 마감이 출발 5분 전이며, 예약 자체의 TTL이 10분이기 때문이다. 값은 `train.cache.retention-days`로 조절하며 1 이상이어야 한다. `train.cache.pipeline-size`도 1 이상이어야 하고, 어기면 기동 시점에 실패한다.

## 4. 적재 주체

| Job | 적재 대상 |
|---|---|
| `trainParse` | 정적 데이터 (파싱 Step 뒤) |
| `trainDailySchedule` | 오늘부터 대상 운행일까지의 운행 데이터 |
| `trainMonthlySchedule` | 생성한 한 달치 운행 데이터 |
| `trainInitialize` | 위 전부 |
| `trainStaticCacheLoad` | 정적 데이터 (단독) |
| `trainScheduleCacheLoad` | 운행 데이터 (단독, 날짜 지정) |

단독 Job은 DB를 건드리지 않고 캐시만 다시 채운다. Redis는 진실 공급원이 아니므로 유실되었을 때의 복구 경로가 필요하다.

스케줄 생성 Job은 **이미 스케줄이 있어 건너뛴 날짜도 캐시 적재 대상에 포함**한다. 그래서 같은 Job을 다시 돌리는 것만으로 캐시가 복구된다.

`trainDailySchedule`은 적재 범위를 오늘부터 잡는다. 날짜를 생략하면 매 실행이 "마지막 운행일 + 1"을 생성하므로, 캐시 적재만 실패한 날짜는 재실행해도 대상에서 빠진다. 범위를 오늘부터 잡아 다음 실행이 이를 메운다.

`trainScheduleCacheLoad`는 `fromDate`가 `toDate`보다 늦으면 실패한다.

## 5. 오래된 키 정리

`trainParse` 재실행 시 운임표가 통째로 교체되므로, 없어진 구간의 운임이 남아 잘못 읽히면 안 된다. 정적 데이터 적재는 저장을 마친 뒤 DB에 없는 키를 지운다.

- `train:fare`: `HKEYS` 차집합으로 `HDEL`
- `train:seat:*`, `train:traincar:*`, `train:station:*`: `SCAN` 차집합으로 `DEL` (`KEYS`는 쓰지 않는다)

저장이 먼저고 삭제가 나중이다. 반대로 하면 두 동작 사이에 키가 비는 구간이 생긴다.

단, DB에서 한 건도 읽지 못하면 정리를 건너뛴다. 조회 실패로 전체 캐시가 날아가는 쪽이 오래된 키가 남는 것보다 나쁘기 때문이다.

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
