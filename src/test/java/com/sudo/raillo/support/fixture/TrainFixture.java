package com.sudo.raillo.support.fixture;

import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.TrainType;
import java.lang.reflect.Field;

public class TrainFixture {

	public static Train create(
		int trainNumber,
		TrainType trainType,
		String trainName,
		int totalCars
	) {
		try {
			var constructor = Train.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			Train train = constructor.newInstance();

			setField(train, "trainNumber", trainNumber);
			setField(train, "trainType", trainType);
			setField(train, "trainName", trainName);
			setField(train, "totalCars", totalCars);

			return train;
		} catch (Exception e) {
			throw new RuntimeException("TrainFixture 생성 에러", e);
		}
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}

