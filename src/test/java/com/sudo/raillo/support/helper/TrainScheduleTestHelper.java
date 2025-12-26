package com.sudo.raillo.support.helper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

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

	private final ScheduleStopRepository scheduleStopRepository;
	private final StationRepository stationRepository;
	private final StationFareRepository stationFareRepository;
	private final TrainScheduleRepository trainScheduleRepository;

	/**
	 * 기본 스케줄 생성 메서드 서울 -> 부산 (출발: 5:00 -> 도착: 8:00) standardFare: 50,000원, firstClassFare: 100,000원
	 */
	public TrainScheduleWithScheduleStops createSchedule(Train train) {
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
	public ScheduleStop getScheduleStopByStationName(TrainScheduleWithScheduleStops trainScheduleWithScheduleStops, String stationName) {
		return trainScheduleWithScheduleStops.scheduleStops().stream()
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
	 * 커스텀 열차 스케줄을 생성하기 위한 빌더를 반환한다.
	 *
	 * <p>복잡한 스케줄 구성이 필요할 때 사용한다. 스케줄 이름, 운행 날짜, 정차역 등을 자유롭게 설정할 수 있다.</p>
	 *
	 * <h4>addStop 파라미터 규칙</h4>
	 * <p>정차역은 addStop 호출 순서대로 stopOrder가 0부터 자동 부여된다.</p>
	 * <ul>
	 *   <li>첫 번째 정차역 (출발역): arrival = null, departure = 필수</li>
	 *   <li>중간 정차역: arrival = 필수, departure = 필수</li>
	 *   <li>마지막 정차역 (도착역): arrival = 필수, departure = null</li>
	 * </ul>
	 *
	 * <h4>사용 예시</h4>
	 * <pre>{@code
	 * ScheduleWithStops result = trainScheduleTestHelper.createCustomSchedule()
	 *     .scheduleName("KTX 101 경부선")
	 *     .operationDate(LocalDate.of(2025, 1, 1))
	 *     .train(train)
	 *     .addStop("서울", null, LocalTime.of(6, 0))           // 출발역 (stopOrder: 0)
	 *     .addStop("대전", LocalTime.of(7, 0), LocalTime.of(7, 5))  // 중간역 (stopOrder: 1)
	 *     .addStop("부산", LocalTime.of(9, 0), null)           // 도착역 (stopOrder: 2)
	 *     .build();
	 * }</pre>
	 *
	 * @return 열차 스케줄 빌더
	 */
	public TrainScheduleBuilder createCustomSchedule() {
		return new TrainScheduleBuilder();
	}

	/**
	 * TrainSchedule 생성용 Builder
	 */
	public class TrainScheduleBuilder {
		private final List<ScheduleStop> stops = new ArrayList<>();
		private String scheduleName;
		private LocalDate operationDate;
		private LocalTime departureTime;
		private LocalTime arrivalTime;
		private Train train;

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

		public TrainScheduleBuilder addStop(String stationName, LocalTime arrival, LocalTime departure) {
			Station station = getOrCreateStation(stationName);
			ScheduleStop stop = ScheduleStopFixture.create(stops.size(), arrival, departure, null, station);
			stops.add(stop);
			return this;
		}

		@Transactional
		public TrainScheduleWithScheduleStops build() {
			validateStops();
			setDepartureAndArrivalTime();
			TrainSchedule schedule = saveSchedule();
			stops.forEach(stop -> stop.setTrainSchedule(schedule));
			List<ScheduleStop> savedStops = scheduleStopRepository.saveAll(stops);
			return new TrainScheduleWithScheduleStops(schedule, savedStops);
		}

		private void validateStops() {
			if (stops.size() < 2) {
				throw new IllegalArgumentException("스케줄은 최소 2개 이상의 정차역이 필요합니다. 현재 정차역 수: " + stops.size());
			}
		}

		private void setDepartureAndArrivalTime() {
			if (departureTime == null) {
				ScheduleStop firstStop = stops.get(0);
				this.departureTime = firstStop.getDepartureTime();
				if (this.departureTime == null) {
					throw new IllegalArgumentException("첫 번째 정차역의 출발시간이 설정되어야 합니다.");
				}
			}

			if (arrivalTime == null) {
				ScheduleStop lastStop = stops.get(stops.size() - 1);
				this.arrivalTime = lastStop.getArrivalTime();
				if (this.arrivalTime == null) {
					throw new IllegalArgumentException("마지막 정차역의 도착시간이 설정되어야 합니다.");
				}
			}
		}

		private TrainSchedule saveSchedule() {
			Station departure = stops.get(0).getStation();
			Station arrival = stops.get(stops.size() - 1).getStation();

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
	}
}
