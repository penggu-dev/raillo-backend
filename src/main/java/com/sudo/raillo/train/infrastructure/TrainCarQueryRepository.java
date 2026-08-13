package com.sudo.raillo.train.infrastructure;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.train.application.dto.projection.QTrainCarProjection;
import com.sudo.raillo.train.application.dto.projection.TrainCarProjection;
import com.sudo.raillo.train.domain.QTrain;
import com.sudo.raillo.train.domain.QTrainCar;
import com.sudo.raillo.train.domain.QTrainSchedule;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TrainCarQueryRepository {

	private final JPAQueryFactory queryFactory;

	/**
	 * 열차 스케줄에 속한 객차 목록 조회
	 *
	 * <p>잔여 좌석 계산은 좌석 점유 집계와 조합해야 하므로 여기서는 객차 정보만 반환한다.</p>
	 */
	public List<TrainCarProjection> findTrainCars(Long trainScheduleId) {
		QTrainSchedule trainSchedule = QTrainSchedule.trainSchedule;
		QTrain train = QTrain.train;
		QTrainCar trainCar = QTrainCar.trainCar;

		return queryFactory
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
	}
}
