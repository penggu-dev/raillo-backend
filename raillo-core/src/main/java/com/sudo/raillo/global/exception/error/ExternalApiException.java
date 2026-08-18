package com.sudo.raillo.global.exception.error;

import lombok.Getter;

@Getter
public class ExternalApiException extends RuntimeException {

	private final int httpStatus;
	private final String errorCode;
	private final String provider;
	private final String errorType;

	public ExternalApiException(int httpStatus, String errorCode, String errorMessage, String provider, String errorType) {
		super(errorMessage);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
		this.provider = provider;
		this.errorType = errorType;
	}
}
