package com.sudo.raillo.global.redis.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.exception.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RedisError implements ErrorCode {

	REDIS_CONNECT_FAIL("Redis 서버 연결에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "R_001"),
	SERIALIZATION_FAIL("Json data 직렬화/역직렬화에 실패하였습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "R_002"),
	INVALID_DATA_ACCESS("잘못된 Redis API 사용입니다.", HttpStatus.BAD_REQUEST, "R_003"),
	SCAN_OPERATION_FAIL("키 스캔중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "R_004"),
	MGET_OPERATION_FAIL("여러 키 조회중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "R_005");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
