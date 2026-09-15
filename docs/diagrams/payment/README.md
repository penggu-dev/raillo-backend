# 결제 승인 흐름 다이어그램

결제 승인 흐름의 트랜잭션 경계, 호출 순서, 후속 복구 설계를 시각화한 인터랙티브 다이어그램의 원본 스펙이다. 렌더러는 [Archify](https://github.com/tt-a1i/archify)를 사용한다.

## 파일 구성

| 파일 | 유형 | 답하는 질문 |
|---|---|---|
| `confirm-transactions.json` | Workflow | confirm 승인 흐름을 왜 TX A · Toss 호출 · TX B 세 단계로 나눴고, 각 단계가 어디서 커밋되며 실패 시 어떤 상태가 남는가 |
| `confirm-sequence.json` | Sequence | 실제 코드에서 클래스 간 호출 순서와 정상 승인·재요청·Toss 오류·확정 실패의 예외 분기가 어떻게 이어지는가 |
| `payment-recovery-overview.json` | Workflow | 현재 구현 흐름과 후속 OutboxWorker(#266) · PaymentRecoveryWorker(#257 후속)가 전체적으로 어떻게 연결되며, 무엇이 구현됐고 무엇이 남았는가 |

각 다이어그램의 세부 근거는 다음 문서와 이슈를 참고한다.

- [docs/payment-approval-flow.md](../../payment-approval-flow.md)
- [docs/payment-consistency.md](../../payment-consistency.md)
- 이슈 #257 (결제 승인 트랜잭션 분리)
- 이슈 #266 (Outbox 도입 및 Redis 정리 비동기 처리)

## 로컬에서 HTML로 열어보기

Archify 렌더러는 각 JSON을 자체 완결형 HTML로 변환한다. 생성된 HTML은 별도 서버 없이 브라우저에서 바로 열 수 있으며, 테마 전환, 팬·줌, 검색, 관심 노드 하이라이트, 정적 이미지 export(PNG/SVG/JPEG/WebP) 등 뷰어 기능을 내장한다.

### 사전 준비

Node.js 18 이상이 필요하다. Archify는 별도 의존성 설치가 필요 없다.

```bash
git clone https://github.com/tt-a1i/archify.git
```

### HTML 생성

이 디렉터리에서 다음 명령을 실행한다. `<archify-root>`는 Archify 저장소를 클론한 경로다.

```bash
ARCHIFY=<archify-root>/bin/archify.mjs

node "$ARCHIFY" deliver workflow confirm-transactions.json         confirm-transactions.html         --quality showcase
node "$ARCHIFY" deliver sequence confirm-sequence.json             confirm-sequence.html             --quality showcase
node "$ARCHIFY" deliver workflow payment-recovery-overview.json    payment-recovery-overview.html    --quality standard
```

`payment-recovery-overview`는 3-lane 밀도 때문에 `standard` 프로필로 딜리버한다. 나머지 두 개는 `showcase`를 통과한다.

생성된 `*.html`을 로컬 브라우저에서 열면 그대로 확인할 수 있다.

### 스펙 수정 후 재생성

카드 문구나 노드 라벨을 바꾼 경우 위 명령을 다시 실행하면 된다. Archify는 검증(레이아웃 제약, 라벨 가독성 등)을 통과할 때만 HTML을 갱신한다.

## 시각 규약

- **빨간 점선 lane과 "EX" 프리픽스**: 예외 경로를 정상 흐름과 구분하는 Archify의 자동 표시(`variant: "exception"`)다.
- **붉은 노드(security 타입)**: Archify의 시각 스타일 카테고리 중 하나로, 실패·차단·복구 분기에 사용한다. 도메인상 보안 개념과는 무관하다.
- **점선 엣지(dashed)**: 아직 구현되지 않았거나 후속 이슈에서 다룰 흐름을 표시한다.
