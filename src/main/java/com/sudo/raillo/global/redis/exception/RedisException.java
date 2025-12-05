package com.sudo.raillo.global.redis.exception;

import com.sudo.raillo.global.exception.error.ErrorCode;

import lombok.Getter;

@Getter
public class RedisException extends RuntimeException {

	private final ErrorCode errorCode;

	public RedisException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public RedisException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.getMessage(), cause);
		this.errorCode = errorCode;
	}

	public RedisException(ErrorCode errorCode, String customMessage) {
		super(customMessage);
		this.errorCode = errorCode;
	}
}
