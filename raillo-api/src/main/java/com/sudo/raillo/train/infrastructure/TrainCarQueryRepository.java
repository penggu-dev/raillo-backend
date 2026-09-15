package com.sudo.raillo.train.infrastructure;

import static com.sudo.raillo.booking.domain.QBooking.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.booking.domain.QSeatBooking;
import com.sudo.raillo.train.application.dto.projection.QTrainCarProjection;
import com.sudo.raillo.train.application.dto.projection.TrainCarProjection;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.domain.QScheduleStop;
import com.sudo.raillo.train.domain.QSeat;
import com.sudo.raillo.train.domain.QTrain;
import com.sudo.raillo.train.domain.QTrainCar;
import com.sudo.raillo.train.domain.QTrainSchedule;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TrainCarQueryRepository {

	private final JPAQueryFactory queryFactory;

	/**
	 * 열차 객차 목록 조회 (잔여 좌석이 있는 객차만)
	 */
	public List<TrainCarInfo> findAvailableTrainCars(Long trainScheduleId, Long departureStationId,
		Long arrivalStationId) {
		QTrainSchedule trainSchedule = QTrainSchedule.trainSchedule;
		QTrain train = QTrain.train;
		QTrainCar trainCar = QTrainCar.trainCar;
		QSeat seat = QSeat.seat;
		QSeatBooking seatBooking = QSeatBooking.seatBooking;

		// stopOrder 기반 구간 겹침을 위한 ScheduleStop 조인
		QScheduleStop bookedDepartureStop = new QScheduleStop("bookedDepartureStop");
		QScheduleStop bookedArrivalStop = new QScheduleStop("bookedArrivalStop");
		QScheduleStop searchDepartureStop = new QScheduleStop("searchDepartureStop");
		QScheduleStop searchArrivalStop = new QScheduleStop("searchArrivalStop");

		// 1. 해당 trainScheduleId의 객차(trainCar) 조회
		List<TrainCarProjection> carProjections = queryFactory
			.select(new QTrainCarProjection(
				trainCar.id,
				trainCar.carNumber,
				trainCar.carType,
				trainCar.totalSeats,
				Expressions.constant(0), // 임시 remainingSeats 기본값 처리
				trainCar.seatArrangement
			))
			.from(trainSchedule)
			.join(trainSchedule.train, train)
			.join(trainCar).on(trainCar.train.id.eq(train.id))
			.where(trainSchedule.id.eq(trainScheduleId))
			.orderBy(trainCar.carNumber.asc())
			.fetch();

		// 2. 각 객차별 예매된 좌석 수 계산
		Map<Long, Long> occupiedSeatsPerCar = queryFactory
			.select(trainCar.id, seatBooking.count())
			.from(seatBooking)
			.join(seatBooking.seat, seat)
			.join(seat.trainCar, trainCar)
			.join(seatBooking.booking, booking)
			// stopOrder 기반 구간 겹침 조건
			.join(booking.departureStop, bookedDepartureStop)
			.join(booking.arrivalStop, bookedArrivalStop)
			.join(searchDepartureStop).on(
				searchDepartureStop.trainSchedule.id.eq(trainScheduleId)
					.and(searchDepartureStop.station.id.eq(departureStationId))
			)
			.join(searchArrivalStop).on(
				searchArrivalStop.trainSchedule.id.eq(trainScheduleId)
					.and(searchArrivalStop.station.id.eq(arrivalStationId))
			)
			.where(
				seatBooking.trainSchedule.id.eq(trainScheduleId),
				seatBooking.seat.isNotNull(),
				// stopOrder 기반 구간 겹침 조건
				// 구간 겹침: NOT(예약종료 <= 검색시작 OR 예약시작 >= 검색종료)
				// = 예약종료 > 검색시작 AND 예약시작 < 검색종료
				bookedArrivalStop.stopOrder.gt(searchDepartureStop.stopOrder)
					.and(bookedDepartureStop.stopOrder.lt(searchArrivalStop.stopOrder))
			)
			.groupBy(trainCar.id)
			.fetch()
			.stream()
			.collect(Collectors.toMap(
				tuple -> tuple.get(trainCar.id),
				tuple -> tuple.get(seatBooking.count())
			));

		// 3. remainingSeats 계산하여 업데이트하고 응답용 record로 변환
		return carProjections.stream()
			.map(projection -> {
				long occupiedSeats = occupiedSeatsPerCar.getOrDefault(projection.getId(), 0L);
				int remainingSeats = Math.max(0, projection.getTotalSeats() - (int)occupiedSeats);
				return projection.withRemainingSeats(remainingSeats);
			})
			.filter(projection -> projection.getRemainingSeats() > 0) // 잔여 좌석이 있는 객차만
			.map(TrainCarProjection::toTrainCarInfo) // 응답용 record로 변환
			.toList();
	}
}
