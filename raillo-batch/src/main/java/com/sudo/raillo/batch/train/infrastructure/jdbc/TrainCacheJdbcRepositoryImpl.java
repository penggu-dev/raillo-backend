package com.sudo.raillo.batch.train.infrastructure.jdbc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.batch.train.application.dto.ScheduleStopCacheEntry;
import com.sudo.raillo.batch.train.application.dto.StationFareCacheEntry;
import com.sudo.raillo.train.cache.ScheduleInfoCacheValue;
import com.sudo.raillo.train.cache.ScheduleStopCacheValue;
import com.sudo.raillo.train.cache.SeatCacheValue;
import com.sudo.raillo.train.cache.StationFareCacheValue;
import com.sudo.raillo.train.cache.TrainCarCacheValue;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.domain.type.SeatType;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TrainCacheJdbcRepositoryImpl implements TrainCacheJdbcRepository {

	private static final String SEAT_SQL = """
		SELECT s.seat_id, s.train_car_id, s.seat_row, s.seat_column, s.seat_type,
		       tc.car_number, tc.car_type
		FROM seat s
		JOIN train_car tc ON tc.train_car_id = s.train_car_id
		""";

	private static final String TRAIN_CAR_SQL = """
		SELECT train_car_id, train_id, car_number, car_type,
		       seat_row_count, total_seats, seat_arrangement
		FROM train_car
		""";

	private static final String STATION_SQL = """
		SELECT station_id, station_name
		FROM station
		""";

	private static final String STATION_FARE_SQL = """
		SELECT departure_station_id, arrival_station_id, standard_fare, first_class_fare
		FROM station_fare
		""";

	private static final String SCHEDULE_SQL = """
		SELECT ts.train_schedule_id, ts.operation_date, ts.departure_time, ts.arrival_time,
		       ts.operation_status, ts.delay_minutes,
		       ts.train_id, t.train_number, t.train_name,
		       ts.departure_station_id, ts.arrival_station_id
		FROM train_schedule ts
		JOIN train t ON t.train_id = ts.train_id
		WHERE ts.operation_date BETWEEN ? AND ?
		""";

	private static final String SCHEDULE_STOP_SQL = """
		SELECT ss.train_schedule_id, ss.schedule_stop_id, ss.stop_order,
		       ss.station_id, st.station_name, ss.arrival_time, ss.departure_time
		FROM schedule_stop ss
		JOIN station st ON st.station_id = ss.station_id
		JOIN train_schedule ts ON ts.train_schedule_id = ss.train_schedule_id
		WHERE ts.operation_date BETWEEN ? AND ?
		ORDER BY ss.train_schedule_id, ss.stop_order
		""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Map<Long, SeatCacheValue> findAllSeats() {
		return jdbcTemplate.query(SEAT_SQL, rs -> {
			Map<Long, SeatCacheValue> seats = new LinkedHashMap<>();
			while (rs.next()) {
				seats.put(rs.getLong("seat_id"), new SeatCacheValue(
					rs.getLong("train_car_id"),
					rs.getInt("car_number"),
					CarType.valueOf(rs.getString("car_type")),
					rs.getInt("seat_row"),
					rs.getString("seat_column"),
					SeatType.valueOf(rs.getString("seat_type"))
				));
			}
			return seats;
		});
	}

	@Override
	public Map<Long, TrainCarCacheValue> findAllTrainCars() {
		return jdbcTemplate.query(TRAIN_CAR_SQL, rs -> {
			Map<Long, TrainCarCacheValue> trainCars = new LinkedHashMap<>();
			while (rs.next()) {
				trainCars.put(rs.getLong("train_car_id"), new TrainCarCacheValue(
					rs.getLong("train_id"),
					rs.getInt("car_number"),
					CarType.valueOf(rs.getString("car_type")),
					rs.getInt("seat_row_count"),
					rs.getInt("total_seats"),
					rs.getString("seat_arrangement")
				));
			}
			return trainCars;
		});
	}

	@Override
	public Map<Long, String> findAllStationNames() {
		return jdbcTemplate.query(STATION_SQL, rs -> {
			Map<Long, String> stations = new LinkedHashMap<>();
			while (rs.next()) {
				stations.put(rs.getLong("station_id"), rs.getString("station_name"));
			}
			return stations;
		});
	}

	@Override
	public List<StationFareCacheEntry> findAllStationFares() {
		return jdbcTemplate.query(STATION_FARE_SQL, rs -> {
			List<StationFareCacheEntry> fares = new ArrayList<>();
			while (rs.next()) {
				fares.add(new StationFareCacheEntry(
					rs.getLong("departure_station_id"),
					rs.getLong("arrival_station_id"),
					new StationFareCacheValue(
						rs.getBigDecimal("standard_fare"),
						rs.getBigDecimal("first_class_fare")
					)
				));
			}
			return fares;
		});
	}

	@Override
	public List<ScheduleInfoCacheValue> findSchedulesBetween(LocalDate startDate, LocalDate endDate) {
		return jdbcTemplate.query(SCHEDULE_SQL, rs -> {
			List<ScheduleInfoCacheValue> schedules = new ArrayList<>();
			while (rs.next()) {
				schedules.add(new ScheduleInfoCacheValue(
					rs.getLong("train_schedule_id"),
					rs.getObject("operation_date", LocalDate.class),
					rs.getObject("departure_time", LocalTime.class),
					rs.getObject("arrival_time", LocalTime.class),
					OperationStatus.valueOf(rs.getString("operation_status")),
					rs.getInt("delay_minutes"),
					rs.getLong("train_id"),
					rs.getInt("train_number"),
					rs.getString("train_name"),
					rs.getLong("departure_station_id"),
					rs.getLong("arrival_station_id")
				));
			}
			return schedules;
		}, startDate, endDate);
	}

	@Override
	public List<ScheduleStopCacheEntry> findScheduleStopsBetween(LocalDate startDate, LocalDate endDate) {
		return jdbcTemplate.query(SCHEDULE_STOP_SQL, rs -> {
			List<ScheduleStopCacheEntry> stops = new ArrayList<>();
			while (rs.next()) {
				stops.add(new ScheduleStopCacheEntry(
					rs.getLong("train_schedule_id"),
					new ScheduleStopCacheValue(
						rs.getLong("schedule_stop_id"),
						rs.getInt("stop_order"),
						rs.getLong("station_id"),
						rs.getString("station_name"),
						rs.getObject("arrival_time", LocalTime.class),
						rs.getObject("departure_time", LocalTime.class)
					)
				));
			}
			return stops;
		}, startDate, endDate);
	}
}
