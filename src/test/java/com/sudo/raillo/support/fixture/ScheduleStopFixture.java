package com.sudo.raillo.support.fixture;

import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.TrainSchedule;
import java.lang.reflect.Field;
import java.time.LocalTime;

public class ScheduleStopFixture {

	public static ScheduleStop create(
			int stopOrder,
			LocalTime arrivalTime,
			LocalTime departureTime,
			TrainSchedule trainSchedule,
			Station station
	) {
		try {
			var constructor = ScheduleStop.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			ScheduleStop scheduleStop = constructor.newInstance();

			setField(scheduleStop, "stopOrder", stopOrder);
			setField(scheduleStop, "arrivalTime", arrivalTime);
			setField(scheduleStop, "departureTime", departureTime);
			scheduleStop.setTrainSchedule(trainSchedule);
			setField(scheduleStop, "station", station);

			return scheduleStop;
		} catch (Exception e) {
			throw new RuntimeException("ScheduleStopFixture 생성 에러", e);
		}
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
