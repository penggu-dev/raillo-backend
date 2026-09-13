package com.sudo.raillo.batch.application.service;

import com.sudo.raillo.batch.config.TrainTemplateProperties;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.batch.infrastructure.TrainCarRepository;
import com.sudo.raillo.batch.infrastructure.jdbc.TrainJdbcRepository;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrainCarService {

	private final TrainTemplateProperties properties;
	private final TrainCarRepository trainCarRepository;
	private final TrainJdbcRepository trainJdbcRepository;
	private final SeatService seatService;

	/**
	 * 객차 생성
	 */
	public void createTrainCars(List<Train> trains) {
		// 객차 생성
		List<TrainCar> trainCars = trains.stream()
			.flatMap(train -> createTrainCars(train).stream())
			.toList();

		// 객차 저장
		trainJdbcRepository.saveAllTrainCars(trainCars);
		log.info("{}개의 객차 저장 완료", trainCars.size());

		// 좌석 생성
		seatService.createSeats(fetchTrainCars(trains));
	}

	private List<TrainCar> createTrainCars(Train train) {
		TrainTemplateProperties.TrainTemplate template = properties.getTrainTemplate(train);
		return IntStream.range(0, template.cars().size())
			.mapToObj(index -> {
				TrainTemplateProperties.CarSpec spec = template.cars().get(index);
				TrainTemplateProperties.SeatLayout layout = properties.getSeatLayout(spec);
				int totalSeats = spec.row() * layout.columns().size();
				return TrainCar.create(
					index + 1,
					spec.carType(),
					spec.row(),
					totalSeats,
					layout.seatArrangement(),
					train
				);
			})
			.toList();
	}

	/**
	 * 객차 ID를 가져오기 위한 메서드
	 */
	private List<TrainCar> fetchTrainCars(List<Train> trains) {
		return trainCarRepository.findByTrainIn(trains);
	}
}
