package com.sudo.raillo.global.success;

import org.springframework.http.HttpStatus;

public interface SuccessCode {

	HttpStatus getStatus();

	String getMessage();
}
