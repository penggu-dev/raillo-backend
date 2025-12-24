package com.sudo.raillo.support.fixture;

import java.time.LocalDate;
import java.time.LocalTime;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.TrainType;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class BookingFixture {

	private Member member;
	private TrainSchedule trainSchedule = createDefaultTrainSchedule();
	private ScheduleStop departureStop = createDefaultScheduleStop(0, null, LocalTime.of(9, 0), trainSchedule, "서울");
	private ScheduleStop arrivalStop = createDefaultScheduleStop(1, LocalTime.of(12, 0), null, trainSchedule, "부산");

	public static Booking create(Member member) {
		return builder()
			.withMember(member)
			.build();
	}

	// builder method
	public static BookingFixture builder() {
		return new BookingFixture();
	}

	public Booking build() {
		return Booking.create(member, trainSchedule, departureStop, arrivalStop);
	}

	public BookingFixture withMember(Member member) {
		this.member = member;
		return this;
	}

	public BookingFixture withTrainSchedule(TrainSchedule trainSchedule) {
		this.trainSchedule = trainSchedule;
		return this;
	}

	public BookingFixture withDepartureStop(ScheduleStop departureStop) {
		this.departureStop = departureStop;
		return this;
	}

	public BookingFixture withArrivalStop(ScheduleStop arrivalStop) {
		this.arrivalStop = arrivalStop;
		return this;
	}

	private static TrainSchedule createDefaultTrainSchedule() {
		Station departureStation = StationFixture.create("서울");
		Station arrivalStation = StationFixture.create("부산");
		Train train = TrainFixture.create(1, TrainType.KTX, "KTX 001", 10);

		return TrainScheduleFixture.create(
			"KTX 001",
			LocalDate.now(),
			LocalTime.of(9, 0),
			LocalTime.of(12, 0),
			OperationStatus.ACTIVE,
			train,
			departureStation,
			arrivalStation
		);
	}

	private static ScheduleStop createDefaultScheduleStop(
		int stopOrder,
		LocalTime arrivalTime,
		LocalTime departureTime,
		TrainSchedule trainSchedule,
		String stationName
	) {
		Station station = StationFixture.create(stationName);
		return ScheduleStopFixture.create(stopOrder, arrivalTime, departureTime, trainSchedule, station);
	}
}
