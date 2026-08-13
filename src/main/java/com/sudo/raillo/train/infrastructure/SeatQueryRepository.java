package com.sudo.raillo.train.infrastructure;

import static com.sudo.raillo.booking.domain.QSeatOccupancy.seatOccupancy;
import static com.sudo.raillo.train.domain.QSeat.seat;
import static com.sudo.raillo.train.domain.QTrainCar.trainCar;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.train.application.dto.TrainCarSeatInfo;
import com.sudo.raillo.train.application.dto.projection.QSeatProjection;
import com.sudo.raillo.train.application.dto.projection.SeatProjection;
import com.sudo.raillo.train.domain.QScheduleStop;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SeatQueryRepository {

	private final JPAQueryFactory queryFactory;

	/**
	 * 특정 객차의 모든 좌석 상세 정보 및 점유 상태 조회
	 *
	 * <p>점유 여부는 {@code seat_occupancy} 에 요청 구간과 겹치는 유효한 행이 있는지로 판단한다.
	 * 예약(HELD)과 확정 예매(CONFIRMED)가 같은 테이블에 있으므로 판정이 한 번에 끝난다.</p>
	 */
	public TrainCarSeatInfo findTrainCarSeatDetail(
		Long trainCarId,
		Long trainScheduleId,
		Long departureStationId,
		Long arrivalStationId
	) {
		// 1. 객차 기본 정보 + 검색 구간 stopOrder 조회
		QScheduleStop carInfoDepartureStop = new QScheduleStop("carInfoDepartureStop");
		QScheduleStop carInfoArrivalStop = new QScheduleStop("carInfoArrivalStop");

		Tuple carInfo = queryFactory.select(
				trainCar.carNumber,
				trainCar.carType,
				trainCar.seatArrangement,
				trainCar.totalSeats,
				trainCar.seatRowCount,
				carInfoDepartureStop.stopOrder,
				carInfoArrivalStop.stopOrder)
			.from(trainCar)
			.leftJoin(carInfoDepartureStop).on(
				carInfoDepartureStop.trainSchedule.id.eq(trainScheduleId)
					.and(carInfoDepartureStop.station.id.eq(departureStationId))
			)
			.leftJoin(carInfoArrivalStop).on(
				carInfoArrivalStop.trainSchedule.id.eq(trainScheduleId)
					.and(carInfoArrivalStop.station.id.eq(arrivalStationId))
			)
			.where(trainCar.id.eq(trainCarId))
			.fetchOne();

		// seatRowCount 로 middleRow 계산
		Integer seatRowCount = carInfo.get(trainCar.seatRowCount);
		int middleRow = (seatRowCount != null) ? (seatRowCount + 1) / 2 : 8; // 중간 지점 계산

		Integer departureStopOrder = carInfo.get(carInfoDepartureStop.stopOrder);
		Integer arrivalStopOrder = carInfo.get(carInfoArrivalStop.stopOrder);

		// 2. 객차 내 모든 좌석 상세 정보 조회 (점유 상태 포함)
		List<SeatProjection> seatProjections = queryFactory.select(
				new QSeatProjection(seat.id,
					seat.seatRow.stringValue().concat(seat.seatColumn),
					seat.seatType,
					// directionCode
					new CaseBuilder().when(seat.seatRow.loe(middleRow)) // 중간 이하 : 순방향
						.then("009")  // 순방향
						.otherwise("010"), // 역방향
					// isBooked
					occupiedExpression(trainScheduleId, departureStopOrder, arrivalStopOrder),
					// specialMessage
					new CaseBuilder().when(seat.seatRow.between(middleRow, middleRow + 1))
						.then(new CaseBuilder().when(seat.seatRow.eq(middleRow))
							.then("KTX 4인동반석 순방향 좌석 입니다. 맞은편 좌석에 다른 승객이 승차할 수 있습니다.")
							.when(seat.seatRow.eq(middleRow + 1))
							.then("KTX 4인동반석 역방향 좌석 입니다. 맞은편 좌석에 다른 승객이 승차할 수 있습니다.")
							.otherwise(""))
						.otherwise("")))
			.from(seat)
			.where(seat.trainCar.id.eq(trainCarId))
			.orderBy(seat.seatRow.asc(), seat.seatColumn.asc())
			.fetch();

		// 3. 잔여 좌석 수 계산
		long remainingSeats = seatProjections.stream()
			.mapToLong(projection -> projection.isAvailable() ? 1 : 0)
			.sum();

		return new TrainCarSeatInfo(
			String.valueOf(carInfo.get(trainCar.carNumber)),
			carInfo.get(trainCar.carType),
			carInfo.get(trainCar.seatArrangement),
			Optional.ofNullable(carInfo.get(trainCar.totalSeats)).orElse(0),
			(int)remainingSeats,
			departureStopOrder,
			arrivalStopOrder,
			seatProjections
		);
	}

	/**
	 * 좌석이 요청 구간에서 점유되어 있는지 판단하는 표현식
	 *
	 * <p>정차역을 찾지 못해 stopOrder가 없으면 판단할 수 없으므로 미점유로 둔다.
	 * 이 경우 호출하는 Service가 {@code SCHEDULE_STOP_NOT_FOUND} 로 처리한다.</p>
	 */
	private Expression<Boolean> occupiedExpression(
		Long trainScheduleId,
		Integer departureStopOrder,
		Integer arrivalStopOrder
	) {
		if (departureStopOrder == null || arrivalStopOrder == null) {
			return Expressions.constant(false);
		}

		return JPAExpressions.selectOne()
			.from(seatOccupancy)
			.where(
				seatOccupancy.seat.id.eq(seat.id),
				seatOccupancy.trainSchedule.id.eq(trainScheduleId),
				seatOccupancy.expiresAt.gt(LocalDateTime.now()),
				seatOccupancy.sectionOrder.goe(departureStopOrder),
				seatOccupancy.sectionOrder.lt(arrivalStopOrder)
			)
			.exists();
	}
}
