package com.sudo.raillo.support.fixture.train;

import com.sudo.raillo.train.domain.Station;
import java.lang.reflect.Field;

public class StationFixture {

	public static Station create(String stationName) {
		try {
			var constructor = Station.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			Station station = constructor.newInstance();
			setField(station, "stationName", stationName);
			return station;
		} catch (Exception e) {
			throw new RuntimeException("StationFixture 생성 에러", e);
		}
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
