package com.sudo.raillo.support.fixture.train;

import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.SeatType;
import java.lang.reflect.Field;

public class SeatFixture {

	public static Seat create(
		TrainCar trainCar,
		int seatRow,
		String seatColumn,
		SeatType seatType,
		String isAccessible,
		String isAvailable
	) {
		try {
			var constructor = Seat.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			Seat seat = constructor.newInstance();

			setField(seat, "seatRow", seatRow);
			setField(seat, "seatColumn", seatColumn);
			setField(seat, "seatType", seatType);
			setField(seat, "isAccessible", isAccessible);
			setField(seat, "isAvailable", isAvailable);
			seat.setTrainCar(trainCar);

			return seat;
		} catch (Exception e) {
			throw new RuntimeException("SeatFixture 생성 에러", e);
		}
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
