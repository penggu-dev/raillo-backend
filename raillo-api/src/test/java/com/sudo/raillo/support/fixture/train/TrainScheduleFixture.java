package com.sudo.raillo.support.fixture.train;

import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalTime;

public class TrainScheduleFixture {

	public static TrainSchedule create(
			String scheduleName,
			LocalDate operationDate,
			LocalTime departureTime,
			LocalTime arrivalTime,
			OperationStatus operationStatus,
			Train train,
			Station departureStation,
			Station arrivalStation
	) {
		try {
			var constructor = TrainSchedule.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			TrainSchedule schedule = constructor.newInstance();

			setField(schedule, "scheduleName", scheduleName);
			setField(schedule, "operationDate", operationDate);
			setField(schedule, "departureTime", departureTime);
			setField(schedule, "arrivalTime", arrivalTime);
			setField(schedule, "operationStatus", operationStatus);
			setField(schedule, "delayMinutes", 0);
			setField(schedule, "train", train);
			setField(schedule, "departureStation", departureStation);
			setField(schedule, "arrivalStation", arrivalStation);

			return schedule;
		} catch (Exception e) {
			throw new RuntimeException("TrainScheduleFixture 생성 에러", e);
		}
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
