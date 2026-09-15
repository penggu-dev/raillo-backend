package com.sudo.raillo.global.response;

import org.springframework.http.HttpStatus;

public interface SuccessCode {

	HttpStatus getStatus();

	String getMessage();
}
