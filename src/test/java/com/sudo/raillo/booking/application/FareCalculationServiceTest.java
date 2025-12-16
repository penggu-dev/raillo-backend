package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.service.FareCalculationService;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class FareCalculationServiceTest {

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private FareCalculationService fareCalculationService;

	@DisplayName("운임 계산에 성공한다")
	@ParameterizedTest(name = "{index}. {1} 계산 결과 = {2}")
	@MethodSource("providePassengers")
	void calculateFare_success(
		List<PassengerType> passengerTypes,
		CarType carType,
		BigDecimal expectedFare
	) {
		// given
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");
		trainScheduleTestHelper.createOrUpdateStationFare(seoul.getStationName(), busan.getStationName(),
			50000, 100000);

		// when
		BigDecimal totalFare = fareCalculationService.calculateFare(seoul.getId(), busan.getId(), passengerTypes, carType);

		// then
		assertThat(totalFare).isEqualByComparingTo(expectedFare);
	}

	private static Stream<Arguments> providePassengers() {
		return Stream.of(
			// 어른 2명 + 어린이 1명
			Arguments.of(
				List.of(
					PassengerType.ADULT,
					PassengerType.ADULT,
					PassengerType.CHILD
				),
				CarType.STANDARD,
				BigDecimal.valueOf(130000)
			),
			// 어른 1명
			Arguments.of(
				List.of(
					PassengerType.ADULT
				),
				CarType.FIRST_CLASS,
				BigDecimal.valueOf(100000)
			)
		);
	}
}
