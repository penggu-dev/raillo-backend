package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.ReservationInfo;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationDetail;
import com.sudo.raillo.booking.application.generator.ReservationCodeGenerator;
import com.sudo.raillo.booking.application.mapper.ReservationMapper;
import com.sudo.raillo.booking.config.BookingConfig;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationQueryRepository;
import com.sudo.raillo.booking.infrastructure.ReservationRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.SeatRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {

	private final BookingConfig bookingConfig;
	private final TrainScheduleRepository trainScheduleRepository;
	private final MemberRepository memberRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final ReservationRepository reservationRepository;
	private final SeatRepository seatRepository;
	private final ReservationQueryRepository reservationQueryRepository;
	private final ReservationCodeGenerator reservationCodeGenerator;
	private final ReservationMapper reservationMapper;

	/**
	 * 예약을 생성하는 메서드
	 * @param request 예약 생성 요청 DTO
	 * @return 예약 레코드
	 */
	public Reservation createReservation(ReservationCreateRequest request, String memberNo, BigDecimal totalFare) {
		TrainSchedule trainSchedule = getTrainSchedule(request);
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
		ScheduleStop departureStop = getStopStation(trainSchedule, request.departureStationId());
		ScheduleStop arrivalStop = getStopStation(trainSchedule, request.arrivalStationId());

		validateTrainOperating(trainSchedule);

		Reservation reservation = generateReservation(
			request, trainSchedule, member, departureStop, arrivalStop, totalFare
		);
		return reservationRepository.save(reservation);
	}

	/**
	 * 객차 타입 조회
	 */
	public CarType findCarType(List<Long> seatIds) {
		List<CarType> carTypes = seatRepository.findCarTypes(seatIds);

		// 입석 체크
		if (seatIds.isEmpty()) {
			return CarType.STANDARD;
		}

		if (carTypes.isEmpty()) {
			throw new BusinessException(BookingError.SEAT_NOT_FOUND);
		}

		if (carTypes.size() != 1) {
			throw new BusinessException(BookingError.INVALID_CAR_TYPE);
		}
		return carTypes.get(0);
	}

	/**
	 * 예약을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @param reservationId 예약 ID
	 * @return 예약
	 */
	@Transactional(readOnly = true)
	public ReservationDetail getReservation(String memberNo, Long reservationId) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		List<ReservationInfo> reservationInfos = reservationQueryRepository.findReservationDetail(
			member.getId(), List.of(reservationId));

		if (reservationInfos.isEmpty()) {
			throw new BusinessException(BookingError.RESERVATION_NOT_FOUND);
		}

		ReservationInfo reservationInfo = reservationInfos.get(0);

		return reservationMapper.convertToReservationDetail(reservationInfo);
	}

	/**
	 * 예약 목록을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @return 예약 목록
	 */
	@Transactional(readOnly = true)
	public List<ReservationDetail> getReservations(String memberNo) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		List<ReservationInfo> reservationInfos = reservationQueryRepository.findReservationDetail(member.getId());

		return reservationMapper.convertToReservationDetail(reservationInfos);
	}

	/**
	 * 특정 예약을 삭제하는 메서드 - 단수 예약 ID 사용
	 * @param reservationId 삭제할 예약의 ID
	 */
	public void deleteReservation(Long reservationId) {
		reservationRepository.deleteById(reservationId);
	}

	/**
	 * 다수의 예약을 삭제하는 메서드 - 복수 예약 ID 사용
	 * @param reservationIds 삭제할 예약의 ID를 원소로 하는 리스트
	 */
	public void deleteReservation(List<Long> reservationIds) {
		reservationRepository.deleteAllByIdInBatch(reservationIds);
	}


	public void deleteAllByMemberId(Long memberId) {
		reservationRepository.deleteAllByMemberId(memberId);
	}

	private ScheduleStop getStopStation(TrainSchedule trainSchedule, Long request) {
		return scheduleStopRepository.findByTrainScheduleIdAndStationId(trainSchedule.getId(), request)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));
	}

	private TrainSchedule getTrainSchedule(ReservationCreateRequest request) {
		return trainScheduleRepository.findById(request.trainScheduleId())
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND));
	}

	private static void validateTrainOperating(TrainSchedule trainSchedule) {
		if (trainSchedule.getOperationStatus() == OperationStatus.CANCELLED) {
			throw new BusinessException(TrainErrorCode.TRAIN_OPERATION_CANCELLED);
		}
	}

	private Reservation generateReservation(
		ReservationCreateRequest request,
		TrainSchedule trainSchedule,
		Member member,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		BigDecimal totalFare
	) {
		return Reservation.builder()
			.trainSchedule(trainSchedule)
			.member(member)
			.reservationCode(reservationCodeGenerator.generateReservationCode())
			.tripType(request.tripType())
			.totalPassengers(request.passengers().stream().mapToInt(PassengerSummary::getCount).sum())
			.passengerSummary(reservationMapper.convertPassengersToJson(request))
			.reservationStatus(ReservationStatus.PAID)
			.fare(totalFare.intValue())
			.departureStop(departureStop)
			.arrivalStop(arrivalStop)
			.build();
	}
}
