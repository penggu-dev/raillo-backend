# 에러 코드 컨벤션

raillo 백엔드의 모든 도메인 `ErrorCode` enum이 따르는 규칙. 새 에러 코드를 추가하거나 변경할 때 이 문서를 따른다.

## 형식

`{DOMAIN}_{NNN}`

- `{DOMAIN}`: 도메인 패키지명 풀워드(대문자). 아래 접두사 맵 참고.
- 구분자: 언더스코어 1개.
- `{NNN}`: 3자리 숫자. 백의 자리 = 카테고리(밴드).

예: `BOOKING_101`, `TRAIN_203`, `PAYMENT_901`.

## 접두사 맵

| 도메인 / Enum | 접두사 |
|---------------|--------|
| auth / `AuthError` | `AUTH_` |
| auth / `TokenError` | `TOKEN_` |
| member / `MemberError` | `MEMBER_` |
| booking / `BookingError` | `BOOKING_` |
| train / `TrainError` | `TRAIN_` |
| order / `OrderError` | `ORDER_` |
| payment / `PaymentError` | `PAYMENT_` |
| redis / `RedisError` | `REDIS_` |
| global / `GlobalError` | `GLOBAL_` |

enum 클래스명은 `{Domain}Error` 형식으로 통일한다.

## 카테고리 밴드 (백의 자리)

에러가 많은 대형 도메인은 백의 자리로 카테고리를 구분한다.

| 도메인 | 1xx | 2xx | 3xx | 4xx | 5xx | 9xx |
|--------|-----|-----|-----|-----|-----|-----|
| `BOOKING_` | 예매/좌석예약 | 승차권 | 임시예약 | 좌석점유·충돌 | 영수증 | — |
| `TRAIN_` | 열차/스케줄 | 객차/좌석 | 역/구간/운임 | 날짜/검색 | — | — |
| `PAYMENT_` | 결제 상태 | 금액/수단/키 | — | — | — | 시스템 |
| `GLOBAL_` | 클라이언트(0xx) | — | — | — | 서버(5xx) | — |

소형 도메인(`AUTH_`, `TOKEN_`, `MEMBER_`, `ORDER_`, `REDIS_`)은 카테고리가 단일하므로 밴드 없이 `001`부터 평면 순번을 쓴다.

## 새 에러 코드 추가 절차

1. 에러가 속한 **도메인 enum**을 고른다(접두사 맵).
2. 대형 도메인이면 해당하는 **밴드(백의 자리)** 를 정한다.
3. 그 밴드(또는 평면) 내에서 **다음 번호**를 부여한다.
4. `("메시지", HttpStatus.XXX, "{DOMAIN}_{NNN}")` 형식으로 상수를 추가한다.
5. 도메인 enum은 `ErrorCode` 인터페이스를 구현한다(`getMessage`/`getStatus`/`getCode`).

예) 승차권 관련 새 에러 → `BookingError`의 2xx 밴드 → 마지막이 `BOOKING_204`면 `BOOKING_205`.
