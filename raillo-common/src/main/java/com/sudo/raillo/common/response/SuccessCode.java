package com.sudo.raillo.common.response;

import org.springframework.http.HttpStatus;

public interface SuccessCode {

	HttpStatus getStatus();

	String getMessage();
}
