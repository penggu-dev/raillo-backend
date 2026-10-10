-- #298: payment_outbox의 type과 status를 ENUM에서 VARCHAR로 바꾼다.
--
-- #298이 PaymentOutboxType에 BOOKING_SEAT_RELEASE_REQUIRED를 추가한다. 두 컬럼은 MySQL 네이티브
-- ENUM이라 값 목록이 DDL에 박혀 있고, ddl-auto: update는 기존 컬럼 정의를 바꾸지 않는다. 적용하지 않으면
-- 기동은 되고 새 타입을 쓰는 첫 INSERT에서 Data truncated로 실패한다.
--
-- 엔티티에 @JdbcTypeCode(SqlTypes.VARCHAR)를 함께 넣었다. 매핑만 고치면 새로 만드는 스키마는 VARCHAR로
-- 생성되지만 이미 있는 컬럼은 그대로이므로, 기존 스키마에는 이 ALTER가 필요하다. #270이
-- payment_attempt.status에 쓴 것과 같은 방식이다.
--
-- ## 하위 호환이다
--
-- ENUM에서 VARCHAR로 넓히는 변경이라 기존 값이 문자열 그대로 유지되고 구 코드도 계속 동작한다.
-- 중단 창이 필요 없다. 배포 전 아무 때나 적용하면 된다. 다만 순서는 ALTER 먼저, 배포 나중이다.
-- 반대 순서면 새 타입을 쓰는 첫 삭제 요청이 실패한다.
--
-- ## dev와 로컬
--
-- 새로 만든 스키마는 이 ALTER가 필요 없다. 아래 확인 질의로 현재 타입을 보고 enum이면 적용한다.
--
-- ## 롤백
--
-- 되돌릴 필요가 없다. VARCHAR는 기존 값을 모두 담고 구 코드가 읽고 쓰는 데 지장이 없다.
-- 굳이 되돌린다면 BOOKING_SEAT_RELEASE_REQUIRED 행이 남아 있지 않은지 먼저 확인해야 한다.
--
--   SELECT COUNT(*) FROM payment_outbox WHERE type = 'BOOKING_SEAT_RELEASE_REQUIRED';
--
-- ## 적용 현황 (2026-10-11)
--
--   운영 적용 완료, dev 적용 완료
--   로컬 raillo-test-mysql의 raillo 스키마 적용 완료 (ENUM 출신이라 CHECK 제약이 남지 않음을 확인)
--   로컬 test 스키마(ssh 터널)는 신규 생성이라 불필요
--   raillo-mysql 컨테이너는 중지 상태로 미적용

-- 적용 여부 확인. DATA_TYPE이 enum이면 미적용이다.
SELECT column_name, column_type, data_type
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'payment_outbox'
   AND column_name IN ('type', 'status');

ALTER TABLE payment_outbox MODIFY type   VARCHAR(30) NOT NULL;
ALTER TABLE payment_outbox MODIFY status VARCHAR(20) NOT NULL;
