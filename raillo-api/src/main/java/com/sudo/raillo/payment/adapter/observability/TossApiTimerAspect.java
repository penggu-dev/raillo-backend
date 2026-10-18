package com.sudo.raillo.payment.adapter.observability;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient;

import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.RequiredArgsConstructor;

/**
 * Toss API 호출을 감싸 응답 시간과 진행 중 요청을 관측한다.
 *
 * <ul>
 *   <li>{@code toss.api.duration} — 완료된 호출의 응답 시간 히스토그램. {@code operation}, {@code outcome}, {@code path} 태그로
 *       성공/실패 분포와 사용자·Worker 호출 경로를 분리한다. 4xx 즉시 거절과 timeout이 서로 오염되지 않고, 에러율도 파생 가능.</li>
 *   <li>{@code toss.api.active} — LongTaskTimer. 진행 중 요청의 개수와 age 분포를 {@code path}별로 실시간 노출한다.
 *       Toss 응답 지연·hang을 완료 데이터 없이도 즉시 감지할 수 있다.
 *       {@code toss_api_active_seconds_active_count}, {@code _duration_sum}, {@code _max} 시리즈로 스크레이핑된다.</li>
 * </ul>
 *
 * <p>{@code path}는 {@link TossPaymentClient#getCallPath()}에서 읽어 사용자/Worker 호출을 구분한다.</p>
 *
 * <p>Timer 인스턴스는 (operation, outcome, path) 조합당 한 번만 등록해 캐시한다.
 * {@link MeterRegistry#register}는 이름+태그가 같으면 기존 미터를 반환하므로, 매 호출마다 Builder를 새로 만드는 것은
 * 낭비이며, 조건부 설정이 도입될 때 첫 등록 이후의 변경이 조용히 무시되는 함정도 있다.</p>
 */
@Aspect
@Component
@RequiredArgsConstructor
public class TossApiTimerAspect {

	private final MeterRegistry meterRegistry;

	private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
	private final Map<String, LongTaskTimer> activeTimerCache = new ConcurrentHashMap<>();

	@Around("execution(* com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient.confirmPayment(..))")
	public Object timeConfirmPayment(ProceedingJoinPoint joinPoint) throws Throwable {
		return timeApiCall(joinPoint, "confirm");
	}

	@Around("execution(* com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient.cancelPayment(..))")
	public Object timeCancelPayment(ProceedingJoinPoint joinPoint) throws Throwable {
		return timeApiCall(joinPoint, "cancel");
	}

	@Around("execution(* com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient.queryPayment(..))")
	public Object timeQueryPayment(ProceedingJoinPoint joinPoint) throws Throwable {
		return timeApiCall(joinPoint, "query");
	}

	private Object timeApiCall(ProceedingJoinPoint joinPoint, String operation) throws Throwable {
		String callPath = ((TossPaymentClient)joinPoint.getTarget()).getCallPath();
		// 이중 try/finally 구조:
		//   외부 try — LongTaskTimer 활성 카운트 leak 방지
		//   내부 try — 완료 Timer 기록과 outcome 결정
		LongTaskTimer.Sample active = activeTimerFor(operation, callPath).start();
		try {
			Sample sample = Timer.start(meterRegistry);
			String outcome = "success";
			try {
				return joinPoint.proceed();
			} catch (Throwable t) {
				outcome = "error";
				throw t;
			} finally {
				sample.stop(timerFor(operation, outcome, callPath));
			}
		} finally {
			active.stop();
		}
	}

	private Timer timerFor(String operation, String outcome, String callPath) {
		return timerCache.computeIfAbsent(operation + "|" + outcome + "|" + callPath, k ->
			Timer.builder("toss.api.duration")
				.description("Toss API 호출 응답 시간")
				.tag("operation", operation)
				.tag("outcome", outcome)
				.tag("path", callPath)
				.publishPercentileHistogram(true)
				.register(meterRegistry));
	}

	private LongTaskTimer activeTimerFor(String operation, String callPath) {
		return activeTimerCache.computeIfAbsent(operation + "|" + callPath, k ->
			LongTaskTimer.builder("toss.api.active")
				.description("진행 중인 Toss API 호출의 개수와 경과 시간 분포")
				.tag("operation", operation)
				.tag("path", callPath)
				.publishPercentileHistogram(true)
				.minimumExpectedValue(Duration.ofMillis(100))
				.maximumExpectedValue(Duration.ofSeconds(30))
				.register(meterRegistry));
	}
}
