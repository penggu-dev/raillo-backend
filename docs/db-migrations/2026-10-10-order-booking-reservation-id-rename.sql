-- #310: order_booking.pending_booking_id를 reservation_id로 rename한다.
-- Java 필드는 이미 reservationId이고 물리 컬럼만 옛 이름으로 남아 있었다. ddl-auto는 rename을 반영하지 못한다.
--
-- 하위 호환이 아니다. 구 코드는 pending_booking_id를, 신 코드는 reservation_id를 요구한다.
-- 한쪽만 적용된 상태에서는 애플리케이션이 기동하지 않는다.
--
-- ## 운영 적용 순서
--
--   1. kubectl -n api-server scale deployment/raillo-api --replicas=0
--   2. kubectl -n api-server get pods -l app=raillo-api      # 0개 확인
--   3. 아래 확인 질의로 현재 컬럼명 확인
--   4. 아래 ALTER 적용
--   5. PR 머지. CI의 kubectl apply가 replicas 1을 복원한다
--
-- 중단은 1단계부터 배포 완료까지다. 보통 8분이지만 1시간을 넘긴 배포 실행도 있었다.
--
-- CI가 실패해 0 replicas로 남으면 scale 1로 복구되지 않는다. 구 이미지도 바뀐 컬럼에서 기동하지 못한다.
-- 역방향 ALTER를 먼저 적용하고 scale 1로 되살린 뒤 재시도한다.
--
-- ## dev와 로컬
--
-- ALTER를 먼저 적용하고 앱을 띄운다. 순서가 바뀌면 ddl-auto: update가 reservation_id를 새로 만들고
-- pending_booking_id의 NOT NULL이 남아 INSERT가 실패한다.
--
-- ## 롤백
--
--   ALTER TABLE order_booking RENAME COLUMN reservation_id TO pending_booking_id;
--
-- 컬럼을 되돌리기 전에 kubectl rollout undo를 쓰지 않는다. 롤백 경로는 역방향 ALTER를 들고 가지 않아
-- 같은 기동 실패가 재생산된다. 롤백도 scale 0 → 역방향 ALTER → 배포 순서를 지킨다.
--
-- ## 적용 현황 (2026-10-10)
--
--   운영 적용 완료 · 로컬 적용 완료(raillo-test-mysql, raillo-mysql) · dev 미확인

-- 적용 여부 확인. pending_booking_id가 보이면 미적용이다.
SELECT column_name
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'order_booking'
   AND column_name IN ('pending_booking_id', 'reservation_id');

ALTER TABLE order_booking RENAME COLUMN pending_booking_id TO reservation_id;
