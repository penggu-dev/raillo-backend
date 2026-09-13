package com.sudo.raillo.support.fixture.train;

import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.CarType;
import java.lang.reflect.Field;

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

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
