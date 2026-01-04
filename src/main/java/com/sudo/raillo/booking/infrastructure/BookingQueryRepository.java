package com.sudo.raillo.booking.infrastructure;

import static com.sudo.raillo.booking.domain.QBooking.*;
import static com.sudo.raillo.booking.domain.QTicket.*;
import static com.sudo.raillo.train.domain.QSeat.*;
import static com.sudo.raillo.train.domain.QTrain.*;
import static com.sudo.raillo.train.domain.QTrainCar.*;
import static com.sudo.raillo.train.domain.QTrainSchedule.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.BookingTimeFilter;
import com.sudo.raillo.booking.application.dto.projection.BookingProjection;
import com.sudo.raillo.booking.application.dto.projection.QBookingProjection;
import com.sudo.raillo.booking.application.dto.projection.QTicketProjection;
import com.sudo.raillo.booking.application.dto.projection.TicketProjection;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.train.domain.QScheduleStop;
import com.sudo.raillo.train.domain.QStation;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class BookingQueryRepository {

	private final JPAQueryFactory queryFactory;

	/**
	 * 승차권 상세 조회 (단건 또는 특정 ID 목록)
	 */
	public List<BookingInfo> findBookingDetail(Long memberId, List<Long> bookingIds) {
		return findBookingDetail(memberId, bookingIds, BookingTimeFilter.ALL);
	}

	/**
	 * 승차권 목록 조회 (시간 필터 적용)
	 */
	public List<BookingInfo> findBookingDetail(Long memberId, BookingTimeFilter timeFilter) {
		return findBookingDetail(memberId, List.of(), timeFilter);
	}

	public List<BookingInfo> findBookingDetail(Long memberId, List<Long> bookingIds, BookingTimeFilter timeFilter) {
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
				arrivalStop.station.id.eq(arrivalStation.id),
				departureStop.station.id.eq(departureStation.id),
				departureStop.stopOrder.lt(arrivalStop.stopOrder),
				buildFilterCondition(timeFilter, departureStop)
			);

		if (bookingIds != null && !bookingIds.isEmpty()) {
			query.where(booking.id.in(bookingIds));
		}

		// 예약 조회
		List<BookingProjection> bookings = query.fetch();

		// Ticket 기반 좌석 정보 조회
		Map<Long, List<TicketProjection>> tickets = queryFactory
			.select(new QTicketProjection(
				ticket.id,
				ticket.booking.id,
				ticket.passengerType,
				trainCar.carNumber,
				trainCar.carType,
				seat.seatRow.stringValue().concat(seat.seatColumn)
			))
			.from(ticket)
			.leftJoin(ticket.seat, seat)
			.leftJoin(seat.trainCar, trainCar)
			.where(ticket.booking.id.in(
				bookings.stream()
					.map(BookingProjection::getBookingId)
					.toList()
			))
			.fetch()
			.stream()
			.collect(Collectors.groupingBy(TicketProjection::getBookingId));

		return bookings.stream()
			.map(booking -> BookingInfo.of(booking, tickets.get(booking.getBookingId())))
			.toList();
	}

	/**
	 * 필터 조건 생성

	 * <li>UPCOMING: BOOKED 상태 + 현재 시간 이후 출발 (승차권 조회)</li>
	 * <li>HISTORY: BOOKED 또는 CANCELLED + 현재 시간 이전 출발 (구입 이력)</li>
	 * <li>ALL: BOOKED 상태만 (시간 무관)</li>
	 */
	private BooleanBuilder buildFilterCondition(BookingTimeFilter timeFilter, QScheduleStop departureStop) {
		BooleanBuilder builder = new BooleanBuilder();
		LocalDate today = LocalDate.now();
		LocalTime now = LocalTime.now();

		switch (timeFilter) {
			case UPCOMING -> {
				builder.and(booking.bookingStatus.eq(BookingStatus.BOOKED));
				builder.and(
					trainSchedule.operationDate.gt(today)
						.or(trainSchedule.operationDate.eq(today)
							.and(departureStop.departureTime.gt(now)))
				);
			}
			case HISTORY -> {
				builder.and(booking.bookingStatus.in(BookingStatus.BOOKED, BookingStatus.CANCELLED));
				builder.and(
					trainSchedule.operationDate.lt(today)
						.or(trainSchedule.operationDate.eq(today)
							.and(departureStop.departureTime.loe(now)))
				);
			}
			case ALL -> builder.and(booking.bookingStatus.eq(BookingStatus.BOOKED));
		}

		return builder;
	}
}
