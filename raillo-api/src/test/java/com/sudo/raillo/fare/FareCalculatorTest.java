package com.sudo.raillo.fare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.train.cache.StationFareCacheValue;
import com.sudo.raillo.train.application.calculator.FareCalculator;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainError;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class FareCalculatorTest {

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private FareCalculator fareCalculator;

	@DisplayName("일반석 승객 유형별 할인율이 적용된 총 운임을 계산한다")
	@ParameterizedTest(name = "{index}. {0} = {2}원")
	@MethodSource("provideStandardSeatScenarios")
	void calculateTotalFare_standardSeat(
		String description,
		List<PassengerType> passengerTypes,
		BigDecimal expectedFare
	) {
		// given
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");
		trainScheduleTestHelper.createOrUpdateStationFare(
			seoul.getStationName(),
			busan.getStationName(),
			50000,
			100000
		);

		// when
		BigDecimal totalFare = fareCalculator.calculateTotalFare(
			seoul.getId(),
			busan.getId(),
			passengerTypes,
			CarType.STANDARD
		);

		// then
		assertThat(totalFare).isEqualByComparingTo(expectedFare);
	}

	@DisplayName("특실 승객 유형별 할인율이 적용된 총 운임을 계산한다")
	@ParameterizedTest(name = "{index}. {0} = {2}원")
	@MethodSource("provideFirstClassSeatScenarios")
	void calculateTotalFare_firstClassSeat(
		String description,
		List<PassengerType> passengerTypes,
		BigDecimal expectedFare
	) {
		// given
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station busan = trainScheduleTestHelper.getOrCreateStation("부산");
		trainScheduleTestHelper.createOrUpdateStationFare(
			seoul.getStationName(),
			busan.getStationName(),
			50000,
			100000
		);

		// when
		BigDecimal totalFare = fareCalculator.calculateTotalFare(
			seoul.getId(),
			busan.getId(),
			passengerTypes,
			CarType.FIRST_CLASS
		);

		// then
		assertThat(totalFare).isEqualByComparingTo(expectedFare);
	}

	@Test
	@DisplayName("존재하지 않는 구간 요금 조회 시 예외가 발생한다")
	void calculateTotalFare_fare_not_found() {
		// given
		Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
		Station daejeon = trainScheduleTestHelper.getOrCreateStation("대전");
		List<PassengerType> passengerTypes = List.of(PassengerType.ADULT);

		// when & then
		assertThatThrownBy(() -> fareCalculator.calculateTotalFare(
			seoul.getId(),
			daejeon.getId(),
			passengerTypes,
			CarType.STANDARD
		))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", TrainError.STATION_FARE_NOT_FOUND);
	}

	private static Stream<Arguments> provideStandardSeatScenarios() {
		return Stream.of(
			// 일반석 (기본 요금: 50,000원)
			Arguments.of(
				"어른 1명 (일반석)",
				List.of(PassengerType.ADULT),
				BigDecimal.valueOf(50000)
			),
			Arguments.of(
				"어린이 1명 (일반석, 40% 할인)",
				List.of(PassengerType.CHILD),
				BigDecimal.valueOf(30000)
			),
			Arguments.of(
				"유아 1명 (일반석, 75% 할인)",
				List.of(PassengerType.INFANT),
				BigDecimal.valueOf(12500)
			),
			Arguments.of(
				"경로 1명 (일반석, 30% 할인)",
				List.of(PassengerType.SENIOR),
				BigDecimal.valueOf(35000)
			),
			Arguments.of(
				"중증 장애인 1명 (일반석, 50% 할인)",
				List.of(PassengerType.DISABLED_HEAVY),
				BigDecimal.valueOf(25000)
			),
			Arguments.of(
				"경증 장애인 1명 (일반석, 30% 할인)",
				List.of(PassengerType.DISABLED_LIGHT),
				BigDecimal.valueOf(35000)
			),
			Arguments.of(
				"국가유공자 1명 (일반석, 50% 할인)",
				List.of(PassengerType.VETERAN),
				BigDecimal.valueOf(25000)
			),
			Arguments.of(
				"어른 + 어린이 (일반석)",
				List.of(PassengerType.ADULT, PassengerType.CHILD),
				BigDecimal.valueOf(80000) // 50000 + 30000
			)
		);
	}

	private static Stream<Arguments> provideFirstClassSeatScenarios() {
		return Stream.of(
			// 특실 (기본 요금: 100,000원)
			Arguments.of(
				"어른 1명 (특실)",
				List.of(PassengerType.ADULT),
				BigDecimal.valueOf(100000)
			),
			Arguments.of(
				"어린이 1명 (특실, 40% 할인)",
				List.of(PassengerType.CHILD),
				BigDecimal.valueOf(60000)
			),
			Arguments.of(
				"유아 1명 (특실, 75% 할인)",
				List.of(PassengerType.INFANT),
				BigDecimal.valueOf(25000)
			),
			Arguments.of(
				"경로 1명 (특실, 30% 할인)",
				List.of(PassengerType.SENIOR),
				BigDecimal.valueOf(70000)
			),
			Arguments.of(
				"중증 장애인 1명 (특실, 50% 할인)",
				List.of(PassengerType.DISABLED_HEAVY),
				BigDecimal.valueOf(50000)
			),
			Arguments.of(
				"경증 장애인 1명 (특실, 30% 할인)",
				List.of(PassengerType.DISABLED_LIGHT),
				BigDecimal.valueOf(70000)
			),
			Arguments.of(
				"국가유공자 1명 (특실, 50% 할인)",
				List.of(PassengerType.VETERAN),
				BigDecimal.valueOf(50000)
			),
			Arguments.of(
				"어른 + 경로 (특실)",
				List.of(PassengerType.ADULT, PassengerType.SENIOR),
				BigDecimal.valueOf(170000) // 100000 + 70000
			)
		);
	}

	@Nested
	@DisplayName("기준정보 캐시 운임 기반 계산")
	class CacheFare {

		private final StationFareCacheValue fare = new StationFareCacheValue(
			new BigDecimal("50000"), new BigDecimal("100000"));

		@Test
		@DisplayName("승객 유형 순서대로 할인이 적용된 좌석별 운임을 돌려준다")
		void calculate_fares_keeps_order() {
			// given
			List<PassengerType> passengerTypes = List.of(PassengerType.ADULT, PassengerType.CHILD, PassengerType.SENIOR);

			// when
			List<BigDecimal> fares = fareCalculator.calculateFares(fare, CarType.STANDARD, passengerTypes);

			// then
			assertThat(fares).hasSize(3);
			assertThat(fares.get(0)).isEqualByComparingTo("50000");
			assertThat(fares.get(1)).isEqualByComparingTo("30000");
			assertThat(fares.get(2)).isEqualByComparingTo("35000");
		}

		@Test
		@DisplayName("특실은 특실 운임을 기준으로 할인한다")
		void calculate_fare_first_class() {
			// given

			// when
			BigDecimal infant = fareCalculator.calculateFare(fare, CarType.FIRST_CLASS, PassengerType.INFANT);
			BigDecimal veteran = fareCalculator.calculateFare(fare, CarType.FIRST_CLASS, PassengerType.VETERAN);

			// then
			assertThat(infant).isEqualByComparingTo("25000");
			assertThat(veteran).isEqualByComparingTo("50000");
		}

		@Test
		@DisplayName("캐시 운임과 DB 운임으로 계산한 총액이 같다")
		void matches_database_calculation() {
			// given
			Station seoul = trainScheduleTestHelper.getOrCreateStation("서울");
			Station busan = trainScheduleTestHelper.getOrCreateStation("부산");
			trainScheduleTestHelper.createOrUpdateStationFare("서울", "부산", 50000, 100000);
			List<PassengerType> passengerTypes = List.of(PassengerType.ADULT, PassengerType.DISABLED_HEAVY);

			// when
			BigDecimal fromDatabase = fareCalculator.calculateTotalFare(seoul.getId(), busan.getId(), passengerTypes, CarType.STANDARD);
			BigDecimal fromCache = fareCalculator.calculateFares(fare, CarType.STANDARD, passengerTypes).stream()
				.reduce(BigDecimal.ZERO, BigDecimal::add);

			// then
			assertThat(fromCache).isEqualByComparingTo(fromDatabase);
		}
	}
}
