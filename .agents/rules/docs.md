# 문서 업데이트 규칙

`docs/` 아래 문서를 언제 어떻게 갱신하는지 정한다. 코드 변경이 문서를 낡게 만들었는데 그대로 머지되는 것을 막는 것이 목적이다.

## 문서의 두 종류

**계약 문서** — 현재 코드가 어떻게 동작하는지를 적는다. 코드가 바뀌면 같은 PR에서 함께 고친다. 낡으면 틀린 문서가 된다.

```
deployment.md            domain-model.md          error-code-convention.md
payment-cases.md         payment-consistency.md   payment-data-contracts.md
reservation-cache-schema.md   seat-conflict-validation.md
train-cache-schema.md    payment-flow.html
diagrams/payment-flow/
```

**계획 문서** — 특정 시점에 무엇을 하려 했는지를 적는다. 작성 시점이 내용의 일부이므로 **구현이 끝나도 고치지 않는다.** 낡은 것이 정상이다.

```
payment-reservation-revised-plan.md    payment-reservation-work-roadmap.md
superpowers/plans/    superpowers/specs/    superpowers/notes/
```

계획 문서를 현재 상태로 고치려 하지 않는다. 계획과 실제가 달라졌으면 계약 문서를 고친다.

## 변경에 따른 갱신 대상

왼쪽에 해당하는 변경이면 오른쪽 문서를 **같은 PR에서** 갱신한다.

| 이번 작업이 바꾸는 것 | 갱신할 문서 |
|---|---|
| `booking/cache` 키나 값 계약, 예약 관련 Lua 스크립트, 좌석 점유 field 만료 | `reservation-cache-schema.md` |
| `train/cache` 키 포맷이나 값 타입, 기준정보 적재 Job, 좌석 캐시를 읽는 방식 | `train-cache-schema.md` |
| 좌석 충돌 검증 계층(Validator, Lua 점유, 결제 준비 재검증) | `seat-conflict-validation.md` |
| Toss와 DB, Redis의 상태 정합성 설계, Outbox나 Recovery Worker의 동작, PaymentAttempt 상태 전이 | `payment-consistency.md` |
| 결제 케이스의 트리거, 응답, 데이터 상태 변화 | `payment-cases.md` |
| 결제나 예약의 실제 저장 형식(테이블 컬럼, Redis 값, Outbox payload 스키마) | `payment-data-contracts.md` |
| 결제 흐름의 시퀀스 자체 | `diagrams/payment-flow/specs/`의 해당 spec과 그것으로 렌더한 `diagrams/payment-flow/*.html`. 그 다이어그램이 `payment-flow.html`에 링크돼 있으면 그 허브 페이지까지 |
| 새 `{Domain}Error` enum 추가로 접두사 맵이 늘어남, 새 카테고리 밴드 도입 | `error-code-convention.md` |
| 엔티티 추가나 관계 변경, Status enum 값 추가, 한국어 도메인 용어 | `domain-model.md` |
| `k8s/oke` 매니페스트, 배포 워크플로, 이미지 태그 규칙 | `deployment.md` |
| 모듈이나 패키지 구조, 레이어 규칙, 새 Batch Job, 스킬이나 규칙 추가 | 루트 `AGENTS.md`와 `.agents/rules/` |
| 테스트 환경, 금지 사항, Fixture나 TestHelper 추가, 작성 컨벤션 | `.agents/rules/test.md` |
| 레이어 규칙, 예외 분리, 트랜잭션, 네이밍 | `.agents/rules/code-convention.md` |

이 표가 단일 소스다. `/issue`와 `/pr`은 이 표를 참조하고 재정의하지 않는다.

## 판단 시 주의 사항

- **여러 행에 걸리면 걸린 문서를 모두 넣는다.** 결제와 예약은 한 변경이 여러 문서를 함께 무효화하는 일이 흔하다. 예약 Redis 값은 계약 원본인 `reservation-cache-schema.md`와 사람용 요약인 `payment-data-contracts.md`를 함께 갱신한다. 커밋 `a3967b49` 하나가 결제와 예약 문서 7개를 함께 고친 것이 실제 사례다.
- **기존 enum에 에러 코드 하나를 더하는 것은 문서 변경이 아니다.** `error-code-convention.md`에는 개별 코드 목록이 없고 형식과 접두사 맵, 밴드, 추가 절차만 있다. 접두사 맵이나 밴드가 늘어날 때만 갱신 대상이다.
- `train-cache-schema.md`는 예약 생성 검증용 기준정보 캐시의 저장 계약이다. 조회 API의 응답 캐시처럼 성격이 다른 캐시는 이 문서의 범위가 아니다. "캐시를 다루면 이 문서"로 일반화하지 않는다.
- 표에 없고 기존 문서의 서술과 어긋나거나 기존 문서가 다루는 계약을 새로 늘리는 변경이면, 해당 문서를 찾아 갱신 대상으로 넣는다.
- 판단이 서지 않으면 추측한 문서명을 체크리스트에 넣지 않고 사용자에게 묻는다.

## 작업 맥락에 따라 먼저 읽을 문서

위 표의 반대 방향이다. 작업을 시작할 때 읽는다.

| 작업 | 먼저 읽을 문서 |
|---|---|
| 예약 생성, 좌석 점유, Lua 스크립트 | `reservation-cache-schema.md` |
| 기준정보 캐시 적재나 조회 | `train-cache-schema.md` |
| 좌석 충돌 검증 변경 | `seat-conflict-validation.md` |
| 결제 정합성, Outbox, 복구 | `payment-consistency.md`, `payment-cases.md`, `payment-data-contracts.md` |
| 에러 코드 추가 | `error-code-convention.md` |
| 새 도메인이나 엔티티, Booking Flow 이해 | `domain-model.md` |
| 테스트 작성 | `.agents/rules/test.md`, `.agents/skills/test/SKILL.md` |
| 배포, 인프라, K8s | `deployment.md` |

## 다이어그램

`diagrams/payment-flow/`는 세 파일이 한 묶음이다. spec을 고치면 나머지 둘이 함께 바뀐다.

```
specs/{name}.sequence.json   ← 원본
{name}.html                  ← spec으로 렌더한 결과
{name}.delivery.json         ← 렌더 메타데이터
```

`{name}.html`만 직접 고치지 않는다. 다음 렌더에서 덮어쓰인다. spec을 고치고 다시 렌더한다.

## 새 문서 추가 절차

1. 계약 문서인지 계획 문서인지 정한다. 계약 문서면 첫 문단에 **범위 한 문장**을 적는다. 무엇을 다루고 무엇을 다루지 않는지가 그 문장으로 판별돼야 한다.
2. 위 두 표에 행을 추가한다.
3. 루트 `AGENTS.md`의 해당 목록에도 추가한다. **한쪽만 고치면 표가 조용히 낡는다.**
4. 문서를 옮기거나 이름을 바꾸거나 범위를 바꿀 때도 같은 세 곳을 함께 고친다.

## 문체

`docs/` 아래 문서는 **"-한다" 서술체**로 쓴다. "-습니다"는 쓰지 않는다. 기존 문서가 그 관례다.

이슈 본문과 PR 본문은 반대로 "-습니다" 완결형이다. 그쪽 규칙은 `/issue`와 `/pr` SKILL.md에 있다.

가운뎃점(`·`)을 쓰지 않는다. "승인과 취소" 또는 "승인, 취소"로 쓴다. **새로 쓰는 문장에만 적용한다.** 기존 문서에 아직 많이 남아 있고, 그걸 걷어내는 것은 별도 작업이다. 다른 작업의 PR에서 함께 고치지 않는다.
