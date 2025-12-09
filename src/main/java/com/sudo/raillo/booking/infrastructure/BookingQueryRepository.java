package com.sudo.raillo.booking.infrastructure;

import static com.sudo.raillo.booking.domain.QBooking.*;
import static com.sudo.raillo.booking.domain.QSeatBooking.*;
import static com.sudo.raillo.train.domain.QSeat.*;
import static com.sudo.raillo.train.domain.QTrain.*;
import static com.sudo.raillo.train.domain.QTrainCar.*;
import static com.sudo.raillo.train.domain.QTrainSchedule.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.projection.QBookingProjection;
import com.sudo.raillo.booking.application.dto.projection.QSeatBookingProjection;
import com.sudo.raillo.booking.application.dto.projection.BookingProjection;
import com.sudo.raillo.booking.application.dto.projection.SeatBookingProjection;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.train.domain.QScheduleStop;
import com.sudo.raillo.train.domain.QStation;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class BookingQueryRepository {

	private final JPAQueryFactory queryFactory;

	public List<BookingInfo> findBookingDetail(Long memberId) {
		return findBookingDetail(memberId, List.of());
	}

	public List<BookingInfo> findBookingDetail(Long memberId, List<Long> bookingIds) {
		QScheduleStop departureStop = new QScheduleStop("departureStop");
		QScheduleStop arrivalStop = new QScheduleStop("arrivalStop");
		QStation departureStation = new QStation("departureStation");
		QStation arrivalStation = new QStation("arrivalStation");

		// 예약 조회 쿼리
		JPAQuery<BookingProjection> query = queryFactory
			.select(new QBookingProjection(
				booking.id,
				booking.bookingCode,
				train.trainNumber,
				train.trainName,
				departureStation.stationName,
				arrivalStation.stationName,
				departureStop.departureTime,
				arrivalStop.arrivalTime,
				trainSchedule.operationDate
			))
			.from(booking)
			.join(booking.trainSchedule, trainSchedule)
			.join(booking.departureStop, departureStop)
			.join(booking.arrivalStop, arrivalStop)
			.join(trainSchedule.train, train)
			.join(departureStop.station, departureStation)
			.join(arrivalStop.station, arrivalStation)
			.where(
				booking.member.id.eq(memberId),
				booking.bookingStatus.eq(BookingStatus.BOOKED),
				arrivalStop.station.id.eq(arrivalStation.id),
				departureStop.station.id.eq(departureStation.id),
				departureStop.stopOrder.lt(arrivalStop.stopOrder)
			);
		if (bookingIds != null && !bookingIds.isEmpty()) {
			query.where(booking.id.in(bookingIds));
		}

		// 예약 조회
		List<BookingProjection> bookings = query.fetch();

		// 예약 좌석 조회
		Map<Long, List<SeatBookingProjection>> seats = queryFactory
			.select(new QSeatBookingProjection(
				seatBooking.id,
				seatBooking.booking.id,
				seatBooking.passengerType,
				trainCar.carNumber,
				trainCar.carType,
				seat.seatRow.stringValue().concat(seat.seatColumn)
			))
			.from(seatBooking)
			.leftJoin(seatBooking.seat, seat)
			.leftJoin(seat.trainCar, trainCar)
			.where(seatBooking.booking.id.in(
				bookings.stream()
					.map(BookingProjection::getBookingId)
					.toList()
			))
			.fetch()
			.stream()
			.collect(Collectors.groupingBy(SeatBookingProjection::getBookingId));

		return bookings.stream()
			.map(booking -> BookingInfo.of(
				booking,
				seats.get(booking.getBookingId())
			)).toList();
	}
}
