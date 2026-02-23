package com.sudo.raillo.support.fixture.train;

import com.sudo.raillo.train.config.TrainTemplateProperties.CarSpec;
import com.sudo.raillo.train.config.TrainTemplateProperties.SeatLayout;
import com.sudo.raillo.train.config.TrainTemplateProperties.TrainTemplate;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.CarType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TrainCarFixture {

	public static TrainCar create(
		Train train,
		int carNumber,
		CarType carType,
		int seatRowCount,
		int totalSeats,
		String seatArrangement
	) {
		try {
			var constructor = TrainCar.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			TrainCar trainCar = constructor.newInstance();

			setField(trainCar, "carNumber", carNumber);
			setField(trainCar, "carType", carType);
			setField(trainCar, "seatRowCount", seatRowCount);
			setField(trainCar, "totalSeats", totalSeats);
			setField(trainCar, "seatArrangement", seatArrangement);
			trainCar.setTrain(train);

			return trainCar;
		} catch (Exception e) {
			throw new RuntimeException("TrainCarFixture 생성 에러", e);
		}
	}

	public static List<TrainCar> generateTrainCars(
		Train train,
		Map<CarType, SeatLayout> seatLayouts,
		TrainTemplate trainTemplate
	) {
		List<TrainCar> trainCars = new ArrayList<>();
		List<CarSpec> carSpecs = trainTemplate.cars();

		for (int i = 0; i < carSpecs.size(); i++) {
			CarSpec carSpec = carSpecs.get(i);
			SeatLayout seatLayout = seatLayouts.get(carSpec.carType());

			int carNumber = i + 1;
			int seatRowCount = carSpec.row();
			int totalSeats = calculateTotalSeats(seatRowCount, seatLayout);

			TrainCar trainCar = create(
				train,
				carNumber,
				carSpec.carType(),
				seatRowCount,
				totalSeats,
				seatLayout.seatArrangement()
			);

			trainCars.add(trainCar);
		}

		return trainCars;
	}

	private static int calculateTotalSeats(int rowCount, SeatLayout seatLayout) {
		int columnsPerRow = seatLayout.columns().size();
		return rowCount * columnsPerRow;
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
