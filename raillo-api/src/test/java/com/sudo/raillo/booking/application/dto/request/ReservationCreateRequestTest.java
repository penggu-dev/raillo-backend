package com.sudo.raillo.booking.application.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.support.annotation.ServiceTest;

import jakarta.validation.Validator;

@ServiceTest
class ReservationCreateRequestTest {

	@Autowired
	private Validator validator;

	record ValidationScenario(
		String description,
		ReservationCreateRequest request,
		String field,
		String expectedMessage
	) {
		@Override
		public String toString() {
			return description;
		}
	}

	static Stream<ValidationScenario> invalid_request_scenarios() {
		List<PassengerType> tenAdults = Collections.nCopies(10, PassengerType.ADULT);
		List<Long> tenSeats = LongStream.rangeClosed(1, 10).boxed().toList();

		return Stream.of(
			new ValidationScenario(
				"좌석 ID 목록에 null이 있는 경우",
				request(List.of(PassengerType.ADULT), Arrays.asList((Long)null)),
				"seatIds[0].<list element>",
				"좌석 ID에 빈 값이 있습니다"
			),
			new ValidationScenario(
				"승객 유형 목록에 null이 있는 경우",
				request(Arrays.asList((PassengerType)null), List.of(1L)),
				"passengerTypes[0].<list element>",
				"승객 유형에 빈 값이 있습니다"
			),
			new ValidationScenario(
				"좌석이 10개인 경우",
				request(tenAdults, tenSeats),
				"seatIds",
				"좌석은 최대 9개까지 선택할 수 있습니다"
			),
			new ValidationScenario(
				"승객이 10명인 경우",
				request(tenAdults, tenSeats),
				"passengerTypes",
				"승객 수는 최대 9명까지 가능합니다"
			),
			new ValidationScenario(
				"좌석 목록이 비어 있는 경우",
				request(List.of(PassengerType.ADULT), new ArrayList<>()),
				"seatIds",
				"좌석 정보는 필수입니다"
			)
		);
	}

	private static ReservationCreateRequest request(List<PassengerType> passengerTypes, List<Long> seatIds) {
		return new ReservationCreateRequest(1L, 1L, 2L, passengerTypes, seatIds);
	}

	@DisplayName("잘못된 예약 생성 요청은 해당 필드의 검증 메시지로 거부된다")
	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("invalid_request_scenarios")
	void rejects_invalid_request(ValidationScenario scenario) {
		// given
		ReservationCreateRequest request = scenario.request();

		// when
		var violations = validator.validate(request);

		// then
		assertThat(violations)
			.anySatisfy(violation -> {
				assertThat(violation.getPropertyPath().toString()).isEqualTo(scenario.field());
				assertThat(violation.getMessage()).isEqualTo(scenario.expectedMessage());
			});
	}

	@Test
	@DisplayName("승객과 좌석이 9개 이하이고 빈 값이 없으면 검증을 통과한다")
	void accepts_nine_passengers() {
		// given
		ReservationCreateRequest request = request(
			Collections.nCopies(ReservationCreateRequest.MAX_PASSENGERS, PassengerType.ADULT),
			LongStream.rangeClosed(1, ReservationCreateRequest.MAX_PASSENGERS).boxed().toList());

		// when
		var violations = validator.validate(request);

		// then
		assertThat(violations).isEmpty();
	}
}
