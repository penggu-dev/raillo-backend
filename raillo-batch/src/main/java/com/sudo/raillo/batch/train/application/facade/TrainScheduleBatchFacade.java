package com.sudo.raillo.batch.train.application.facade;

import com.sudo.raillo.batch.train.application.dto.TrainData;
import com.sudo.raillo.batch.train.application.dto.TrainScheduleData;
import com.sudo.raillo.batch.train.application.service.ScheduleStopService;
import com.sudo.raillo.batch.train.application.service.StationService;
import com.sudo.raillo.batch.train.application.service.TrainScheduleTemplateService;
import com.sudo.raillo.batch.train.application.service.TrainService;
import com.sudo.raillo.batch.train.application.util.OperatingDayUtil;
import com.sudo.raillo.batch.train.infrastructure.TrainScheduleRepository;
import com.sudo.raillo.batch.train.infrastructure.excel.TrainScheduleParser;
import com.sudo.raillo.batch.train.infrastructure.jdbc.TrainScheduleJdbcRepository;
import com.sudo.raillo.train.domain.Station;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.TrainScheduleTemplate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellAddress;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrainScheduleBatchFacade {

	private final TrainScheduleParser parser;
	private final StationService stationService;
	private final TrainService trainService;
	private final TrainScheduleTemplateService trainScheduleTemplateService;
	private final TrainScheduleRepository trainScheduleRepository;
	private final TrainScheduleJdbcRepository trainScheduleJdbcRepository;
	private final ScheduleStopService scheduleStopService;

	/**
	 * 마지막 운행일 기준 다음 날 스케줄 생성
	 *
	 * @return 스케줄 생성 대상 날짜
	 */
	@Transactional
	public List<LocalDate> createTrainSchedule() {
		LocalDate localDate = trainScheduleRepository.findLastOperationDate()
			.map(date -> date.plusDays(1))
			.orElse(LocalDate.now());

		log.info("[{}] 스케줄 생성 대상 날짜", localDate);
		return createTrainSchedule(List.of(localDate));
	}

	/**
	 * 이미 스케줄이 있어 건너뛴 날짜도 반환에 포함한다. 후속 캐시 적재가 그 날짜도 다시 채워야 하기 때문이다.
	 *
	 * @return 스케줄 생성 대상 날짜
	 */
	@Transactional
	public List<LocalDate> createTrainSchedule(List<LocalDate> dates) {
		if (dates.isEmpty()) {
			log.info("스케줄 생성 대상 날짜가 없습니다.");
			return dates;
		}

		log.info("[{} ~ {}] {} 일간 스케줄 생성 시작", dates.get(0), dates.get(dates.size() - 1), dates.size());
		List<TrainSchedule> trainSchedules = new ArrayList<>();
		List<TrainScheduleTemplate> templates = trainScheduleTemplateService.findTrainScheduleTemplate();

		// 운행 스케줄이 존재하는 날짜 조회
		Set<LocalDate> existingDates = trainScheduleRepository.findExistingOperationDatesIn(dates);
		List<LocalDate> newDates = dates.stream()
			.filter(date -> !existingDates.contains(date))
			.toList();

		for (LocalDate date : dates) {
			if (existingDates.contains(date)) {
				log.info("[{}] 이미 운행 스케줄이 존재합니다.", date);
				continue;
			}

			// 스케줄 템플릿에서 운행일에 해당하는 스케줄만 추가
			trainSchedules.addAll(templates.stream()
				.filter(template -> OperatingDayUtil.isOperatingDay(date, template.getOperatingDay()))
				.map(template -> TrainSchedule.create(date, template))
				.toList());
		}

		if (!trainSchedules.isEmpty()) {
			// 스케줄 저장
			trainScheduleJdbcRepository.saveAll(trainSchedules);
			log.info("{}개의 운행 스케줄 저장 완료", trainSchedules.size());

			// 정차역 생성
			scheduleStopService.createScheduleStops(
				fetchTrainSchedules(trainSchedules, newDates),
				templates
			);
		}

		return dates;
	}

	/**
	 * 스케줄 ID를 가져오기 위한 메서드
	 */
	private List<TrainSchedule> fetchTrainSchedules(List<TrainSchedule> schedules, List<LocalDate> dates) {
		List<String> scheduleNames = schedules.stream()
			.map(TrainSchedule::getScheduleName)
			.toList();

		return trainScheduleRepository.findByScheduleNameInAndOperationDateIn(scheduleNames, dates);
	}

	/**
	 * 스케줄 파싱
	 */
	@Transactional
	public void parseTrainSchedule() {
		log.info("스케줄 파싱 시작");

		List<Sheet> sheets = parser.getSheets();
		Set<String> stationNames = new LinkedHashSet<>();
		List<TrainScheduleData> trainScheduleData = new ArrayList<>();

		for (Sheet sheet : sheets) {
			List<CellAddress> addresses = getFirstCellAddresses(sheet);

			for (CellAddress address : addresses) {

				// 역 이름 파싱
				stationNames.addAll(parser.parseStationNames(sheet, address));

				// 스케줄 파싱
				trainScheduleData.addAll(parser.parseTrainSchedule(sheet, address));
			}
		}

		// 파싱 결과 저장
		persistTrainSchedule(stationNames, trainScheduleData);
		log.info("스케줄 파싱 종료");
	}

	/**
	 * 파싱 결과 저장
	 */
	private void persistTrainSchedule(Set<String> stationNames, List<TrainScheduleData> trainScheduleData) {
		// 역 조회 및 저장
		Map<String, Station> stationMap = stationService.findOrCreateStations(stationNames);

		// 스케줄에서 열차 데이터 분리
		List<TrainData> trainData = trainScheduleData.stream()
			.map(TrainScheduleData::getTrainData)
			.toList();

		// 열차 조회 및 저장
		Map<Integer, Train> trainMap = trainService.findOrCreateTrains(trainData);

		// 스케줄 템플릿 저장
		trainScheduleTemplateService.createTrainScheduleTemplate(trainScheduleData, stationMap, trainMap);
	}

	/**
	 * 하행과 상행 파싱 시작 지점 반환
	 */
	private List<CellAddress> getFirstCellAddresses(Sheet sheet) {
		CellAddress downTrainAddress = parser.getFirstCellAddress(sheet, 0); // 하행
		CellAddress upTrainAddress = parser.getFirstCellAddress(sheet, downTrainAddress.getColumn() + 1); // 상행
		return List.of(downTrainAddress, upTrainAddress);
	}
}
