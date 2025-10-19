package com.sudo.raillo.support.fixture;

import com.sudo.raillo.train.config.TrainTemplateProperties.CarSpec;
import com.sudo.raillo.train.config.TrainTemplateProperties.SeatColumn;
import com.sudo.raillo.train.config.TrainTemplateProperties.SeatLayout;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainCar;
import com.sudo.raillo.train.domain.type.SeatType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

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

	public static List<Seat> generateSeats(TrainCar trainCar, CarSpec carSpec, SeatLayout seatLayout) {
		List<Seat> seats = new ArrayList<>();
		int rowCount = carSpec.row();
		List<SeatColumn> columns = seatLayout.columns();

		for (int row = 1; row <= rowCount; row++) {
			for (SeatColumn column : columns) {
				Seat seat = create(
					trainCar,
					row,
					column.name(),
					column.seatType(),
					"Y",
					"Y"
				);

				seats.add(seat);
			}
		}

		return seats;
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
