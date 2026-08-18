package com.sudo.raillo.global.exception.error;

import lombok.Getter;

@Getter
public class DomainException extends RuntimeException {

	private final ErrorCode errorCode;

	public DomainException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public DomainException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.getMessage(), cause);
		this.errorCode = errorCode;
	}

	public DomainException(ErrorCode errorCode, String customMessage) {
		super(customMessage);
		this.errorCode = errorCode;
	}
}
