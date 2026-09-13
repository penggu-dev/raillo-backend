package com.sudo.raillo.train.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.domain.type.SeatType;
import com.sudo.raillo.train.domain.type.TrainType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrainBatchFactoryTest {

	@DisplayName("스케줄 템플릿을 생성하면 정차역 템플릿의 양방향 연관관계가 설정된다")
	@Test
	void creates_schedule_template_with_bidirectional_stops() {
		// given
		Train train = Train.create(1, TrainType.KTX, "KTX", 20);
		Station departureStation = Station.create("서울");
		Station arrivalStation = Station.create("부산");
		ScheduleStopTemplate departureStop = ScheduleStopTemplate.create(
			0, null, LocalTime.of(6, 0), departureStation);
		ScheduleStopTemplate arrivalStop = ScheduleStopTemplate.create(
			1, LocalTime.of(8, 30), null, arrivalStation);

		// when
		TrainScheduleTemplate template = TrainScheduleTemplate.create(
			"KTX 001 경부선",
			0b1111111,
			LocalTime.of(6, 0),
			LocalTime.of(8, 30),
			train,
			departureStation,
			arrivalStation,
			List.of(departureStop, arrivalStop)
		);

		// then
		assertThat(template.getScheduleStops()).containsExactly(departureStop, arrivalStop);
		assertThat(departureStop.getTrainSchedule()).isSameAs(template);
		assertThat(arrivalStop.getTrainSchedule()).isSameAs(template);
	}

	@DisplayName("템플릿으로 운행 스케줄과 정차역을 생성하면 도메인 기본값과 원본 정보가 유지된다")
	@Test
	void creates_daily_schedule_and_stop_from_template() {
		// given
		Train train = Train.create(1, TrainType.KTX, "KTX", 20);
		Station departureStation = Station.create("서울");
		Station arrivalStation = Station.create("부산");
		ScheduleStopTemplate stopTemplate = ScheduleStopTemplate.create(
			0, null, LocalTime.of(6, 0), departureStation);
		TrainScheduleTemplate template = TrainScheduleTemplate.create(
			"KTX 001 경부선",
			0b1111111,
			LocalTime.of(6, 0),
			LocalTime.of(8, 30),
			train,
			departureStation,
			arrivalStation,
			List.of(stopTemplate)
		);
		LocalDate operationDate = LocalDate.of(2026, 9, 13);

		// when
		TrainSchedule schedule = TrainSchedule.create(operationDate, template);
		ScheduleStop stop = ScheduleStop.create(stopTemplate, schedule);

		// then
		assertThat(schedule.getScheduleName()).isEqualTo(template.getScheduleName());
		assertThat(schedule.getOperationDate()).isEqualTo(operationDate);
		assertThat(schedule.getOperationStatus()).isEqualTo(OperationStatus.ACTIVE);
		assertThat(schedule.getDelayMinutes()).isZero();
		assertThat(stop.getTrainSchedule()).isSameAs(schedule);
		assertThat(stop.getStation()).isSameAs(departureStation);
	}

	@DisplayName("열차와 객차와 좌석과 운임을 원시값과 도메인 타입만으로 생성한다")
	@Test
	void creates_train_components_from_domain_values() {
		// given
		Station departureStation = Station.create("서울");
		Station arrivalStation = Station.create("부산");

		// when
		Train train = Train.create(1, TrainType.KTX, "KTX", 20);
		TrainCar trainCar = TrainCar.create(1, CarType.STANDARD, 14, 56, "2+2", train);
		Seat seat = Seat.create(1, "A", SeatType.WINDOW, trainCar);
		StationFare fare = StationFare.create(
			departureStation, arrivalStation, new BigDecimal("59800"), new BigDecimal("83700"));

		// then
		assertThat(train.getTotalCars()).isEqualTo(20);
		assertThat(trainCar.getTrain()).isSameAs(train);
		assertThat(seat.getTrainCar()).isSameAs(trainCar);
		assertThat(seat.getIsAccessible()).isEqualTo("Y");
		assertThat(seat.getIsAvailable()).isEqualTo("Y");
		assertThat(fare.getStandardFare()).isEqualByComparingTo("59800");
		assertThat(fare.getFirstClassFare()).isEqualByComparingTo("83700");
	}
}
