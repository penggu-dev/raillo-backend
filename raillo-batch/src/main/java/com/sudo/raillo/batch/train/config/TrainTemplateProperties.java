package com.sudo.raillo.batch.train.config;

import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.domain.type.SeatType;
import com.sudo.raillo.train.domain.type.TrainType;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "train-template")
public class TrainTemplateProperties {

	private final Map<CarType, SeatLayout> layouts;
	private final Map<TrainType, TrainTemplate> templates;

	public record SeatLayout(String seatArrangement, List<SeatColumn> columns) {
	}

	public record SeatColumn(String name, SeatType seatType) {
	}

	public record TrainTemplate(List<CarSpec> cars) {
	}

	public record CarSpec(CarType carType, int row) {
	}

	public TrainTemplate getTrainTemplate(Train train) {
		return templates.get(train.getTrainType());
	}

	public CarSpec getCarSpec(TrainCar trainCar) {
		TrainTemplate template = getTrainTemplate(trainCar.getTrain());
		return template.cars.get(trainCar.getCarNumber() - 1);
	}

	public SeatLayout getSeatLayout(CarSpec spec) {
		return layouts.get(spec.carType());
	}
}
