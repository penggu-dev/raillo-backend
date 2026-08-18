package com.sudo.raillo.global.redis.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sudo.raillo.common.response.ErrorResponse;

import io.jsonwebtoken.io.SerializationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class RedisExceptionHandler {

	@ExceptionHandler(RedisConnectionFailureException.class)
	public ResponseEntity<ErrorResponse> handleRedisConnectionFailure(RedisConnectionFailureException ex) {
		ErrorResponse errorResponse = ErrorResponse.of(RedisError.REDIS_CONNECT_FAIL);
		log.warn("Redis connection failure: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
	}

	@ExceptionHandler(SerializationException.class)
	public ResponseEntity<ErrorResponse> handleRedisSerializationException(Exception ex) {
		ErrorResponse errorResponse = ErrorResponse.of(RedisError.SERIALIZATION_FAIL);
		log.warn("Redis serialization failure: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
	}

	@ExceptionHandler(InvalidDataAccessApiUsageException.class)
	public ResponseEntity<ErrorResponse> handleRedisApiUsageException(InvalidDataAccessApiUsageException ex) {
		ErrorResponse errorResponse = ErrorResponse.of(RedisError.INVALID_DATA_ACCESS);
		log.warn("Redis API usage failure: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
	}

	@ExceptionHandler(RedisException.class)
	public ResponseEntity<ErrorResponse> handleRedisException(RedisException ex) {
		ErrorResponse errorResponse = ErrorResponse.of(ex.getErrorCode());
		log.warn("Redis exception occurred: {}", ex.getMessage());
		return ResponseEntity.status(ex.getErrorCode().getStatus()).body(errorResponse);
	}
}
