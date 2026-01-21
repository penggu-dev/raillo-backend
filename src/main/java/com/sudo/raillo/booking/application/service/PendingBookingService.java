package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.SeatInfo;
import com.sudo.raillo.booking.application.dto.StopInfo;
import com.sudo.raillo.booking.application.dto.TrainScheduleInfo;
import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingDetailResponse;
import com.sudo.raillo.booking.application.mapper.PendingBookingMapper;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.SeatRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PendingBookingService {

	private final TrainScheduleRepository trainScheduleRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final BookingRedisRepository bookingRedisRepository;
	private final SeatRepository seatRepository;
	private final BookingValidator bookingValidator;
	private final PendingBookingMapper pendingBookingMapper;

	/**
	 * 예약을 생성하는 메서드
	 * @param request 예약 요청 DTO
	 * @return 예약
	 * */
	public PendingBooking createPendingBooking(
		String pendingBookingId,
		PendingBookingCreateRequest request,
		String memberNo,
		BigDecimal totalFare
	) {
		// 1. 기본 검증
		TrainSchedule trainSchedule = getTrainSchedule(request.trainScheduleId());
		ScheduleStop departureStop = getStopStation(trainSchedule, request.departureStationId());
		ScheduleStop arrivalStop = getStopStation(trainSchedule, request.arrivalStationId());

		// 열차 스케줄, 출발역, 도착역 검증
		bookingValidator.validateTrainOperating(trainSchedule);
		bookingValidator.validateSameSchedule(departureStop, arrivalStop);
		bookingValidator.validateStopSequence(departureStop, arrivalStop);
		bookingValidator.validatePassengerSeatCount(request.passengerTypes(), request.seatIds());

		List<PendingSeatBooking> pendingSeatBookings = createPendingSeatBookings(request.passengerTypes(),
			request.seatIds());

		PendingBooking pendingBooking = PendingBooking.createWithId(
			pendingBookingId,
			memberNo,
			trainSchedule.getId(),
			departureStop.getId(),
			arrivalStop.getId(),
			pendingSeatBookings,
			totalFare
		);

		// redis 에 저장
		bookingRedisRepository.savePendingBooking(pendingBooking);

		return pendingBooking;
	}

	/**
	 * 회원 번호로 예약 목록 조회
	 * @param memberNo 회원 번호
	 * @return 예약 목록
	 */
	@Transactional(readOnly = true)
	public List<PendingBookingDetailResponse> getPendingBookings(String memberNo) {
		// 1. Redis에서 PendingBooking 목록 조회
		List<PendingBooking> pendingBookings = bookingRedisRepository.getPendingBookings(memberNo);

		if (pendingBookings.isEmpty()) {
			return List.of();
		}

		// 2. 예약 접근 권한 확인
		bookingValidator.validatePendingBookingOwner(pendingBookings, memberNo);

		// 3. ID 추출 (중복 제거 후 조회)
		Set<Long> trainScheduleIds = pendingBookings.stream()
			.map(PendingBooking::getTrainScheduleId)
			.collect(Collectors.toSet());

		Set<Long> stopIds = pendingBookings.stream()
			.flatMap(pb -> Stream.of(pb.getDepartureStopId(), pb.getArrivalStopId()))
			.collect(Collectors.toSet());

		Set<Long> seatIds = pendingBookings.stream()
			.flatMap(pb -> pb.getPendingSeatBookings().stream())
			.map(PendingSeatBooking::seatId)
			.collect(Collectors.toSet());

		// 4. 필요 데이터 배치 조회
		Map<Long, TrainScheduleInfo> trainScheduleMap = getTrainScheduleMap(trainScheduleIds);
		Map<Long, StopInfo> scheduleStopMap = getScheduleStopMap(stopIds);
		Map<Long, SeatInfo> seatMap = getSeatMap(seatIds);

		// 5. 각 예약의 필요한 정보를 가져올 수 있도록 매핑
		return pendingBookings.stream()
			.map(pendingBooking ->
				pendingBookingMapper.convertToPendingBookingDetail(pendingBooking, trainScheduleMap, scheduleStopMap,
					seatMap)
			)
			.toList();
	}

	/**
	 * 여러 PendingBooking 한 번에 조회 및 검증
	 * - 모든 예약이 Redis에 존재, 소유자가 일치해야 함
	 * @param pendingBookingIds 조회할 예약 아이디 리스트
	 * @param memberNo 멤버 번호
	 * @return 예약 목록
	 */
	public List<PendingBooking> getPendingBookings(List<String> pendingBookingIds, String memberNo) {
		Map<String, PendingBooking> bookingsById = bookingRedisRepository.getPendingBookingsAsMap(pendingBookingIds);

		bookingValidator.validateAllPendingBookingsExist(pendingBookingIds, bookingsById);

		List<PendingBooking> bookings = pendingBookingIds.stream()
			.map(bookingsById::get)
			.toList();

		bookingValidator.validatePendingBookingOwner(bookings, memberNo);

		return bookings;
	}

	public void deletePendingBookings(List<String> ids, String memberNo) {
		bookingRedisRepository.deletePendingBookings(ids, memberNo);
	}

	private List<PendingSeatBooking> createPendingSeatBookings(
		List<PassengerType> passengerTypes,
		List<Long> seatIds
	) {
		return IntStream.range(0, seatIds.size())
			.mapToObj(i -> new PendingSeatBooking(seatIds.get(i), passengerTypes.get(i)))
			.toList();
	}

	private ScheduleStop getStopStation(TrainSchedule trainSchedule, Long request) {
		return scheduleStopRepository.findByTrainScheduleIdAndStationId(trainSchedule.getId(), request)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));
	}

	private TrainSchedule getTrainSchedule(Long trainScheduleId) {
		return trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND));
	}

	private Map<Long, TrainScheduleInfo> getTrainScheduleMap(Set<Long> trainScheduleIds) {
		List<TrainSchedule> trainSchedules = trainScheduleRepository.findAllByIdWithTrain(trainScheduleIds);

		if (trainSchedules.size() != trainScheduleIds.size()) {
			throw new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND);
		}

		return trainSchedules.stream()
			.collect(Collectors.toMap(
				TrainSchedule::getId,
				ts -> new TrainScheduleInfo(
					String.format("%03d", ts.getTrain().getTrainNumber()),
					ts.getTrain().getTrainName(),
					ts.getOperationDate()
				)
			));
	}

	private Map<Long, StopInfo> getScheduleStopMap(Set<Long> stopIds) {
		List<ScheduleStop> scheduleStops = scheduleStopRepository.findAllByIdWithStation(stopIds);

		if (scheduleStops.size() != stopIds.size()) {
			throw new BusinessException(TrainErrorCode.STATION_NOT_FOUND);
		}

		return scheduleStops.stream()
			.collect(Collectors.toMap(
				ScheduleStop::getId,
				ss -> new StopInfo(
					ss.getStation().getStationName(),
					ss.getDepartureTime(),
					ss.getArrivalTime()
				)
			));
	}

	private Map<Long, SeatInfo> getSeatMap(Set<Long> seatIds) {
		List<Seat> seats = seatRepository.findAllByIdWithTrainCar(seatIds);

		if (seats.size() != seatIds.size()) {
			throw new BusinessException(TrainErrorCode.SEAT_NOT_FOUND);
		}

		return seats.stream()
			.collect(Collectors.toMap(
				Seat::getId,
				s -> new SeatInfo(
					s.getTrainCar().getCarNumber(),
					s.getTrainCar().getCarType(),
					String.valueOf(s.getSeatRow()).concat(s.getSeatColumn())
				)
			));
	}
}
