-- #270: payment_attempt.status를 MySQL ENUM에서 VARCHAR로 바꾼다.
-- ddl-auto: update는 기존 컬럼 정의를 바꾸지 않으므로 개발 DB와 운영 DB에 배포 전 한 번 적용한다.
-- 기존 값(IN_PROGRESS, SUCCEEDED, FAILED)은 문자열 그대로 유지된다.
ALTER TABLE payment_attempt MODIFY status VARCHAR(20) NOT NULL;
