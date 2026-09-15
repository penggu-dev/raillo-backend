package com.sudo.raillo.batch.train.application.service;

import com.sudo.raillo.batch.train.config.TrainTemplateProperties;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.batch.train.infrastructure.jdbc.TrainJdbcRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatService {

	private final TrainTemplateProperties properties;
	private final TrainJdbcRepository trainJdbcRepository;

	/**
	 * 좌석 생성
	 */
	public void createSeats(List<TrainCar> trainCars) {
		// 좌석 생성
		List<Seat> seats = trainCars.stream()
			.flatMap(trainCar -> {
				TrainTemplateProperties.CarSpec spec = properties.getCarSpec(trainCar);
				TrainTemplateProperties.SeatLayout layout = properties.getSeatLayout(spec);
				return java.util.stream.IntStream.rangeClosed(1, spec.row())
					.boxed()
					.flatMap(row -> layout.columns().stream()
						.map(column -> Seat.create(row, column.name(), column.seatType(), trainCar)));
			}).toList();

		// 좌석 저장
		trainJdbcRepository.saveAllSeats(seats);
		log.info("{}개의 좌석 저장 완료", seats.size());
	}
}
