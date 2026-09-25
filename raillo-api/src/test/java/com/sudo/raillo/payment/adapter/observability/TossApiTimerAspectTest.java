package com.sudo.raillo.payment.adapter.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentCancelRequest;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;

import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TossApiTimerAspectTest {

	private MeterRegistry meterRegistry;
	private TossPaymentClient stubClient;
	private TossPaymentClient proxy;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		TossApiTimerAspect aspect = new TossApiTimerAspect(meterRegistry);
		stubClient = mock(TossPaymentClient.class);

		AspectJProxyFactory factory = new AspectJProxyFactory(stubClient);
		factory.addAspect(aspect);
		proxy = factory.getProxy();
	}

	@Test
	@DisplayName("confirm 성공 시 toss.api.duration Timer가 outcome=success 태그로 기록된다")
	void confirmSuccess_recordsDurationTimerWithSuccessOutcome() {
		PaymentConfirmCommand command = sampleConfirmCommand();

		proxy.confirmPayment(command);

		Timer timer = meterRegistry.find("toss.api.duration")
			.tag("operation", "confirm")
			.tag("outcome", "success")
			.timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("confirm 실패 시 toss.api.duration Timer가 outcome=error 태그로 기록되고 예외는 그대로 전파된다")
	void confirmFailure_recordsDurationTimerWithErrorOutcomeAndPropagatesException() {
		PaymentConfirmCommand command = sampleConfirmCommand();
		given(stubClient.confirmPayment(any())).willThrow(new RuntimeException("boom"));

		assertThatThrownBy(() -> proxy.confirmPayment(command))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("boom");

		Timer timer = meterRegistry.find("toss.api.duration")
			.tag("operation", "confirm")
			.tag("outcome", "error")
			.timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("queryPayment도 aspect에 걸려 duration Timer가 기록된다")
	void queryPayment_isCoveredByAspect() {
		proxy.queryPayment("toss_pk_1");

		Timer timer = meterRegistry.find("toss.api.duration")
			.tag("operation", "query")
			.tag("outcome", "success")
			.timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("cancelPayment도 aspect에 걸려 duration Timer가 기록된다")
	void cancelPayment_isCoveredByAspect() {
		proxy.cancelPayment("toss_pk_1", new TossPaymentCancelRequest("고객 변심", null));

		Timer timer = meterRegistry.find("toss.api.duration")
			.tag("operation", "cancel")
			.tag("outcome", "success")
			.timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("동일 (operation, outcome) 조합은 Timer 인스턴스가 캐싱되어 여러 호출이 하나의 Timer에 누적된다")
	void sameOperationAndOutcome_reusesSameTimerInstance() {
		PaymentConfirmCommand command = sampleConfirmCommand();

		proxy.confirmPayment(command);
		proxy.confirmPayment(command);
		proxy.confirmPayment(command);

		Timer timer = meterRegistry.find("toss.api.duration")
			.tag("operation", "confirm")
			.tag("outcome", "success")
			.timer();
		assertThat(timer.count()).isEqualTo(3);
	}

	@Test
	@DisplayName("호출 도중 toss.api.active LongTaskTimer의 active count가 1이 되고, 반환 후 0으로 복귀한다")
	void longTaskTimer_incrementsActiveDuringCallAndResetsAfter() {
		PaymentConfirmCommand command = sampleConfirmCommand();
		// 호출 중 시점에 LongTaskTimer 상태를 검사한다.
		doAnswer(invocation -> {
			LongTaskTimer active = meterRegistry.find("toss.api.active")
				.tag("operation", "confirm")
				.longTaskTimer();
			assertThat(active).as("LongTaskTimer는 호출 중에 이미 등록되어 있어야 한다").isNotNull();
			assertThat(active.activeTasks()).as("호출 중 active count").isEqualTo(1);
			return null;
		}).when(stubClient).confirmPayment(any());

		proxy.confirmPayment(command);

		LongTaskTimer active = meterRegistry.find("toss.api.active")
			.tag("operation", "confirm")
			.longTaskTimer();
		assertThat(active).isNotNull();
		assertThat(active.activeTasks()).as("반환 후 active count").isZero();
	}

	@Test
	@DisplayName("예외 발생 시에도 LongTaskTimer가 반드시 stop 되어 active count가 0으로 복귀한다")
	void longTaskTimer_stopsEvenOnException() {
		PaymentConfirmCommand command = sampleConfirmCommand();
		given(stubClient.confirmPayment(any())).willThrow(new RuntimeException("boom"));

		assertThatThrownBy(() -> proxy.confirmPayment(command))
			.isInstanceOf(RuntimeException.class);

		LongTaskTimer active = meterRegistry.find("toss.api.active")
			.tag("operation", "confirm")
			.longTaskTimer();
		assertThat(active).isNotNull();
		assertThat(active.activeTasks()).isZero();
	}

	@Test
	@DisplayName("operation 태그가 다르면 LongTaskTimer는 별개 인스턴스로 등록된다")
	void differentOperations_registerSeparateLongTaskTimers() {
		proxy.confirmPayment(sampleConfirmCommand());
		proxy.queryPayment("toss_pk_1");

		LongTaskTimer confirmActive = meterRegistry.find("toss.api.active")
			.tag("operation", "confirm")
			.longTaskTimer();
		LongTaskTimer queryActive = meterRegistry.find("toss.api.active")
			.tag("operation", "query")
			.longTaskTimer();

		assertThat(confirmActive).isNotNull();
		assertThat(queryActive).isNotNull();
		assertThat(confirmActive).isNotSameAs(queryActive);
	}

	private PaymentConfirmCommand sampleConfirmCommand() {
		return new PaymentConfirmCommand("toss_pk_1", "ORDER_001", BigDecimal.valueOf(1000));
	}
}
