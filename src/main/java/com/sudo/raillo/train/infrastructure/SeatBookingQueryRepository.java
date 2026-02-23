package com.sudo.raillo.train.infrastructure;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.booking.domain.QSeatBooking;
import com.sudo.raillo.train.application.dto.SeatBookingInfo;
import com.sudo.raillo.train.domain.QScheduleStop;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SeatBookingQueryRepository {

	private final JPAQueryFactory queryFactory;

	/**
	 * 여러 열차의 특정 구간에서 겹치는 예매 정보를 일괄 조회
	 * - 요청한 출발역~도착역 구간과 겹치는 예매 찾기
	 */
	public Map<Long, List<SeatBookingInfo>> findOverlappingBookingsBatch(
		List<Long> trainScheduleIds,
		Long departureStationId, Long arrivalStationId
	) {
		if (trainScheduleIds.isEmpty()) {
			return Map.of();
		}

		QSeatBooking seatBooking = QSeatBooking.seatBooking;
		QScheduleStop searchDepartureStop = new QScheduleStop("searchDepartureStop");
		QScheduleStop searchArrivalStop = new QScheduleStop("searchArrivalStop");

		List<Tuple> results = queryFactory
			.select(
				seatBooking.trainSchedule.id,
				seatBooking.seat.id,
				seatBooking.carType,
				seatBooking.departureStationId,
				seatBooking.arrivalStationId
			)
			.from(seatBooking)
			.join(searchDepartureStop).on(
				searchDepartureStop.trainSchedule.id.eq(seatBooking.trainSchedule.id)
					.and(searchDepartureStop.station.id.eq(departureStationId))
			)
			.join(searchArrivalStop).on(
				searchArrivalStop.trainSchedule.id.eq(seatBooking.trainSchedule.id)
					.and(searchArrivalStop.station.id.eq(arrivalStationId))
			)
			.where(
				seatBooking.trainSchedule.id.in(trainScheduleIds),
				seatBooking.seat.isNotNull(),
				seatBooking.arrivalStopOrder.gt(searchDepartureStop.stopOrder)
					.and(seatBooking.departureStopOrder.lt(searchArrivalStop.stopOrder))
			)
			.fetch();

		// 결과를 trainScheduleId별로 그룹핑
		return results.stream()
			.collect(Collectors.groupingBy(
				tuple -> tuple.get(seatBooking.trainSchedule.id),
				Collectors.mapping(tuple -> new SeatBookingInfo(
					tuple.get(seatBooking.seat.id),
					tuple.get(seatBooking.carType),
					tuple.get(seatBooking.departureStationId),
					tuple.get(seatBooking.arrivalStationId)
				), Collectors.toList())
			));
	}
}
