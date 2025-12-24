package com.sudo.raillo.support.fixture.train;

import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.StationFare;
import java.lang.reflect.Field;

public class StationFareFixture {

	public static StationFare create(
			Station departureStation,
			Station arrivalStation,
			int standardFare,
			int firstClassFare
	) {
		try {
			var constructor = StationFare.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			StationFare stationFare = constructor.newInstance();

			setField(stationFare, "departureStation", departureStation);
			setField(stationFare, "arrivalStation", arrivalStation);
			setField(stationFare, "standardFare", standardFare);
			setField(stationFare, "firstClassFare", firstClassFare);

			return stationFare;
		} catch (Exception e) {
			throw new RuntimeException("StationFareFixture 생성 에러", e);
		}
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
