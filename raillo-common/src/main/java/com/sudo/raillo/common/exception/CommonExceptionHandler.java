package com.sudo.raillo.common.exception;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import com.sudo.raillo.common.response.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class CommonExceptionHandler {

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
		ErrorResponse errorResponse = ErrorResponse.of(GlobalError.FORBIDDEN_ACCESS, ex.getMessage());
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
		HttpMessageNotReadableException e, HttpServletRequest request) {

		log.warn("Request body missing or invalid: {}", e.getMessage());

		return ResponseEntity.badRequest().body(
			ErrorResponse.of(
				GlobalError.REQUEST_BODY_MISSING,
				Map.of(
					"path", "uri=" + request.getRequestURI(),
					"method", request.getMethod()
				)
			)
		);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
		List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
		ErrorResponse errorResponse = ErrorResponse.of(GlobalError.INVALID_REQUEST_BODY, fieldErrors);

		log.warn("Validation failed: {}", errorResponse.getDetails());
		return ResponseEntity.badRequest().body(errorResponse);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingServletRequestParameter(
		MissingServletRequestParameterException ex) {
		String detail = String.format("Required parameter '%s' is missing", ex.getParameterName());
		ErrorResponse errorResponse = ErrorResponse.of(GlobalError.MISSING_REQUEST_PARAM, detail);

		log.warn("Missing request parameter: {}", ex.getParameterName());
		return ResponseEntity.badRequest().body(errorResponse);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
		Map<String, String> errors = new HashMap<>();
		ex.getConstraintViolations().forEach(violation -> {
			String fieldName = violation.getPropertyPath().toString();
			String message = violation.getMessage();
			errors.put(fieldName, message);
		});

		ErrorResponse errorResponse = ErrorResponse.of(GlobalError.INVALID_REQUEST_PARAM, errors);
		log.warn("Constraint violation: {}", errors);
		return ResponseEntity.badRequest().body(errorResponse);
	}

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
		ErrorResponse errorResponse = ErrorResponse.of(ex.getErrorCode());

		logBusinessException(ex);
		return ResponseEntity.status(ex.getErrorCode().getStatus()).body(errorResponse);
	}

	@ExceptionHandler(ExternalApiException.class)
	public ResponseEntity<ErrorResponse> handleExternalApiException(ExternalApiException ex) {
		Map<String, Object> details = Map.of(
			"provider", ex.getProvider(),
			"type", ex.getErrorType()
		);

		ErrorResponse response = ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), details);

		logExternalApiException(ex);
		return ResponseEntity.status(ex.getHttpStatus()).body(response);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
		ErrorResponse errorResponse = ErrorResponse.of(GlobalError.INVALID_REQUEST_PARAM);
		log.warn("Invalid argument: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception ex, WebRequest request) {
		ErrorResponse errorResponse = ErrorResponse.of(
			GlobalError.INTERNAL_SERVER_ERROR,
			Map.of("path", request.getDescription(false))
		);

		log.error("Unexpected error occurred", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
	}

	private void logBusinessException(BusinessException ex) {
		if (ex.getErrorCode().getStatus().is5xxServerError()) {
			log.error("Business exception occurred", ex);
		} else {
			log.warn("Business exception occurred: {}", ex.getMessage());
		}
	}

	private void logExternalApiException(ExternalApiException ex) {
		log.warn("External API Error | Provider={} | ErrorType={} | HTTP={} | Code={} | Message={}",
			ex.getProvider(),
			ex.getErrorType(),
			ex.getHttpStatus(),
			ex.getErrorCode(),
			ex.getMessage()
		);
	}
}
