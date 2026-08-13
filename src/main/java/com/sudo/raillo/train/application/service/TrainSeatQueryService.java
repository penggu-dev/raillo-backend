package com.sudo.raillo.train.application.service;

import com.sudo.raillo.booking.infrastructure.SeatOccupancyQueryRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.application.dto.TrainCarSeatInfo;
import com.sudo.raillo.train.application.dto.projection.TrainCarProjection;
import com.sudo.raillo.train.application.dto.request.TrainCarSeatDetailRequest;
import com.sudo.raillo.train.application.dto.response.TrainCarInfo;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainError;
import com.sudo.raillo.train.infrastructure.SeatQueryRepository;
import com.sudo.raillo.train.infrastructure.SeatRepository;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.infrastructure.TrainCarQueryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainSeatQueryService {

	private final TrainCarQueryRepository trainCarQueryRepository;
	private final SeatQueryRepository seatQueryRepository;
	private final SeatOccupancyQueryRepository seatOccupancyQueryRepository;
	private final SeatRepository seatRepository;

	/**
	 * 열차 객차 목록 조회 (좌석 점유를 차감한 잔여 좌석이 있는 객차만)
	 *
	 * @param arrivalStopOrder 도착 stopOrder (점유 구간은 이 값 미만까지)
	 */
	public List<TrainCarInfo> getAvailableTrainCars(
		Long trainScheduleId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		List<TrainCarProjection> trainCars = trainCarQueryRepository.findTrainCars(trainScheduleId);
		Map<Long, Integer> occupiedSeatsPerCar = seatOccupancyQueryRepository.countOccupiedSeatsByTrainCar(
			trainScheduleId, departureStopOrder, arrivalStopOrder, LocalDateTime.now());

		List<TrainCarInfo> availableCars = trainCars.stream()
			.map(trainCar -> {
				int occupied = occupiedSeatsPerCar.getOrDefault(trainCar.getId(), 0);
				return trainCar.withRemainingSeats(Math.max(0, trainCar.getTotalSeats() - occupied));
			})
			.filter(trainCar -> trainCar.getRemainingSeats() > 0)
			.map(TrainCarProjection::toTrainCarInfo)
			.toList();

		if (availableCars.isEmpty()) {
			log.warn("잔여 좌석이 있는 객차가 없음: trainScheduleId={}", trainScheduleId);
			throw new BusinessException(TrainError.NO_AVAILABLE_CARS);
		}

		return availableCars;
	}

	/**
	 * 열차 객차 좌석 상세 조회
	 */
	public TrainCarSeatInfo getTrainCarSeatDetail(TrainCarSeatDetailRequest request) {
		TrainCarSeatInfo carSeatInfo = seatQueryRepository.findTrainCarSeatDetail(
			request.trainCarId(),
			request.trainScheduleId(),
			request.departureStationId(),
			request.arrivalStationId()
		);
		if (carSeatInfo.departureStopOrder() == null || carSeatInfo.arrivalStopOrder() == null) {
			throw new BusinessException(TrainError.SCHEDULE_STOP_NOT_FOUND);
		}
		if (carSeatInfo.departureStopOrder() >= carSeatInfo.arrivalStopOrder()) {
			throw new BusinessException(TrainError.INVALID_ROUTE);
		}

		log.info("열차 객차 좌석 상세 조회 완료: 객차={}, 전체좌석={}, 잔여좌석={}",
			carSeatInfo.carNumber(), carSeatInfo.totalSeats(), carSeatInfo.remainingSeats());

		return carSeatInfo;
	}

	/**
	 * 좌석 ID 목록에 해당하는 객차 타입 조회
	 * @return 중복 제거된 객차 타입 목록
	 */
	public List<CarType> getCarTypes(List<Long> seatIds) {
		return seatRepository.findCarTypes(seatIds);
	}

	/**
	 * 좌석 ID 목록에 해당하는 좌석 조회
	 *
	 * <p>요청한 순서를 그대로 유지해 반환한다. 좌석과 승객 유형이 인덱스로 짝지어지므로
	 * DB 조회 순서를 그대로 쓰면 승객 유형이 어긋난다.</p>
	 *
	 * @throws BusinessException 요청한 좌석 중 존재하지 않는 좌석이 있는 경우
	 */
	public List<Seat> getSeats(List<Long> seatIds) {
		Map<Long, Seat> seatMap = seatRepository.findAllByIdWithTrainCar(seatIds).stream()
			.collect(Collectors.toMap(Seat::getId, Function.identity()));

		if (seatMap.size() != Set.copyOf(seatIds).size()) {
			log.warn("[좌석 조회 실패] 존재하지 않는 좌석이 포함됨: seatIds={}", seatIds);
			throw new BusinessException(TrainError.SEAT_NOT_FOUND);
		}

		return seatIds.stream()
			.map(seatMap::get)
			.toList();
	}

	/**
	 * 좌석 ID 목록에서 trainCarId 추출
	 * 같은 CarType의 좌석들은 모두 같은 객차에 속하므로 첫 번째 좌석의 trainCarId 반환
	 */
	public Long getTrainCarId(List<Long> seatIds) {
		return seatRepository.findAllByIdWithTrainCar(List.of(seatIds.get(0)))
			.get(0).getTrainCar().getId();
	}
}
