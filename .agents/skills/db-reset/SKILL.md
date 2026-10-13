---
name: db-reset
description: .env의 DB에서 열차, 회원 데이터와 Batch 메타데이터만 남기고 나머지 테이블을 DROP한다. 예매, 주문, 결제 스키마가 바뀌어 테이블을 새로 만들어야 할 때 쓴다. Use when the user says "/db-reset", "열차 회원 빼고 테이블 날려줘", "DB 초기화해줘".
disable-model-invocation: true
allowed-tools: Bash(qa/db-scripts/drop-tables-except-train-member.sh *)
---

# DB Reset

스크립트: [qa/db-scripts/drop-tables-except-train-member.sh](../../../qa/db-scripts/drop-tables-except-train-member.sh)

- 남기는 테이블: 열차(station, station_fare, train, train_car, seat, train_schedule_template, schedule_stop_template, train_schedule, schedule_stop), member, `BATCH_*`
- 대상: `.env`의 `DB_URL`(호스트, 포트, DB), `DB_USERNAME`, `DB_PW`. 앱이 붙는 DB와 같다. 다른 DB를 지우려면 `.env`를 바꾼다.
- 필요: 터널(`ssh -f -N -L 13306:10.0.10.198:30060 raillo-bastion`), mysql 클라이언트(`brew install mysql-client`)

## 규칙

- **dry-run 결과를 보여주고 명시적 승인을 받은 뒤에만 `--execute`한다.** 승인은 그 실행 한 번에만 유효하다.
- 터널이 닫혀 있거나 클라이언트가 없으면 스크립트가 알려주는 명령을 사용자에게 전하고 멈춘다. 터널을 대신 열지 않는다.

## 절차

1. dry-run을 실행한다.

   ```bash
   qa/db-scripts/drop-tables-except-train-member.sh
   ```

2. 대상(`계정@호스트:포트/DB`)과 DROP할 테이블을 그대로 보여주고 멈춘다.

   ```
   대상: {대상}
   DROP: {테이블 목록}
   남김: {n}개

   이대로 DROP할까요?
   ```

3. 승인되면 실행한다.

   ```bash
   qa/db-scripts/drop-tables-except-train-member.sh --execute --yes
   ```

4. 결과와 후속 작업을 알린다.
   - dev 프로파일(`ddl-auto: update`)로 raillo-api를 띄우면 테이블이 다시 만들어진다. `./gradlew :raillo-api:bootRun`
   - Redis는 그대로다. 예매가 지워져도 좌석 점유 `B:{bookingId}`는 남는다.
