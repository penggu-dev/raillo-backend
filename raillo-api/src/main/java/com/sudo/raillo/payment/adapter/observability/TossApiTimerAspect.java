package com.sudo.raillo.payment.adapter.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.RequiredArgsConstructor;

@Aspect
@Component
@RequiredArgsConstructor
public class TossApiTimerAspect {

	private final MeterRegistry meterRegistry;
	private final TossApiMetrics tossApiMetrics;

	@Around("execution(* com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient.confirmPayment(..))")
	public Object timeConfirmPayment(ProceedingJoinPoint joinPoint) throws Throwable {
		return timeApiCall(joinPoint, "confirm");
	}

	@Around("execution(* com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient.cancelPayment(..))")
	public Object timeCancelPayment(ProceedingJoinPoint joinPoint) throws Throwable {
		return timeApiCall(joinPoint, "cancel");
	}

	private Object timeApiCall(ProceedingJoinPoint joinPoint, String operation) throws Throwable {
		tossApiMetrics.incrementInFlight(operation);
		Sample sample = Timer.start(meterRegistry);
		// outcome은 stop 시점에 결정된다. success/error를 분리해야 4xx 즉시 거절(빠름)과 timeout(느림)이
		// p99를 서로 오염시키지 않고, 에러율도 이 미터 하나에서 파생할 수 있다.
		String outcome = "success";
		try {
			return joinPoint.proceed();
		} catch (Throwable t) {
			outcome = "error";
			throw t;
		} finally {
			sample.stop(Timer.builder("toss.api.duration")
				.description("Toss API 호출 응답 시간")
				.tag("operation", operation)
				.tag("outcome", outcome)
				.publishPercentileHistogram(true)
				.register(meterRegistry));
			tossApiMetrics.decrementInFlight(operation);
		}
	}
}
