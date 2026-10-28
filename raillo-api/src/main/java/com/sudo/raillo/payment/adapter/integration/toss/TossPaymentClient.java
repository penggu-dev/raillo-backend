package com.sudo.raillo.payment.adapter.integration.toss;


import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.payment.adapter.observability.TossApiMetrics;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.exception.DeliveryPhase;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class TossPaymentClient {

	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final RestClient tossPaymentRestClient;
	private final ObjectMapper objectMapper;
	private final TossApiMetrics tossApiMetrics;

	/** 지표가 사용자 경로와 Worker 경로를 구분하는 이름. */
	@Getter
	private final String callPath;

	private final QueryRetryPolicy queryRetryPolicy;

	/**
	 * 토스페이먼츠 결제 승인 API 호출
	 *
	 * @param command 결제 승인 커맨드 (paymentKey, orderId, amount)
	 * @return Payment 객체 -> TossPaymentConfirmResponse 변환
	 * <ul>
	 * 	   <li>성공: 200 OK + Payment 객체</li>
	 *     <li>실패: 4xx, 5xx 에러</li>
	 * </ul>
	 */
	public TossPaymentConfirmResponse confirmPayment(PaymentConfirmCommand command) {
		log.info("토스 결제 승인 요청: paymentKey={}, orderId={}, amount={}",
			command.paymentKey(), command.orderId(), command.amount());

		try {
			TossPaymentConfirmResponse response = tossPaymentRestClient.post()
				.uri("/v1/payments/confirm")
				.body(command)
				.exchange((req, res) -> {
					if (res.getStatusCode().isError()) {
						handleErrorResponse(res, "confirm");
					}
					return res.bodyTo(TossPaymentConfirmResponse.class);
				});

			log.info("[TOSS] 결제 승인 성공: paymentKey={}, orderId={}, status={}",
				response.paymentKey(), response.orderId(), response.status());

			return response;

		} catch (TossPaymentException e) {
			throw e;
		} catch (Exception e) {
			throw deliveryFailure("confirm", e);
		}
	}

	/**
	 * 토스페이먼츠 결제 조회 API 호출. Toss 응답 유실로 PaymentAttempt가 IN_PROGRESS로 남은 상태에서
	 * 실제 상태를 확인해 로컬을 정정하는 데 쓴다.
	 *
	 * <p>재시도는 이 루프 한 곳에만 있다. 전송 계층은 {@code disableAutomaticRetries}로 꺼져 있다.
	 * 4xx는 확정이라 바로 던지고, 전송 실패와 5xx와 본문 읽기 실패는 {@link QueryRetryPolicy}의
	 * 횟수와 예산 안에서 다시 보낸다.</p>
	 */
	public TossPaymentQueryResponse queryPayment(String paymentKey) {
		log.info("[TOSS] 결제 조회 요청: paymentKey={}", paymentKey);

		long startedAt = System.nanoTime();
		RuntimeException lastFailure = null;
		String lastCause = "UNKNOWN";

		for (int attempt = 1; ; attempt++) {
			if (queryRetryPolicy.exhausted(attempt, elapsedMillis(startedAt))) {
				throw queryUncertain(lastCause, statusOf(lastCause), lastFailure, phaseOf(lastFailure));
			}
			try {
				TossPaymentQueryResponse response = exchangeQuery(paymentKey);
				log.info("[TOSS] 결제 조회 성공: paymentKey={}, status={}, method={}",
					response.paymentKey(), response.status(), response.method());
				return response;

			} catch (TossPaymentException e) {
				if (e.isDefinitiveFailure()) {
					throw e;
				}
				lastFailure = e;
				lastCause = "SERVER";
			} catch (QueryBodyReadException e) {
				lastFailure = e;
				lastCause = "BODY";
			} catch (RuntimeException e) {
				lastFailure = e;
				lastCause = isTimeout(e) ? "TIMEOUT" : "IO";
			}

			log.warn("[TOSS] 결제 조회 실패: paymentKey={}, attempt={}, cause={}",
				paymentKey, attempt, lastCause, lastFailure);

			if (queryRetryPolicy.exhausted(attempt + 1, elapsedMillis(startedAt))) {
				throw queryUncertain(lastCause, statusOf(lastCause), lastFailure, phaseOf(lastFailure));
			}
			if (!sleepBeforeRetry(queryRetryPolicy.backoffMillis(attempt))) {
				throw queryUncertain(lastCause, statusOf(lastCause), lastFailure, phaseOf(lastFailure));
			}
		}
	}

	private TossPaymentQueryResponse exchangeQuery(String paymentKey) {
		return tossPaymentRestClient.get()
			.uri("/v1/payments/{paymentKey}", paymentKey)
			.exchange((req, res) -> {
				if (res.getStatusCode().isError()) {
					handleErrorResponse(res, "query");
				}
				try {
					return res.bodyTo(TossPaymentQueryResponse.class);
				} catch (RuntimeException e) {
					throw new QueryBodyReadException(e);
				}
			});
	}

	private static long elapsedMillis(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000L;
	}

	/** 본문을 이미 받은 뒤의 실패는 502, 응답 대기 초과는 504로 둔다. 기존 분류와 같다. */
	private static HttpStatus statusOf(String cause) {
		return "TIMEOUT".equals(cause) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
	}

	private TossPaymentException queryUncertain(String cause, HttpStatus status, Throwable e, DeliveryPhase phase) {
		String errorCode = PaymentGateway.QUERY_UNCERTAIN_PREFIX + cause;
		log.error("[TOSS] 결제 조회 결과 불명: errorCode={}, phase={}", errorCode, phase, e);
		tossApiMetrics.incrementFailure("query", 0, errorCode, phase);
		return new TossPaymentException(status.value(), errorCode, "결제 조회 결과를 확인하지 못했습니다.", phase);
	}

	/** @return 기다린 뒤 다시 조회해도 되면 true, 인터럽트되면 false */
	private static boolean sleepBeforeRetry(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * HTTP 응답을 받지 못한 실패를 전송 단계로 가른 예외로 만든다. 지표도 같은 값으로 함께 올린다.
	 *
	 * <p>http_status는 0이다. 응답을 받지 못했다는 뜻이며 기존 지표 관례와 같다. 확정 여부 판정은
	 * 상태 코드가 아니라 전송 단계로 하므로 0이 확정 실패로 읽히지 않는다.</p>
	 */
	private TossPaymentException deliveryFailure(String operation, Throwable e) {
		DeliveryPhase phase = phaseOf(e);
		boolean notReached = phase == DeliveryPhase.NOT_REACHED;
		String errorCode = operation.toUpperCase() + (notReached ? "_NOT_SENT" : "_OUTCOME_UNKNOWN");
		String message = notReached
			? "결제 요청이 전송되지 않았습니다."
			: "결제 결과를 확인하지 못했습니다.";

		log.error("[TOSS] {} 실패: phase={}, errorCode={}", operation, phase, errorCode, e);
		tossApiMetrics.incrementFailure(operation, 0, errorCode, phase);
		return new TossPaymentException(0, errorCode, message, phase);
	}

	/**
	 * 요청이 게이트웨이에 도달했는지를 원인 체인에서 판정한다.
	 *
	 * <p>도달하지 않은 것이 확정인 타입만 열거하고 나머지는 {@code NO_RESPONSE}로 둔다. 잘못 분류했을 때의
	 * 비용이 대칭이 아니기 때문이다. 나간 요청을 {@code NOT_REACHED}로 보면 사용자가 재시도해 이중 청구가
	 * 되고, 나가지 않은 요청을 {@code NO_RESPONSE}로 보면 불필요한 조회 한 번으로 끝난다.</p>
	 *
	 * <p>{@link ConnectTimeoutException}은 {@link java.net.SocketTimeoutException}의 하위 타입이다.
	 * 조건에 {@code SocketTimeoutException}을 넣으면 검사 순서에 따라 연결 타임아웃이 응답 대기 초과로
	 * 분류되므로 넣지 않는다.</p>
	 */
	private static DeliveryPhase phaseOf(Throwable e) {
		if (e == null) {
			return DeliveryPhase.NO_RESPONSE;
		}
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConnectionRequestTimeoutException
				|| cause instanceof ConnectTimeoutException
				|| cause instanceof ConnectException
				|| cause instanceof UnknownHostException
				|| cause instanceof NoRouteToHostException) {
				return DeliveryPhase.NOT_REACHED;
			}
		}
		return DeliveryPhase.NO_RESPONSE;
	}

	/** 연결, 커넥션 획득, 읽기 timeout은 모두 InterruptedIOException 계열이다. */
	private static boolean isTimeout(Throwable e) {
		if (e == null) {
			return false;
		}
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof InterruptedIOException) {
				return true;
			}
		}
		return false;
	}

	/** 결제 조회 응답의 헤더는 받았지만 본문을 읽거나 해석하지 못했음을 표시한다. 조회 재시도 판단에만 쓴다. */
	private static final class QueryBodyReadException extends RuntimeException {

		private QueryBodyReadException(Throwable cause) {
			super(cause);
		}
	}

	/**
	 * 토스페이먼츠 결제 취소 API 호출
	 *
	 * @param paymentKey 결제 키
	 * @param request 취소 요청 (cancelReason 필수, cancelAmount는 부분 취소 시에만)
	 * @return Payment 객체 -> TossPaymentCancelResponse 변환
	 *
	 * <p>요청마다 새 {@code Idempotency-Key}를 보낸다. 토스는 같은 키로 온 요청에 캐시된 응답을 돌려주지만(키는 15일간 유효)
	 * 우리가 호출마다 새 키를 만들기 때문에 같은 취소를 다시 보내면 별개 요청으로 처리된다.</p>
	 */
	public TossPaymentCancelResponse cancelPayment(String paymentKey, TossPaymentCancelRequest request) {
		String idempotencyKey = UUID.randomUUID().toString();

		log.info("[TOSS] 결제 취소 요청: paymentKey={}, cancelReason={}, cancelAmount={}, idempotencyKey={}",
			paymentKey, request.cancelReason(), request.cancelAmount(), idempotencyKey);

		try {
			TossPaymentCancelResponse response = tossPaymentRestClient.post()
				.uri("/v1/payments/{paymentKey}/cancel", paymentKey)
				.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
				.body(request)
				.exchange((req, res) -> {
					if (res.getStatusCode().isError()) {
						handleErrorResponse(res, "cancel");
					}
					return res.bodyTo(TossPaymentCancelResponse.class);
				});

			log.info("[TOSS] 결제 취소 성공: paymentKey={}, status={}, balanceAmount={}, cancelCount={}",
				response.paymentKey(), response.status(), response.balanceAmount(), response.getCancelCount());

			return response;

		} catch (TossPaymentException e) {
			throw e;
		} catch (Exception e) {
			throw deliveryFailure("cancel", e);
		}
	}

	private void handleErrorResponse(ClientHttpResponse res, String operation) throws IOException {
		int statusCode = res.getStatusCode().value();

		byte[] rawBytes = res.getBody().readAllBytes();
		String raw = new String(rawBytes, StandardCharsets.UTF_8);

		log.info("[TOSS] {} 에러 응답: httpStatus={}, Content-Type={}, bytes={}, traceId={}",
			operation, statusCode,
			res.getHeaders().getContentType(),
			rawBytes.length,
			res.getHeaders().getFirst("x-tosspayments-trace-id"));

		boolean serverError = res.getStatusCode().is5xxServerError();

		if (rawBytes.length == 0) {
			String message = "토스 에러 응답 본문이 비어 있습니다. (httpStatus=" + statusCode + ")";
			logByStatus(serverError, "[TOSS] {} 실패 ({}): {}", operation, statusCode, message);
			throw fail(operation, statusCode, "EMPTY_ERROR_BODY", message);
		}

		TossErrorResponseV1 error;
		try {
			error = objectMapper.readValue(raw, TossErrorResponseV1.class);
		} catch (RuntimeException e) {
			String bodySnippet = truncateForLog(raw);
			String message = "토스 에러 응답 파싱 실패 (httpStatus=" + statusCode + ")";
			log.error("[TOSS] {} 실패 ({}): {} bodySnippet={}", operation, statusCode, message, bodySnippet, e);
			throw fail(operation, statusCode, "UNPARSABLE_ERROR_BODY", message + ", body=" + bodySnippet);
		}

		logByStatus(serverError, "[TOSS] {} 실패 ({}): httpStatus={}, code={}, message={}",
			operation, serverError ? "5xx" : "4xx", statusCode, error.code(), error.message());
		throw fail(operation, statusCode, error.code(), error.message());
	}

	/**
	 * 실패를 지표에 올리고 던질 예외를 만든다.
	 *
	 * <p>지표의 {@code (operation, httpStatus, code)}와 예외가 든 값이 어긋나면 대시보드와 로그가 서로 다른
	 * 말을 한다. 실패 경로가 셋이라 각자 올리면 한쪽만 바뀌기 쉬워 한 곳에서 같은 값으로 둘을 만든다.</p>
	 */
	private TossPaymentException fail(String operation, int statusCode, String code, String message) {
		tossApiMetrics.incrementFailure(operation, statusCode, code, DeliveryPhase.ANSWERED);
		return new TossPaymentException(statusCode, code, message);
	}

	/** 5xx는 토스 쪽 장애라 error, 4xx는 요청이 거절된 것이라 warn으로 남긴다. */
	private static void logByStatus(boolean serverError, String format, Object... args) {
		if (serverError) {
			log.error(format, args);
		} else {
			log.warn(format, args);
		}
	}

	private String truncateForLog(String raw) {
		String normalized = raw.replaceAll("\\s+", " ").trim();
		if (normalized.length() <= 500) {
			return normalized;
		}
		return normalized.substring(0, 500) + "...(truncated)";
	}
}
