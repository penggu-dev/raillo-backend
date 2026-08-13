package com.sudo.raillo.booking.infrastructure;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.booking.domain.QSeatOccupancy;
import com.sudo.raillo.train.domain.QScheduleStop;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class SeatOccupancyQueryRepository {

	private final JPAQueryFactory queryFactory;

	public Map<Long, Map<CarType, Integer>> countOccupiedSeatsBatch(
		List<Long> trainScheduleIds,
		Long departureStationId,
		Long arrivalStationId,
		LocalDateTime now
	) {
		if (trainScheduleIds.isEmpty()) {
			return Map.of();
		}

		QSeatOccupancy occupancy = QSeatOccupancy.seatOccupancy;
		QScheduleStop searchDepartureStop = new QScheduleStop("searchDepartureStop");
		QScheduleStop searchArrivalStop = new QScheduleStop("searchArrivalStop");
		NumberExpression<Long> occupiedSeatCount = occupancy.seat.id.countDistinct();

		List<Tuple> results = queryFactory
			.select(occupancy.trainSchedule.id, occupancy.carType, occupiedSeatCount)
			.from(occupancy)
			.join(searchDepartureStop).on(
				searchDepartureStop.trainSchedule.id.eq(occupancy.trainSchedule.id)
					.and(searchDepartureStop.station.id.eq(departureStationId))
			)
			.join(searchArrivalStop).on(
				searchArrivalStop.trainSchedule.id.eq(occupancy.trainSchedule.id)
					.and(searchArrivalStop.station.id.eq(arrivalStationId))
			)
			.where(
				occupancy.trainSchedule.id.in(trainScheduleIds),
				occupancy.expiresAt.gt(now),
				// 구간 겹침: sectionOrder ∈ [출발 stopOrder, 도착 stopOrder - 1]
				occupancy.sectionOrder.goe(searchDepartureStop.stopOrder),
				occupancy.sectionOrder.lt(searchArrivalStop.stopOrder)
			)
			.groupBy(occupancy.trainSchedule.id, occupancy.carType)
			.fetch();

		Map<Long, Map<CarType, Integer>> occupiedSeatsBySchedule = new HashMap<>();
		for (Tuple result : results) {
			Long trainScheduleId = result.get(occupancy.trainSchedule.id);
			CarType carType = result.get(occupancy.carType);
			Long count = result.get(occupiedSeatCount);

			occupiedSeatsBySchedule
				.computeIfAbsent(trainScheduleId, id -> new HashMap<>())
				.put(carType, count == null ? 0 : count.intValue());
		}
		return occupiedSeatsBySchedule;
	}

	/**
	 * 한 열차의 요청 구간에 대해 객차별 점유 좌석 수를 집계한다.
	 *
	 * <p>{@code trainCarId} 역정규화 덕에 seat/trainCar 조인이 필요 없다.</p>
	 *
	 * @param arrivalStopOrder 도착 stopOrder (구간은 이 값 미만까지)
	 * @return {trainCarId: 점유 좌석 수}
	 */
	public Map<Long, Integer> countOccupiedSeatsByTrainCar(
		Long trainScheduleId,
		int departureStopOrder,
		int arrivalStopOrder,
		LocalDateTime now
	) {
		QSeatOccupancy occupancy = QSeatOccupancy.seatOccupancy;
		NumberExpression<Long> occupiedSeatCount = occupancy.seat.id.countDistinct();

		List<Tuple> results = queryFactory
			.select(occupancy.trainCarId, occupiedSeatCount)
			.from(occupancy)
			.where(
				occupancy.trainSchedule.id.eq(trainScheduleId),
				occupancy.expiresAt.gt(now),
				occupancy.sectionOrder.goe(departureStopOrder),
				occupancy.sectionOrder.lt(arrivalStopOrder)
			)
			.groupBy(occupancy.trainCarId)
			.fetch();

		return results.stream()
			.collect(Collectors.toMap(
				result -> result.get(occupancy.trainCarId),
				result -> {
					Long count = result.get(occupiedSeatCount);
					return count == null ? 0 : count.intValue();
				}
			));
	}

	/**
	 * 특정 객차에서 요청 구간에 점유된 좌석 ID 목록을 조회한다. (좌석 상세 화면용)
	 */
	public Set<Long> findOccupiedSeatIds(
		Long trainScheduleId,
		Long trainCarId,
		int departureStopOrder,
		int arrivalStopOrder,
		LocalDateTime now
	) {
		QSeatOccupancy occupancy = QSeatOccupancy.seatOccupancy;

		return new HashSet<>(queryFactory
			.select(occupancy.seat.id)
			.distinct()
			.from(occupancy)
			.where(
				occupancy.trainSchedule.id.eq(trainScheduleId),
				occupancy.trainCarId.eq(trainCarId),
				occupancy.expiresAt.gt(now),
				occupancy.sectionOrder.goe(departureStopOrder),
				occupancy.sectionOrder.lt(arrivalStopOrder)
			)
			.fetch());
	}

	/**
	 * 요청 구간에서 지정한 좌석들 중 이미 점유된 좌석 ID를 조회한다. (예약 생성 전 사전 검증용)
	 *
	 * <p>최종 충돌 판정은 유니크 제약이 하므로 이 조회는 빠른 실패를 위한 보조 수단이다.</p>
	 */
	public Set<Long> findOccupiedSeatIdsAmong(
		Long trainScheduleId,
		List<Long> seatIds,
		int departureStopOrder,
		int arrivalStopOrder,
		LocalDateTime now
	) {
		if (seatIds.isEmpty()) {
			return Set.of();
		}

		QSeatOccupancy occupancy = QSeatOccupancy.seatOccupancy;

		return new HashSet<>(queryFactory
			.select(occupancy.seat.id)
			.distinct()
			.from(occupancy)
			.where(
				occupancy.trainSchedule.id.eq(trainScheduleId),
				occupancy.seat.id.in(seatIds),
				occupancy.expiresAt.gt(now),
				occupancy.sectionOrder.goe(departureStopOrder),
				occupancy.sectionOrder.lt(arrivalStopOrder)
			)
			.fetch());
	}
}
