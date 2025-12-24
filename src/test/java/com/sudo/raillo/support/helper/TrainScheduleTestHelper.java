package com.sudo.raillo.support.helper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.support.fixture.train.ScheduleStopFixture;
import com.sudo.raillo.support.fixture.train.StationFareFixture;
import com.sudo.raillo.support.fixture.train.StationFixture;
import com.sudo.raillo.support.fixture.train.TrainScheduleFixture;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.StationFareRepository;
import com.sudo.raillo.train.infrastructure.StationRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Transactional
public class TrainScheduleTestHelper {

	public static final int EVERYDAY = 127;

	private final ScheduleStopRepository scheduleStopRepository;
	private final StationRepository stationRepository;
	private final StationFareRepository stationFareRepository;
	private final TrainScheduleRepository trainScheduleRepository;

	/**
	 * 기본 스케줄 생성 메서드
	 * 서울 -> 부산 (출발: 5:00 -> 도착: 8:00)
	 * standardFare: 50,000원, firstClassFare: 100,000원
	 */
	public TrainScheduleWithStopStations createSchedule(Train train) {
		createOrUpdateStationFare("서울", "부산", 50000, 100000);
		return createCustomSchedule()
			.scheduleName("KTX 001 경부선")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("서울", null, LocalTime.of(5, 0))
			.addStop("부산", LocalTime.of(8, 0), null)
			.build();
	}

	/**
	 * 커스텀 스케줄 빌더
	 */
	public TrainScheduleBuilder createCustomSchedule() {
		return new TrainScheduleBuilder();
	}

	/**
	 * 역 + 구간요금 생성 (없으면 생성, 있으면 갱신)
	 */
	public void createOrUpdateStationFare(String from, String to, int standardFare, int firstClassFare) {
		Station departure = getOrCreateStation(from);
		Station arrival = getOrCreateStation(to);

		StationFare fare = StationFareFixture.create(departure, arrival, standardFare, firstClassFare);
		stationFareRepository.save(fare);
	}

	/**
	 * 특정 역의 정차 정보 조회
	 */
	public ScheduleStop getScheduleStopByStationName(TrainScheduleWithStopStations schedule, String stationName) {
		return schedule.scheduleStops().stream()
			.filter(s -> s.getStation().getStationName().equals(stationName))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("정차역을 찾을 수 없습니다: " + stationName));
	}

	/**
	 * 역 생성 또는 조회
	 */
	public Station getOrCreateStation(String stationName) {
		return stationRepository.findByStationName(stationName)
			.orElseGet(() -> stationRepository.save(StationFixture.create(stationName)));
	}

	/**
	 * TrainSchedule 생성용 Builder
	 */
	public class TrainScheduleBuilder {
		private final List<StopInfo> stops = new ArrayList<>();
		private String scheduleName;
		private LocalDate operationDate;
		private LocalTime departureTime;
		private LocalTime arrivalTime;
		private Train train;
		private int operatingDays = EVERYDAY;

		public TrainScheduleBuilder scheduleName(String name) {
			this.scheduleName = name;
			return this;
		}

		public TrainScheduleBuilder operationDate(LocalDate date) {
			this.operationDate = date;
			return this;
		}

		public TrainScheduleBuilder train(Train train) {
			this.train = train;
			return this;
		}

		public TrainScheduleBuilder operatingDays(int days) {
			this.operatingDays = days;
			return this;
		}

		public TrainScheduleBuilder addStop(String stationName, LocalTime arrival, LocalTime departure) {
			stops.add(new StopInfo(stationName, arrival, departure, stops.size()));
			return this;
		}

		@Transactional
		public TrainScheduleWithStopStations build() {
			validateStops();
			setDepartureAndArrivalTime();
			Map<String, Station> stationMap = resolveStations();
			TrainSchedule schedule = saveSchedule(stationMap);
			List<ScheduleStop> savedStops = saveScheduleStops(schedule, stationMap);
			return new TrainScheduleWithStopStations(schedule, savedStops);
		}

		private void validateStops() {
			if (stops.size() < 2) {
				throw new IllegalArgumentException("스케줄은 최소 2개 이상의 정차역이 필요합니다. 현재 정차역 수: " + stops.size());
			}
		}

		private void setDepartureAndArrivalTime() {
			if (departureTime == null) {
				StopInfo firstStop = stops.get(0);
				this.departureTime = firstStop.departureTime();
				if (this.departureTime == null) {
					throw new IllegalArgumentException("첫 번째 정차역의 출발시간이 설정되어야 합니다.");
				}
			}

			if (arrivalTime == null) {
				StopInfo lastStop = stops.get(stops.size() - 1);
				this.arrivalTime = lastStop.arrivalTime();
				if (this.arrivalTime == null) {
					throw new IllegalArgumentException("마지막 정차역의 도착시간이 설정되어야 합니다.");
				}
			}
		}

		private Map<String, Station> resolveStations() {
			Map<String, Station> map = new HashMap<>();
			stops.forEach(stop -> map.putIfAbsent(stop.stationName(), getOrCreateStation(stop.stationName())));
			return map;
		}

		private TrainSchedule saveSchedule(Map<String, Station> stationMap) {
			Station departure = stationMap.get(stops.get(0).stationName());
			Station arrival = stationMap.get(stops.get(stops.size() - 1).stationName());

			TrainSchedule schedule = TrainScheduleFixture.create(
				scheduleName,
				operationDate,
				departureTime,
				arrivalTime,
				OperationStatus.ACTIVE,
				train,
				departure,
				arrival
			);
			return trainScheduleRepository.save(schedule);
		}

		private List<ScheduleStop> saveScheduleStops(TrainSchedule schedule, Map<String, Station> stationMap) {
			List<ScheduleStop> scheduleStops = new ArrayList<>();

			for (StopInfo stopInfo : stops) {
				Station station = stationMap.get(stopInfo.stationName());
				ScheduleStop scheduleStop = ScheduleStopFixture.create(
					stopInfo.stopOrder(),
					stopInfo.arrivalTime(),
					stopInfo.departureTime(),
					schedule,
					station
				);
				scheduleStops.add(scheduleStop);
			}

			return scheduleStopRepository.saveAll(scheduleStops);
		}
	}

	/**
	 * 생성 결과 객체 (스케줄 + 정차역들)
	 */
	public record TrainScheduleWithStopStations(TrainSchedule trainSchedule, List<ScheduleStop> scheduleStops) {
	}

	/**
	 * 정차역 임시 정보 (Builder 내부용)
	 */
	private record StopInfo(String stationName, LocalTime arrivalTime, LocalTime departureTime, int stopOrder) {
	}
}
