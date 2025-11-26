package com.sudo.raillo.train.infrastructure;

import static com.sudo.raillo.booking.domain.QBooking.*;
import static com.sudo.raillo.booking.domain.QSeatBooking.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.booking.application.dto.projection.QSeatInfoProjection;
import com.sudo.raillo.booking.application.dto.projection.SeatInfoProjection;
import com.sudo.raillo.booking.domain.QSeatBooking;
import com.sudo.raillo.train.application.dto.SeatBookingInfo;
import com.sudo.raillo.train.domain.QScheduleStop;
import com.sudo.raillo.train.domain.QSeat;
import com.sudo.raillo.train.domain.QStation;
import com.sudo.raillo.train.domain.QTrainCar;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class SeatBookingQueryRepository {

	private final JPAQueryFactory queryFactory;

	/**
	 * 여러 열차의 특정 구간에서 겹치는 예약 정보를 일괄 조회
	 * - 요청한 출발역~도착역 구간과 겹치는 예약 찾기
	 */
	public Map<Long, List<SeatBookingInfo>> findOverlappingBookingsBatch(List<Long> trainScheduleIds,
		Long departureStationId, Long arrivalStationId) {

		if (trainScheduleIds.isEmpty()) {
			return Map.of();
		}

		QSeatBooking seatBooking = QSeatBooking.seatBooking;
		QSeat seat = QSeat.seat;
		QTrainCar trainCar = QTrainCar.trainCar;
		QScheduleStop reservedDepartureStop = new QScheduleStop("reservedDepartureStop");
		QScheduleStop reservedArrivalStop = new QScheduleStop("reservedArrivalStop");
		QStation reservedDepartureStation = new QStation("reservedDepartureStation");
		QStation reservedArrivalStation = new QStation("reservedArrivalStation");
		QScheduleStop searchDepartureStop = new QScheduleStop("searchDepartureStop");
		QScheduleStop searchArrivalStop = new QScheduleStop("searchArrivalStop");

		List<Tuple> results = queryFactory
			.select(
				seatBooking.trainSchedule.id,
				seatBooking.seat.id,
				trainCar.carType,
				reservedDepartureStation.id,
				reservedArrivalStation.id
			)
			.from(seatBooking)
			.join(seat).on(seat.id.eq(seatBooking.seat.id))            // 좌석 정보
			.join(trainCar).on(trainCar.id.eq(seat.trainCar.id))           // 객차 정보 (객차 타입 판별 : 일반실/특실)
			.join(seatBooking.booking, booking)                // 예약 정보 (seatBooking 에만 좌석 정보 존재)
			.join(booking.departureStop, reservedDepartureStop)           // 출발역
			.join(booking.arrivalStop, reservedArrivalStop)               // 도착역
			.join(reservedDepartureStop.station, reservedDepartureStation)
			.join(reservedArrivalStop.station, reservedArrivalStation)
			.join(searchDepartureStop).on(
				searchDepartureStop.trainSchedule.id.in(trainScheduleIds)
					.and(searchDepartureStop.station.id.eq(departureStationId))
			)
			.join(searchArrivalStop).on(
				searchArrivalStop.trainSchedule.id.eq(seatBooking.trainSchedule.id)
					.and(searchArrivalStop.station.id.eq(arrivalStationId))
			)
			.where(
				seatBooking.trainSchedule.id.in(trainScheduleIds),             // 해당 trainScheduleId 모두 조회
				seatBooking.seat.isNotNull(),                                     // 실제 좌석 예약
				reservedArrivalStop.stopOrder.gt(searchDepartureStop.stopOrder)         // 구간 겹침 조건
					.and(reservedDepartureStop.stopOrder.lt(searchArrivalStop.stopOrder))
			)
			.fetch();

		// 결과를 trainScheduleId별로 그룹핑
		return results.stream()
			.collect(Collectors.groupingBy(
				tuple -> tuple.get(seatBooking.trainSchedule.id),
				Collectors.mapping(tuple -> new SeatBookingInfo(
					tuple.get(seatBooking.seat.id),
					tuple.get(trainCar.carType),
					tuple.get(reservedDepartureStation.id),
					tuple.get(reservedArrivalStation.id)
				), Collectors.toList())
			));
	}

	/**
	 * 예약 ID로 해당 예약의 좌석 정보와 승객 타입을 조회 (PaymentService용)
	 */
	public List<SeatInfoProjection> findSeatInfoByBookingId(Long bookingId) {
		return queryFactory
			.select(new QSeatInfoProjection(
				seatBooking.seat,
				seatBooking.passengerType
			))
			.from(seatBooking)
			.where(seatBooking.booking.id.eq(bookingId))
			.fetch();
	}
}
